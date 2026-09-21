package xyz.neosapien.neo_wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Plain JVM tests for the chorus6 [WakeSpotter] gate: ring geometry and
 * scaling, the two-hop confirm, hysteresis re-arm, ring reset on fire and on
 * a drop. The frontend/body are injected fakes recording shape, not
 * behaviour — real ONNX numerics are the device tier's job
 * ([NeoWakeSessionsInstrumentedTest], `WakeSpotterDeviceGoldenTest`).
 */
private class FakeChain {
    /** Scripted per-hop `[background, wake_up_neo, neo_wake_up]`; the last entry repeats. */
    var probsForCall: (Int) -> FloatArray = { floatArrayOf(0.96f, 0.02f, 0.02f) }

    val frontendInputs = mutableListOf<FloatArray>()
    var bodyCalls = 0
        private set

    val frontend: FrontendHook = { audio ->
        frontendInputs.add(audio)
        FloatArray(WakeSpotter.LOGMEL_FLOATS)
    }

    val body: BodyHook = { logmel ->
        assertEquals(WakeSpotter.LOGMEL_FLOATS, logmel.size)
        probsForCall(bodyCalls++)
    }

    fun script(vararg probs: FloatArray) {
        probsForCall = { i -> probs[minOf(i, probs.size - 1)] }
    }
}

private fun spotterOf(chain: FakeChain, threshold: Double = 0.45) =
    WakeSpotter(threshold, chain.frontend, chain.body)

private fun frameOf(value: Short): ShortArray = ShortArray(WakeSpotter.ADVANCE_SAMPLES) { value }

private val BACKGROUND = floatArrayOf(0.96f, 0.02f, 0.02f)
private val HIGH = floatArrayOf(0.1f, 0.5f, 0.4f) // sum 0.9

class WakeSpotterTest {
    @Test
    fun `a frame of 16384 lands in the ring tail as 0_5 behind 30720 zeros`() {
        val chain = FakeChain()
        val spotter = spotterOf(chain)

        spotter.process(frameOf(16384))

        val audio = chain.frontendInputs.single()
        assertEquals(WakeSpotter.WINDOW_SAMPLES, audio.size)
        val tail = WakeSpotter.WINDOW_SAMPLES - WakeSpotter.ADVANCE_SAMPLES
        assertTrue(audio.sliceArray(0 until tail).all { it == 0f })
        assertTrue(audio.sliceArray(tail until audio.size).all { it == 0.5f })
    }

    @Test
    fun `the ring shifts by exactly one advance per step`() {
        val chain = FakeChain()
        val spotter = spotterOf(chain)

        spotter.process(frameOf(16384)) // 0.5
        spotter.process(frameOf(-16384)) // -0.5

        val audio = chain.frontendInputs.last()
        val tail = WakeSpotter.WINDOW_SAMPLES - WakeSpotter.ADVANCE_SAMPLES
        val prev = tail - WakeSpotter.ADVANCE_SAMPLES
        assertTrue(audio.sliceArray(0 until prev).all { it == 0f })
        assertTrue(audio.sliceArray(prev until tail).all { it == 0.5f })
        assertTrue(audio.sliceArray(tail until audio.size).all { it == -0.5f })
    }

    @Test
    fun `every hop scores and the score is the sum of the two wake classes`() {
        val chain = FakeChain()
        chain.script(floatArrayOf(0.15f, 0.45f, 0.40f))
        val spotter = spotterOf(chain)

        val step = spotter.process(frameOf(1))

        assertNotNull(step.score)
        assertEquals(0.85, step.score!!, 1e-6)
        assertEquals(0, step.stepIndex)
    }

    @Test
    fun `two consecutive hops over threshold fire once then the third does not`() {
        val chain = FakeChain()
        chain.script(HIGH)
        val spotter = spotterOf(chain)

        val first = spotter.process(frameOf(1))
        assertFalse(first.fired)
        assertEquals(1, spotter.consecutiveOverThreshold)

        val second = spotter.process(frameOf(1))
        assertTrue(second.fired)
        assertFalse(spotter.isArmed)

        val third = spotter.process(frameOf(1))
        assertFalse("disarmed after a fire — no re-fire on a still-high score", third.fired)
    }

    @Test
    fun `a single hop over threshold never fires`() {
        val chain = FakeChain()
        chain.script(HIGH, BACKGROUND)
        val spotter = spotterOf(chain)

        assertFalse(spotter.process(frameOf(1)).fired)
        assertFalse(spotter.process(frameOf(1)).fired)
        assertEquals(0, spotter.consecutiveOverThreshold)
    }

    @Test
    fun `hysteresis re-arms only below release not merely below threshold`() {
        val chain = FakeChain()
        val aboveRelease = floatArrayOf(0.70f, 0.20f, 0.10f) // 0.30 > release 0.2475
        val belowRelease = floatArrayOf(0.80f, 0.10f, 0.10f) // 0.20 < release
        chain.script(HIGH, HIGH, aboveRelease, HIGH, HIGH, belowRelease, HIGH, HIGH)
        val spotter = spotterOf(chain)

        spotter.process(frameOf(1))
        assertTrue(spotter.process(frameOf(1)).fired) // fire #1
        spotter.process(frameOf(1)) // 0.30: still disarmed
        assertFalse(spotter.isArmed)
        spotter.process(frameOf(1))
        assertFalse("two high hops while disarmed must not fire", spotter.process(frameOf(1)).fired)
        spotter.process(frameOf(1)) // 0.20: re-arms
        assertTrue(spotter.isArmed)
        spotter.process(frameOf(1))
        assertTrue(spotter.process(frameOf(1)).fired) // fire #2
    }

