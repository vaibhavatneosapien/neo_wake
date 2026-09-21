package xyz.neosapien.neo_wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * A fake Opus decoder: "decodes" only when the payload starts with [marker]
 * and holds enough bytes, so tests can force decode failure at the wrong
 * header offset without a real libopus — the wrong offset reads the marker
 * byte from the wrong position (or from mid-payload garbage) and naturally
 * fails, exactly mirroring how a real decoder rejects a misaligned Opus
 * bitstream. On success, samples are `payload[1]` repeated [frameSize] times
 * — deterministic and distinguishable per test fixture.
 */
private class FakeOpusDecoder(private val marker: Byte = 0xAB.toByte()) : OpusDecoding {
    var decodeCalls = 0
        private set

    override fun decode(payload: ByteArray, frameSize: Int): ShortArray? {
        decodeCalls++
        if (payload.size < 2 || payload[0] != marker) return null
        val value = (payload[1].toInt() and 0xFF).toShort()
        return ShortArray(frameSize) { value }
    }
}

/** Builds one raw BLE fragment: `[seq lo][seq hi][reserved][cmd?][marker][value]`. */
private fun opusFragment(headerLen: Int, marker: Byte, value: Int, seq: Int = 0): ByteArray {
    val out = mutableListOf<Byte>(
        (seq and 0xFF).toByte(),
        ((seq shr 8) and 0xFF).toByte(),
        0,
    )
    if (headerLen == 4) out.add(0)
    out.add(marker)
    out.add(value.toByte())
    return out.toByteArray()
}

private val BACKGROUND = floatArrayOf(0.96f, 0.02f, 0.02f)
private val HIGH = floatArrayOf(0.1f, 0.5f, 0.4f) // sum 0.9

private class RecordingHooks {
    val frontendInputs = mutableListOf<FloatArray>()
    var probs: FloatArray = BACKGROUND
    val frontend: FrontendHook = { audio ->
        frontendInputs.add(audio)
        FloatArray(WakeSpotter.LOGMEL_FLOATS)
    }
    val body: BodyHook = { _ -> probs }
}

private const val RING_TAIL = WakeSpotter.WINDOW_SAMPLES - WakeSpotter.ADVANCE_SAMPLES
private const val SCALE_10 = 10f / 32768f
private const val SCALE_42 = 42f / 32768f

class WakeCodecPipelineTest {
    @Test
    fun `correct offset decodes and carries overlap through the pipeline`() {
        val hooks = RecordingHooks()
        val spotter = WakeSpotter(0.9, hooks.frontend, hooks.body)
        val fake = FakeOpusDecoder()
        val pipeline = WakeCodecPipeline(
            spotter = spotter, codec = NeoWakeAudioCodec.OPUS, samplesPerFrame = 160,
            headerLenOverride = 3, decoderFactory = { fake }
        )

        for (i in 0 until 8) {
            val steps = pipeline.onFragment(opusFragment(3, 0xAB.toByte(), 10, seq = i))
            if (i < 7) {
                assertTrue("fragment $i must not complete an 80ms advance yet", steps.isEmpty())
            } else {
                assertEquals(1, steps.size)
            }
        }

        val audio = hooks.frontendInputs[0]
        assertEquals(WakeSpotter.WINDOW_SAMPLES, audio.size)
        assertTrue(audio.sliceArray(0 until RING_TAIL).all { it == 0.0f })
        assertTrue(audio.sliceArray(RING_TAIL until audio.size).all { it == SCALE_10 })
    }

    @Test
    fun `wrong header offset never decodes never fires`() {
        val hooks = RecordingHooks()
        val spotter = WakeSpotter(0.0, hooks.frontend, hooks.body)
        val fake = FakeOpusDecoder()
        val pipeline = WakeCodecPipeline(
            spotter = spotter, codec = NeoWakeAudioCodec.OPUS, samplesPerFrame = 160,
            headerLenOverride = 3, decoderFactory = { fake }
        )

        var anyStepsProduced = false
        for (i in 0 until 400) {
            val steps = pipeline.onFragment(opusFragment(4, 0xAB.toByte(), 10, seq = i))
            if (steps.isNotEmpty()) anyStepsProduced = true
        }

        assertFalse("a wrong header offset must never synthesize a spotter step", anyStepsProduced)
        assertEquals(400, pipeline.decodeFailed)
    }

