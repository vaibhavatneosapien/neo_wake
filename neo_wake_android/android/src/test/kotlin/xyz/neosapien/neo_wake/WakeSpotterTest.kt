package xyz.neosapien.neo_wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported 1:1 from `test/core/neo_agent/wake_spotter_test.dart` — same
 * scenarios, same warm-up arithmetic (first embed at step 10, first score
 * at step 25), same fakes-record-shape-not-behaviour approach. This is the
 * numeric-parity evidence for the native port: every assertion here matches
 * an assertion already proven against the Dart original.
 *
 * Plain JVM test (no Android framework, no ORT) — WakeSpotter's mel/embed/
 * classify are injected hooks, so this runs under a bare `gradle test`,
 * same as [NeoWakeSessionConfigTest].
 */
private const val FIRST_EMBED_AT_STEP = 10 // ceil(76 / 8)
private const val FIRST_SCORE_AT_STEP = FIRST_EMBED_AT_STEP + WakeSpotter.EMBEDDING_RING_DEPTH - 1 // 25

private class RecordingChain(private val melValue: Double = 1.0) {
    var melValueForCall: ((Int) -> Double)? = null
    var classifyReturn: Double = 0.0

    var melCalls = 0
        private set
    var embedCalls = 0
        private set
    var classifyCalls = 0
        private set

    val melInputs = mutableListOf<FloatArray>()
    val embedInputs = mutableListOf<FloatArray>()
    val embedShapes = mutableListOf<List<Int>>()
    val classifyInputs = mutableListOf<FloatArray>()
    val classifyShapes = mutableListOf<List<Int>>()

    val mel: MelHook = { audio ->
        val callIndex = melCalls
        melCalls++
        melInputs.add(audio)
        val v = melValueForCall?.invoke(callIndex) ?: melValue
        FloatArray(WakeSpotter.MEL_FRAMES_PER_STEP * WakeSpotter.MEL_BIN_COUNT) { v.toFloat() }
    }

    val embed: EmbedHook = { melWindow, shape ->
        embedCalls++
        embedInputs.add(melWindow)
        embedShapes.add(shape)
        FloatArray(WakeSpotter.EMBEDDING_DIM) { embedCalls.toFloat() }
    }

    val classify: ClassifyHook = { embeddingWindow, shape ->
        classifyCalls++
        classifyInputs.add(embeddingWindow)
        classifyShapes.add(shape)
        classifyReturn
    }
}

private fun spotterOf(chain: RecordingChain, threshold: Double = 0.3) =
    WakeSpotter(threshold, chain.mel, chain.embed, chain.classify)

private fun frameOf(value: Short): ShortArray = ShortArray(WakeSpotter.ADVANCE_SAMPLES) { value }

private fun runSteps(spotter: WakeSpotter, count: Int): WakeSpotterStep {
    var last: WakeSpotterStep? = null
    repeat(count) { last = spotter.process(frameOf(1)) }
    return last!!
}

class WakeSpotterTest {
    @Test
    fun `a steady-state step feeds the mel hook 1760 samples and 8 mel frames enter the buffer`() {
        val chain = RecordingChain()
        val spotter = spotterOf(chain)

        spotter.process(frameOf(100))

        assertEquals(1, chain.melInputs.size)
        assertEquals(WakeSpotter.MEL_INPUT_SAMPLES, chain.melInputs[0].size)
        assertEquals(WakeSpotter.MEL_FRAMES_PER_STEP, spotter.melBufferLength)
    }

    @Test
    fun `the first step after construction feeds zero-padded overlap and produces no score`() {
        val chain = RecordingChain()
        val spotter = spotterOf(chain)

        val result = spotter.process(frameOf(50))
        val audio = chain.melInputs[0]

        assertEquals(WakeSpotter.MEL_INPUT_SAMPLES, audio.size)
        assertTrue(audio.sliceArray(0 until WakeSpotter.OVERLAP_SAMPLES).all { it == 0.0f })
        assertTrue(audio.sliceArray(WakeSpotter.OVERLAP_SAMPLES until audio.size).all { it == 50.0f })
        assertNull(result.score)
    }

    @Test
    fun `the mel buffer never exceeds its bound and slides by exactly 8`() {
        val chain = RecordingChain()
        chain.melValueForCall = { (it + 1).toDouble() }
        val spotter = spotterOf(chain)

        for (i in 0 until 14) {
            spotter.process(frameOf(1))
            assertTrue(spotter.melBufferLength <= WakeSpotter.MEL_BUFFER_FRAMES)
        }
        assertEquals(WakeSpotter.MEL_BUFFER_FRAMES, spotter.melBufferLength)

        val slideFloats = WakeSpotter.MEL_FRAMES_PER_STEP * WakeSpotter.MEL_BIN_COUNT
        val totalFloats = WakeSpotter.MEL_BUFFER_FRAMES * WakeSpotter.MEL_BIN_COUNT
        val windowA = chain.embedInputs[chain.embedInputs.size - 2]
        val windowB = chain.embedInputs[chain.embedInputs.size - 1]
        assertTrue(
            windowA.sliceArray(slideFloats until totalFloats)
                .contentEquals(windowB.sliceArray(0 until (totalFloats - slideFloats)))
        )
    }

    @Test
    fun `steady state produces exactly one embedding per 80ms step`() {
        val chain = RecordingChain()
        val spotter = spotterOf(chain)

        val steps = 20
        runSteps(spotter, steps)
        assertEquals(steps - FIRST_EMBED_AT_STEP + 1, chain.embedCalls)

        val before = chain.embedCalls
        spotter.process(frameOf(1))
        assertEquals(before + 1, chain.embedCalls)
    }

