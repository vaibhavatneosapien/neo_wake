package xyz.neosapien.neo_wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Ported 1:1 from the `PcmFramer` group in
// `test/core/neo_agent/wake_word_service_test.dart`.
private const val SAMPLES_PER_FRAME = 160
private val ENGINE_FRAME = WakeSpotter.ADVANCE_SAMPLES

private fun ramp(n: Int, start: Int = 0): ShortArray =
    ShortArray(n) { ((start + it) % 32767).toShort() }

class WakePcmFramerTest {
    @Test
    fun `emits 1280-sample frames with no overlap, carry never grows across 1000 fragments`() {
        val framer = WakePcmFramer(ENGINE_FRAME)
        val emitted = mutableListOf<ShortArray>()
        val fragments = 1000

        for (i in 0 until fragments) {
            framer.add(ramp(SAMPLES_PER_FRAME, start = i * SAMPLES_PER_FRAME)) { emitted.add(it) }
            assertTrue(framer.pending < ENGINE_FRAME)
        }
        assertEquals(125, emitted.size)
        assertEquals(0, framer.pending)

        for (i in emitted.indices) {
            assertTrue(
                "frame $i mismatch",
                emitted[i].contentEquals(ramp(ENGINE_FRAME, start = i * ENGINE_FRAME))
            )
        }
    }

    @Test
    fun `eight pendant fragments produce exactly one frame`() {
        val framer = WakePcmFramer(ENGINE_FRAME)
        val emitted = mutableListOf<ShortArray>()
        for (i in 0 until 8) {
            framer.add(ramp(SAMPLES_PER_FRAME, start = i * SAMPLES_PER_FRAME)) { emitted.add(it) }
        }
        assertEquals(1, emitted.size)
        assertEquals(0, framer.pending)
    }

    @Test
    fun `hands out a copy, not the live carry buffer`() {
        val framer = WakePcmFramer(4)
        val emitted = mutableListOf<ShortArray>()
        framer.add(shortArrayOf(1, 2, 3, 4)) { emitted.add(it) }
        val first = emitted[0]
        assertTrue(first.contentEquals(shortArrayOf(1, 2, 3, 4)))

        framer.add(shortArrayOf(9, 9, 9, 9)) { emitted.add(it) }
        assertTrue(
            "the first frame must survive the second frame being built",
            first.contentEquals(shortArrayOf(1, 2, 3, 4))
        )
    }

    @Test
    fun `reset drops the pending remainder`() {
        val framer = WakePcmFramer(ENGINE_FRAME)
        framer.add(ramp(SAMPLES_PER_FRAME)) { }
        assertEquals(SAMPLES_PER_FRAME, framer.pending)
        framer.reset()
        assertEquals(0, framer.pending)
    }
}

// Ported 1:1 from the `HeaderProbe` and `rmsOf` groups in the same file.
class WakeHeaderProbeTest {
    @Test
    fun `picks the offset that decoded more often`() {
        val probe = WakeHeaderProbe(framesNeeded = 3)
        repeat(3) {
            probe.record(3, decoded = false, rms = 0.0)
            probe.record(4, decoded = true, rms = 0.4)
        }
        assertTrue(probe.done)
        assertEquals(4, probe.verdict(3))
    }

    @Test
    fun `breaks a tie on accumulated energy`() {
        val probe = WakeHeaderProbe(framesNeeded = 2)
        repeat(2) {
            probe.record(3, decoded = true, rms = 0.1)
            probe.record(4, decoded = true, rms = 0.9)
        }
        assertEquals(4, probe.verdict(3))
    }

    @Test
    fun `falls back when neither offset ever decoded`() {
        val probe = WakeHeaderProbe(framesNeeded = 2)
        repeat(2) {
            probe.record(3, decoded = false, rms = 0.0)
            probe.record(4, decoded = false, rms = 0.0)
        }
        assertEquals(3, probe.verdict(3))
        assertEquals(4, probe.verdict(4))
    }

    @Test
    fun `is not done before it has seen enough frames`() {
        val probe = WakeHeaderProbe(framesNeeded = 10)
        probe.record(4, decoded = true, rms = 0.5)
        assertEquals(false, probe.done)
        assertEquals(1, probe.framesSeen)
    }

    @Test
    fun `rmsOf silence is zero`() {
        assertEquals(0.0, rmsOf(ShortArray(320)), 0.0)
    }

    @Test
    fun `rmsOf full scale is one`() {
        val full = ShortArray(320) { 32767 }
        assertEquals(1.0, rmsOf(full), 1e-6)
    }

    @Test
    fun `rmsOf empty block is zero rather than a division error`() {
        assertEquals(0.0, rmsOf(ShortArray(0)), 0.0)
    }
}