    @Test
    fun `header probe picks 4-byte offset then decodes steady state`() {
        val hooks = RecordingHooks()
        val spotter = WakeSpotter(0.9, hooks.frontend, hooks.body)
        val fake = FakeOpusDecoder()
        val pipeline = WakeCodecPipeline(
            spotter = spotter, codec = NeoWakeAudioCodec.OPUS, samplesPerFrame = 160,
            headerLenOverride = 0, probeFramesNeeded = 5, decoderFactory = { fake }
        )

        for (i in 0 until 5) {
            val steps = pipeline.onFragment(opusFragment(4, 0xAB.toByte(), 3, seq = i))
            assertTrue(steps.isEmpty())
        }

        assertEquals(4, pipeline.resolvedHeaderLen)

        val steps = pipeline.onFragment(opusFragment(4, 0xAB.toByte(), 3, seq = 5))
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `dropped frame on decode failure zeroes the ring and does not fire`() {
        val hooks = RecordingHooks()
        val spotter = WakeSpotter(0.5, hooks.frontend, hooks.body)
        repeat(40) { spotter.process(ShortArray(WakeSpotter.ADVANCE_SAMPLES) { 16384 }) }
        assertTrue(hooks.frontendInputs.last().all { it == 0.5f })

        val fake = FakeOpusDecoder()
        val pipeline = WakeCodecPipeline(
            spotter = spotter, codec = NeoWakeAudioCodec.OPUS, samplesPerFrame = 160,
            headerLenOverride = 3, decoderFactory = { fake }
        )
        val steps = pipeline.onFragment(opusFragment(3, 0xFF.toByte(), 1))

        assertTrue(steps.isEmpty())
        assertEquals(1, pipeline.decodeFailed)
        // The next real advance sees only itself: everything older was zeroed.
        spotter.process(ShortArray(WakeSpotter.ADVANCE_SAMPLES) { 16384 })
        val audio = hooks.frontendInputs.last()
        assertTrue(audio.sliceArray(0 until RING_TAIL).all { it == 0.0f })
        assertTrue(audio.sliceArray(RING_TAIL until audio.size).all { it == 0.5f })
    }

    @Test
    fun `two consecutive over-threshold hops fire once and a third does not through the pipeline`() {
        val hooks = RecordingHooks()
        hooks.probs = HIGH
        val spotter = WakeSpotter(0.45, hooks.frontend, hooks.body)
        val fake = FakeOpusDecoder()
        val pipeline = WakeCodecPipeline(
            spotter = spotter, codec = NeoWakeAudioCodec.OPUS, samplesPerFrame = 160,
            headerLenOverride = 3, decoderFactory = { fake }
        )

        val fired = mutableListOf<Int>()
        for (i in 0 until 24) { // 3 advances
            val steps = pipeline.onFragment(opusFragment(3, 0xAB.toByte(), 10, seq = i))
            for (s in steps) if (s.fired) fired.add(s.stepIndex)
        }

        assertEquals("hysteresis, not a clock: one fire on the second hop, none on the third", listOf(1), fired)
    }

    @Test
    fun `pcm8 path decodes without opus`() {
        val hooks = RecordingHooks()
        val spotter = WakeSpotter(0.9, hooks.frontend, hooks.body)
        val fake = FakeOpusDecoder()
        val pipeline = WakeCodecPipeline(
            spotter = spotter, codec = NeoWakeAudioCodec.PCM8, samplesPerFrame = 160,
            headerLenOverride = 3, decoderFactory = { fake }
        )

        var lastSteps: List<WakeSpotterStep> = emptyList()
        for (i in 0 until 8) {
            val bytes = mutableListOf<Byte>((i and 0xFF).toByte(), 0, 0)
            repeat(160) {
                bytes.add(0x2A) // low byte
                bytes.add(0x00) // high byte -> value 42
            }
            lastSteps = pipeline.onFragment(bytes.toByteArray())
        }

        assertEquals(1, lastSteps.size)
        assertEquals(0, fake.decodeCalls)
        val audio = hooks.frontendInputs[0]
        assertTrue(audio.sliceArray(RING_TAIL until audio.size).all { it == SCALE_42 })
    }

    @Test
    fun `neutral input never fires`() {
        val hooks = RecordingHooks()
        val spotter = WakeSpotter(0.99, hooks.frontend, hooks.body)
        val fake = FakeOpusDecoder()
        val pipeline = WakeCodecPipeline(
            spotter = spotter, codec = NeoWakeAudioCodec.OPUS, samplesPerFrame = 160,
            headerLenOverride = 3, decoderFactory = { fake }
        )

        var anyFired = false
        for (i in 0 until 400) {
            val value = Random.nextInt(0, 256)
            val steps = pipeline.onFragment(opusFragment(3, 0xAB.toByte(), value, seq = i))
            if (steps.any { it.fired }) anyFired = true
        }
        assertFalse(anyFired)
    }
}