    @Test
    fun `every mel value reaching the embed hook has been scaled out of the dB band`() {
        val chain = RecordingChain(melValue = -80.0)
        val spotter = spotterOf(chain)

        runSteps(spotter, FIRST_EMBED_AT_STEP)

        assertTrue(chain.embedInputs.isNotEmpty())
        val expectedScaled = (-80.0 / 10 + 2).toFloat() // -6.0
        assertTrue(chain.embedInputs.last().all { Math.abs(it - expectedScaled) < 1e-6 })
    }

    @Test
    fun `the classify hook is not called until 16 real embeddings exist and never with a zero-filled ring`() {
        val chain = RecordingChain()
        val spotter = spotterOf(chain)

        val warmedUp = runSteps(spotter, FIRST_SCORE_AT_STEP - 1)
        assertEquals(0, chain.classifyCalls)
        assertNull(warmedUp.score)

        val result = spotter.process(frameOf(1))
        assertEquals(1, chain.classifyCalls)
        assertNotNull(result.score)
        assertEquals(WakeSpotter.EMBEDDING_RING_DEPTH * WakeSpotter.EMBEDDING_DIM, chain.classifyInputs[0].size)
        assertFalse(chain.classifyInputs[0].any { it == 0.0f })
    }

    @Test
    fun `the embedding tensor is 1,76,32,1 and the classifier tensor is 1,16,96`() {
        val chain = RecordingChain()
        val spotter = spotterOf(chain)

        runSteps(spotter, FIRST_SCORE_AT_STEP)

        assertEquals(
            listOf(1, WakeSpotter.MEL_BUFFER_FRAMES, WakeSpotter.MEL_BIN_COUNT, 1),
            chain.embedShapes.first()
        )
        assertEquals(
            listOf(1, WakeSpotter.EMBEDDING_RING_DEPTH, WakeSpotter.EMBEDDING_DIM),
            chain.classifyShapes[0]
        )
    }

    @Test
    fun `a score exactly at the threshold fires just below does not`() {
        val threshold = 0.3

        val chainAt = RecordingChain()
        chainAt.classifyReturn = threshold
        val resultAt = runSteps(spotterOf(chainAt), FIRST_SCORE_AT_STEP)
        assertTrue(resultAt.fired)

        val chainBelow = RecordingChain()
        chainBelow.classifyReturn = threshold - 0.0001
        val resultBelow = runSteps(spotterOf(chainBelow), FIRST_SCORE_AT_STEP)
        assertFalse(resultBelow.fired)
    }

    @Test
    fun `a detection clears the embedding ring and leaves mel and raw intact`() {
        val chain = RecordingChain()
        val spotter = spotterOf(chain)
        runSteps(spotter, FIRST_SCORE_AT_STEP)
        assertEquals(WakeSpotter.EMBEDDING_RING_DEPTH, spotter.embeddingRingLength)

        spotter.onDetection()
        assertEquals(0, spotter.embeddingRingLength)
        assertEquals(WakeSpotter.MEL_BUFFER_FRAMES, spotter.melBufferLength)

        val embedCallsBefore = chain.embedCalls
        val result = spotter.process(frameOf(1))

        assertEquals(embedCallsBefore + 1, chain.embedCalls)
        assertEquals(1, spotter.embeddingRingLength)
        assertNull(result.score)
    }

    @Test
    fun `a dropped frame clears the embedding ring and no non-adjacent audio reaches the mel buffer`() {
        val chain = RecordingChain()
        val spotter = spotterOf(chain)
        runSteps(spotter, FIRST_SCORE_AT_STEP)
        val melCallsBefore = chain.melCalls

        spotter.onFrameDropped()

        assertEquals(0, spotter.embeddingRingLength)
        assertEquals(melCallsBefore, chain.melCalls)
        assertEquals(WakeSpotter.MEL_BUFFER_FRAMES, spotter.melBufferLength)
    }

    @Test
    fun `a full reset clears all three and no detection is possible until 16 fresh embeddings exist`() {
        val chain = RecordingChain()
        val spotter = spotterOf(chain)
        runSteps(spotter, FIRST_SCORE_AT_STEP)
        assertEquals(WakeSpotter.EMBEDDING_RING_DEPTH, spotter.embeddingRingLength)

        spotter.reset()
        assertEquals(0, spotter.melBufferLength)
        assertEquals(0, spotter.embeddingRingLength)

        val first = spotter.process(frameOf(1))
        assertTrue(chain.melInputs.last().sliceArray(0 until WakeSpotter.OVERLAP_SAMPLES).all { it == 0.0f })
        assertEquals(0, first.stepIndex)
        assertNull(first.score)

        for (i in 0 until FIRST_SCORE_AT_STEP - 2) {
            val r = spotter.process(frameOf(1))
            assertNull(r.score)
        }
        val last = spotter.process(frameOf(1))
        assertNotNull(last.score)
    }

    @Test
    fun `ring and buffer memory are flat across 10,000 simulated steps`() {
        val chain = RecordingChain()
        val spotter = spotterOf(chain)

        for (i in 0 until 10000) {
            spotter.process(frameOf(1))
            assertTrue(spotter.melBufferLength <= WakeSpotter.MEL_BUFFER_FRAMES)
            assertTrue(spotter.embeddingRingLength <= WakeSpotter.EMBEDDING_RING_DEPTH)
        }
        assertEquals(WakeSpotter.MEL_BUFFER_FRAMES, spotter.melBufferLength)
        assertEquals(WakeSpotter.EMBEDDING_RING_DEPTH, spotter.embeddingRingLength)
    }
}
