package xyz.neosapien.neo_wake

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        override fun getApplicationContext(): Context = this
    }

    private fun record() = NeoWakeArmRecord(
        armed = true,
        ownerUid = "uid-1",
        modelVersion = "neo_sim_sim_encore",
        threshold = 0.5,
        lagMs = 250,
    )

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
}
