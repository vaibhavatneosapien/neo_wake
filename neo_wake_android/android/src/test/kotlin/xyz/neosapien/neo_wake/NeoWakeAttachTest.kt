package xyz.neosapien.neo_wake

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for [NeoWakeAttach]'s attach-outcome logic (Fix 1,
 * review of U8-core).
 *
 * Calls [NeoWakeAttach.attach] directly rather than through [NeoWakeAttach.arm]
 * / [NeoWakeAttach.bootstrap]: those resolve a uid through neo_ble's
 * SharedPreferences convention first, which needs a real Android SharedPreferences
 * implementation this plain JVM test has no way to back (see
 * [NeoWakeSessionConfigTest]'s own note on the same ORT/Android constraint).
 * [attach] itself touches neither — only `context.applicationContext` (which
 * [FakeContext] overrides directly, no stub jar involved) — so it is reachable
 * standalone via the `internal` test seam.
 *
 * Neither `neo_ble`'s `BleEventSinks` class nor `xyz.neosapien.neo_ble.upload.
 * NeoAudioUploader` are on this module's test classpath (by design — see
 * [NeoBleAudioBridge]'s doc), so [NeoBleAudioBridge.addAudioListener] reliably
 * returns `false` here: exactly the "registration failed" case Fix 1 is about,
 * with no mocking required.
 */
class NeoWakeAttachTest {

    private class FakeContext : ContextWrapper(null) {
        private val files = createTempDir("neo_wake_attach_test")
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = files
    }

    private fun record(modelVersion: String = "cover", threshold: Double = 0.3, lagMs: Int = 0) = NeoWakeArmRecord(
        armed = true,
        ownerUid = "uid-1",
        modelVersion = modelVersion,
        threshold = threshold,
        lagMs = lagMs,
    )

    /** Makes attach() succeed on the JVM: sessions are a no-op and the
     * listener registration is faked as accepted. */
    private fun allowAttachToSucceed(): MutableList<String> {
        val registered = mutableListOf<String>()
        NeoWakeAttach.sessionsInit = { }
        NeoWakeAttach.listenerRegistrar = { key, _ -> registered.add(key); true }
        return registered
    }

    @After
    fun tearDown() {
        NeoWakeAttach.resetForTest()
    }

    @Test
    fun attach_whenListenerRegistrationFails_leavesAttachedFalse() {
        var sessionInitCalls = 0
        NeoWakeAttach.sessionsInit = { sessionInitCalls++ }

        NeoWakeAttach.attach(FakeContext(), record())

        assertFalse("a failed registration must never latch attached=true", NeoWakeAttach.isAttached)
        assertEquals(1, sessionInitCalls)
    }

    @Test
    fun attach_afterAFailedRegistration_isRetryable_notBlockedByAStaleLatch() {
        var sessionInitCalls = 0
        NeoWakeAttach.sessionsInit = { sessionInitCalls++ }
        val ctx = FakeContext()
        val rec = record()

        NeoWakeAttach.attach(ctx, rec)
        assertFalse(NeoWakeAttach.isAttached)

        // The bug this guards: a stale `attached=true` from the first
        // (failed) attempt would make the `if (attached) return` guard
        // silently no-op every later retry — proven here by a second
        // sessionsInit call actually happening, not by the count staying at 1.
        NeoWakeAttach.attach(ctx, rec)

        assertFalse(NeoWakeAttach.isAttached)
        assertEquals("a second attach() must actually reattempt, not be " +
            "swallowed by a leftover attached=true latch", 2, sessionInitCalls)
    }

    // Command-mode UI parity plan (U1/U3): `currentCommandMode()` is both
    // the `command_state` EventChannel's on-subscribe snapshot AND the pull
    // provider neo_ble's connect-ready reconcile reads. Real capture-open/
    // close/resume flows (and NeoWakePlugin's `emitCommandState`, which
    // constructs a `Handler(Looper.getMainLooper())`) need a live Android
    // frame/Looper this plain JVM test cannot back — see this class's own
    // doc on why [attach] is exercised directly instead of via a real
    // capture pipeline. What IS reachable here: the default/failed-attach
    // state, which is exactly what neo_ble's reconcile falls back to
    // (`provider?.invoke() ?: false`) whenever nothing is really capturing.

    @Test
    fun currentCommandMode_whenNeverAttached_isFalse() {
        assertFalse(NeoWakeAttach.currentCommandMode())
    }

    @Test
    fun currentCommandMode_afterAFailedAttach_staysFalse() {
        NeoWakeAttach.sessionsInit = { }

        NeoWakeAttach.attach(FakeContext(), record())

        assertFalse(NeoWakeAttach.isAttached)
        assertFalse(
            "no live WakeCommandCapture after a failed attach — the reconcile " +
                "provider must read false, not stale/leftover state",
            NeoWakeAttach.currentCommandMode(),
        )
    }
    // KTD12: a live arm() with a changed record must rebuild the session in
    // this process, not at the next launch — attach() alone is an idempotent
    // no-op while attached.

    @Test
    fun recordChanged_isTrueOnlyWhenModelThresholdOrLagDiffer() {
        val live = record(modelVersion = "cover", threshold = 0.3, lagMs = 0)
        assertFalse(NeoWakeAttach.recordChanged(null, live))
        assertFalse(NeoWakeAttach.recordChanged(live, live.copy(ownerUid = "someone-else")))
        assertTrue(NeoWakeAttach.recordChanged(live, live.copy(modelVersion = "chorus6")))
        assertTrue(NeoWakeAttach.recordChanged(live, live.copy(threshold = 0.45)))
        assertTrue(NeoWakeAttach.recordChanged(live, live.copy(lagMs = 80)))
    }

    @Test
    fun attachOrRebuild_withAnUnchangedRecord_keepsTheLiveSession() {
        val registered = allowAttachToSucceed()
        val ctx = FakeContext()
        val rec = record()

        NeoWakeAttach.attachOrRebuild(ctx, rec)
        assertTrue(NeoWakeAttach.isAttached)
        NeoWakeAttach.attachOrRebuild(ctx, rec.copy())

        assertTrue(NeoWakeAttach.isAttached)
        assertEquals("an unchanged record must not re-register the listener", 1, registered.size)
    }

    @Test
    fun attachOrRebuild_withAChangedRecord_detachesAndReattachesOnce() {
        val registered = allowAttachToSucceed()
        var sessionInitCalls = 0
        NeoWakeAttach.sessionsInit = { sessionInitCalls++ }
        val ctx = FakeContext()

        NeoWakeAttach.attachOrRebuild(ctx, record(modelVersion = "cover", threshold = 0.3, lagMs = 0))
        assertTrue(NeoWakeAttach.isAttached)
        NeoWakeAttach.attachOrRebuild(ctx, record(modelVersion = "chorus6", threshold = 0.45, lagMs = 80))

        assertTrue(NeoWakeAttach.isAttached)
        assertEquals("the stale 'cover' session is torn down and a chorus6 one built in-process", 2, sessionInitCalls)
        assertEquals(2, registered.size)
    }
}
