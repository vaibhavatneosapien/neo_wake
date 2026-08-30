package xyz.neosapien.neo_wake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure JVM unit tests for [WakeCommandCapture] (U7 / KTD5, plan
 * 2026-08-30-0038). Mirrors the scenarios in
 * `test/core/neo_agent/wake_word_service_test.dart`'s capture-machinery
 * group, ported to synthetic frames since this unit is not wired to live
 * audio until U8.
 */
class WakeCommandCaptureTest {

    private fun frame(tag: Int, size: Int = 4): ByteArray = ByteArray(size) { (tag + it).toByte() }

    private fun unflatten(bytes: ByteArray): List<ByteArray> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val out = mutableListOf<ByteArray>()
        while (buf.remaining() > 0) {
            val len = buf.int
            val f = ByteArray(len)
            buf.get(f)
            out.add(f)
        }
        return out
    }

    @Test
    fun fireOpensAndAssemblesClipFromPrerollRing_taggedWakePhrase() {
        val cap = WakeCommandCapture(
            WakeCommandCaptureConfig(prerollWindowMs = 50, tailTrimMs = 10, minCommandMs = 10, frameMs = 10),
        )
        // 5 idle frames feed the ring (preroll window = 5 frames @ 10ms).
        for (i in 0 until 5) cap.feed(frame(i), nowMs = i * 10L)
        assertEquals(WakeCaptureState.IDLE, cap.state)

        val opened = cap.onFire(nowMs = 50L)
        assertNull("opening a capture never emits a clip", opened)
        assertEquals(WakeCaptureState.CAPTURING, cap.state)

        // Command audio arrives while capturing.
        for (i in 5 until 5 + 40) cap.feed(frame(i), nowMs = 50L + (i - 5) * 10L)

        val clip = cap.onFire(nowMs = 450L) // second fire closes
        assertNotNull(clip)
        assertEquals(WakeCommandSource.WAKE_PHRASE, clip!!.source)
        assertEquals(WakeCaptureState.IDLE, cap.state)
    }

    @Test
    fun clipCarriesThePrerollRingContent() {
        val cap = WakeCommandCapture(
            WakeCommandCaptureConfig(prerollWindowMs = 50, tailTrimMs = 10, minCommandMs = 50, frameMs = 10),
        )
        val prerollFrames = (0 until 5).map { frame(it) }
        prerollFrames.forEachIndexed { i, f -> cap.feed(f, nowMs = i * 10L) }

        cap.onFire(nowMs = 50L) // open — drains preroll into the clip
        for (i in 5 until 15) cap.feed(frame(i), nowMs = 50L + (i - 5) * 10L)
        val clip = cap.onFire(nowMs = 200L) // close, tiny tail trim (1 frame)

        assertNotNull(clip)
        val frames = unflatten(clip!!.audioBytes)
        // 5 preroll + 10 command - 1 trimmed = 14 frames, and the preroll
        // content is exactly what was fed before the fire.
        assertEquals(14, frames.size)
        for (i in 0 until 5) assertArrayEquals(frame(i), frames[i])
    }

    @Test
    fun secondFireClosesAndTailTrimDropsTheClosingPhrase() {
        val cap = WakeCommandCapture(
            WakeCommandCaptureConfig(prerollWindowMs = 10, tailTrimMs = 30, minCommandMs = 10, frameMs = 10),
        )
        cap.onFire(nowMs = 0L)
        // 10 command frames, then simulate the closing "neo simsim" as the
        // last 3 frames (tailTrimMs=30 -> 3 frames trimmed).
        for (i in 0 until 10) cap.feed(frame(100 + i), nowMs = i * 10L)
        val clip = cap.onFire(nowMs = 100L)

        assertNotNull(clip)
        val frames = unflatten(clip!!.audioBytes)
        assertEquals(7, frames.size) // 10 - 3 trimmed
        // The trimmed frames are the LAST three fed.
        assertArrayEquals(frame(106), frames.last())
    }

    @Test
    fun noSecondFire_wallClockCeilingClosesTheWindow() {
        val cap = WakeCommandCapture(
            WakeCommandCaptureConfig(maxClipMs = 1000, minCommandMs = 10, frameMs = 10),
        )
        cap.onFire(nowMs = 0L)
        for (i in 0 until 50) cap.feed(frame(i), nowMs = i * 10L)

        assertNull("ceiling not yet reached", cap.tick(nowMs = 500L))
        assertEquals(WakeCaptureState.CAPTURING, cap.state)

        val clip = cap.tick(nowMs = 1000L)
        assertNotNull("ceiling reached — closes without a second fire", clip)
        assertEquals("ceiling", clip!!.reason)
        assertEquals(WakeCaptureState.IDLE, cap.state)
    }

    @Test
    fun disconnectMidCapture_finalizesWithoutTrim() {
        val cap = WakeCommandCapture(WakeCommandCaptureConfig(minCommandMs = 10, frameMs = 10))
        cap.onFire(nowMs = 0L)
        for (i in 0 until 20) cap.feed(frame(i), nowMs = i * 10L)

        val clip = cap.onDisconnect(nowMs = 200L)
        assertNotNull(clip)
        assertEquals("pendant_disconnected", clip!!.reason)
        assertEquals(20, unflatten(clip.audioBytes).size) // nothing trimmed
        assertEquals(WakeCaptureState.IDLE, cap.state)
    }

    @Test
    fun tooShortAfterTrim_isDiscarded_returnsNull() {
        val cap = WakeCommandCapture(
            WakeCommandCaptureConfig(tailTrimMs = 1500, minCommandMs = 200, frameMs = 10),
        )
        cap.onFire(nowMs = 0L)
        for (i in 0 until 10) cap.feed(frame(i), nowMs = i * 10L) // 100ms of command
        val clip = cap.onFire(nowMs = 100L) // 1500ms tail trim eats it all

        assertNull(clip)
        assertEquals(WakeCaptureState.IDLE, cap.state)
    }

    @Test
    fun wakeEndMsFromPreroll_matchesDartFormula() {
        assertEquals(1000, wakeEndMsFromPreroll(prerollFrames = 100, lagMs = 0, frameMs = 10))
        assertEquals(700, wakeEndMsFromPreroll(prerollFrames = 100, lagMs = 300, frameMs = 10))
        assertEquals(0, wakeEndMsFromPreroll(prerollFrames = 10, lagMs = 999, frameMs = 10)) // floors at 0
    }

    @Test
    fun tailTrimCoversLag_invariant() {
        assertTrue(tailTrimCoversLag(tailTrimMs = 1500, lagMs = 0))
        assertTrue(!tailTrimCoversLag(tailTrimMs = 1500, lagMs = 600)) // 600+1000 > 1500
    }

    @Test
    fun flattenOpus_roundTripsFrameBoundaries() {
        val frames = listOf(frame(1, 3), frame(2, 0), frame(3, 7))
        val flat = flattenOpus(frames)
        val back = unflatten(flat)
        assertEquals(frames.size, back.size)
        frames.indices.forEach { assertArrayEquals(frames[it], back[it]) }
    }

    // ---- U9 / KTD11 ambient/command isolation seam -------------------------

    @Test
    fun onCaptureOpened_firesOnceOnTheWakeFireThatOpens_notOnTheCloseFire() {
        val cap = WakeCommandCapture(WakeCommandCaptureConfig(minCommandMs = 10, frameMs = 10))
        val opened = mutableListOf<String>()
        cap.onCaptureOpened = { opened.add(it) }

        cap.onFire(nowMs = 0L) // opens
        assertEquals(1, opened.size)

        for (i in 0 until 5) cap.feed(frame(i), nowMs = i * 10L)
        cap.onFire(nowMs = 50L) // closes — must NOT fire onCaptureOpened again

        assertEquals(1, opened.size)
    }

    @Test
    fun onCaptureClosed_firesWithTheSameCaptureIdOnTheClosingFire() {
        val cap = WakeCommandCapture(
            WakeCommandCaptureConfig(tailTrimMs = 10, minCommandMs = 10, frameMs = 10),
        )
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        cap.onCaptureOpened = { opened.add(it) }
        cap.onCaptureClosed = { closed.add(it) }

        cap.onFire(nowMs = 0L)
        for (i in 0 until 5) cap.feed(frame(i), nowMs = i * 10L)
        val clip = cap.onFire(nowMs = 50L)

        assertNotNull(clip)
        assertEquals(1, opened.size)
        assertEquals(1, closed.size)
        assertEquals("the ambient resume signal must correlate to the same capture", opened[0], closed[0])
    }

    @Test
    fun onCaptureClosed_firesEvenWhenTheClipIsDiscardedAsTooShort() {
        val cap = WakeCommandCapture(
            WakeCommandCaptureConfig(tailTrimMs = 1500, minCommandMs = 200, frameMs = 10),
        )
        var closedCount = 0
        var clipReadyCount = 0
        cap.onCaptureClosed = { closedCount++ }
        cap.onClipReady = { clipReadyCount++ }

        cap.onFire(nowMs = 0L)
        for (i in 0 until 10) cap.feed(frame(i), nowMs = i * 10L)
        val clip = cap.onFire(nowMs = 100L) // discarded — tail trim eats it all

        assertNull(clip)
        assertEquals("the ambient feed must resume even though no clip was produced", 1, closedCount)
        assertEquals(0, clipReadyCount)
    }

    @Test
    fun onCaptureClosed_firesOnTheWallClockCeiling() {
        val cap = WakeCommandCapture(WakeCommandCaptureConfig(maxClipMs = 1000, minCommandMs = 10, frameMs = 10))
        var closedId: String? = null
        var openedId: String? = null
        cap.onCaptureOpened = { openedId = it }
        cap.onCaptureClosed = { closedId = it }

        cap.onFire(nowMs = 0L)
        for (i in 0 until 50) cap.feed(frame(i), nowMs = i * 10L)
        assertNull(cap.tick(nowMs = 500L))
        assertNull("no premature resume before the ceiling", closedId)

        cap.tick(nowMs = 1000L)
        assertEquals(openedId, closedId)
    }

    @Test
    fun onCaptureClosed_firesOnDisconnectMidCapture_butNotWhenIdle() {
        val cap = WakeCommandCapture(WakeCommandCaptureConfig(minCommandMs = 10, frameMs = 10))
        var closedCount = 0
        cap.onCaptureClosed = { closedCount++ }

        // Idle disconnect — nothing was open, no resume signal to send.
        cap.onDisconnect(nowMs = 0L)
        assertEquals(0, closedCount)

        cap.onFire(nowMs = 100L)
        for (i in 0 until 5) cap.feed(frame(i), nowMs = 100L + i * 10L)
        cap.onDisconnect(nowMs = 200L)
        assertEquals(1, closedCount)
    }

    @Test
    fun captureHooks_defaultToNull_dormantByDesign() {
        val cap = WakeCommandCapture(
            WakeCommandCaptureConfig(tailTrimMs = 10, minCommandMs = 10, frameMs = 10),
        )
        assertNull(cap.onCaptureOpened)
        assertNull(cap.onCaptureClosed)

        // Must not throw with both hooks unset (the live U8 binding is
        // optional until wired).
        cap.onFire(nowMs = 0L)
        for (i in 0 until 5) cap.feed(frame(i), nowMs = i * 10L)
        val clip = cap.onFire(nowMs = 50L)
        assertNotNull(clip)
    }
}