    @Test
    fun `a single class at 0_5 also fires because the sum not one class is compared`() {
        val chain = FakeChain()
        chain.script(floatArrayOf(0.5f, 0.45f, 0.05f)) // sum 0.50 >= 0.45
        val spotter = spotterOf(chain)

        spotter.process(frameOf(1))
        assertTrue(spotter.process(frameOf(1)).fired)
    }

    @Test
    fun `a score exactly at the threshold counts just below does not`() {
        val at = FakeChain()
        at.script(floatArrayOf(0.55f, 0.25f, 0.20f)) // 0.45
        val atSpotter = spotterOf(at)
        atSpotter.process(frameOf(1))
        assertTrue(atSpotter.process(frameOf(1)).fired)

        val below = FakeChain()
        below.script(floatArrayOf(0.5501f, 0.25f, 0.1999f)) // 0.4499
        val belowSpotter = spotterOf(below)
        belowSpotter.process(frameOf(1))
        assertFalse(belowSpotter.process(frameOf(1)).fired)
    }

    @Test
    fun `after a fire the ring holds 30720 zeros and only the new frame`() {
        val chain = FakeChain()
        chain.script(HIGH, HIGH, BACKGROUND)
        val spotter = spotterOf(chain)

        spotter.process(frameOf(16384))
        assertTrue(spotter.process(frameOf(16384)).fired)
        spotter.process(frameOf(-16384))

        val audio = chain.frontendInputs.last()
        val tail = WakeSpotter.WINDOW_SAMPLES - WakeSpotter.ADVANCE_SAMPLES
        assertTrue("consumed audio must be zeroed", audio.sliceArray(0 until tail).all { it == 0f })
        assertTrue(audio.sliceArray(tail until audio.size).all { it == -0.5f })
        assertEquals(0, spotter.consecutiveOverThreshold)
    }

    @Test
    fun `a dropped frame zeroes the ring and the run but leaves armed unchanged`() {
        val chain = FakeChain()
        val aboveRelease = floatArrayOf(0.70f, 0.20f, 0.10f) // 0.30: below threshold, above release
        chain.script(HIGH, HIGH, aboveRelease, HIGH, BACKGROUND)
        val spotter = spotterOf(chain)

        spotter.process(frameOf(16384))
        assertTrue(spotter.process(frameOf(16384)).fired)
        spotter.process(frameOf(16384)) // 0.30 keeps it disarmed
        assertFalse(spotter.isArmed)
        spotter.process(frameOf(16384)) // run = 1 while disarmed
        assertEquals(1, spotter.consecutiveOverThreshold)

        spotter.onFrameDropped()

        assertEquals(0, spotter.consecutiveOverThreshold)
        assertFalse("a drop is not a fire and not a re-arm", spotter.isArmed)
        spotter.process(frameOf(-16384))
        val audio = chain.frontendInputs.last()
        val tail = WakeSpotter.WINDOW_SAMPLES - WakeSpotter.ADVANCE_SAMPLES
        assertTrue(audio.sliceArray(0 until tail).all { it == 0f })
    }

    @Test
    fun `a full reset re-arms zeroes the ring and restarts the step index`() {
        val chain = FakeChain()
        chain.script(HIGH)
        val spotter = spotterOf(chain)
        spotter.process(frameOf(16384))
        assertTrue(spotter.process(frameOf(16384)).fired)
        assertFalse(spotter.isArmed)

        spotter.reset()

        assertTrue(spotter.isArmed)
        val first = spotter.process(frameOf(1))
        assertEquals(0, first.stepIndex)
        val audio = chain.frontendInputs.last()
        val tail = WakeSpotter.WINDOW_SAMPLES - WakeSpotter.ADVANCE_SAMPLES
        assertTrue(audio.sliceArray(0 until tail).all { it == 0f })
    }

    @Test
    fun `a frame of the wrong length throws`() {
        val spotter = spotterOf(FakeChain())
        try {
            spotter.process(ShortArray(WakeSpotter.ADVANCE_SAMPLES - 1))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1280"))
        }
    }

    @Test
    fun `a body hook returning the wrong class count throws`() {
        val chain = FakeChain()
        chain.probsForCall = { floatArrayOf(1f) }
        val spotter = spotterOf(chain)
        try {
            spotter.process(frameOf(1))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("expected 3"))
        }
    }

    @Test
    fun `ring memory is flat across 10,000 simulated steps`() {
        val chain = FakeChain()
        val spotter = spotterOf(chain)
        for (i in 0 until 10000) {
            spotter.process(frameOf(1))
            assertEquals(WakeSpotter.WINDOW_SAMPLES, chain.frontendInputs.last().size)
            chain.frontendInputs.clear()
        }
        assertEquals(10000, chain.bodyCalls)
    }
}
