package xyz.neosapien.neo_wake

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U3 native codec tests for [NeoOpusDecoder] — DEVICE-GATED
 * (`connectedAndroidTest`), same reason [NeoWakeSessionsInstrumentedTest]
 * (U2) is instrumented rather than a plain JVM test: [System.loadLibrary]
 * needs a real device/emulator classloader, which this environment does not
 * have. Written but NOT run here.
 *
 * `kFixedTonePayload` is a real Opus packet (320 samples / 20 ms @ 16 kHz
 * mono, a 440 Hz tone), captured from a verified `opus_encode` round trip
 * against the SAME vendored source this module compiles (see
 * `neo_wake_ios/ios/Tests/OpusBridgeTests.swift`'s identical fixture and
 * `cpp/third_party/opus/VENDORING.md`) — Opus decode is bit-exact per
 * RFC 6716, so this fixed bitstream decodes identically on this module's
 * real arm64-v8a build.
 */
@RunWith(AndroidJUnit4::class)
class NeoOpusDecoderInstrumentedTest {
    private val kFixedTonePayload = byteArrayOf(
        72, -126, -75, 3, 108, -98, -103, -84, 0, 0, 4, 95, -8, 48, 4, -118, 97, -113, -31, 63,
        97, -23, 72, -103, -88, -9, -88, -105, 84, -23, -6, -109, 11, 70, 79, 9, 57, 94, -90, -22,
        -3, 93, -1, -65, -39, 104, -98, 6, 36, -82, 118, -101, 36, -120, -26, 13, -18, -58, 28, -36,
        -117, 55, -76, 16, 37, -50, 56, 10, 49, -64,
    ).map { it.toByte() }.toByteArray()

    @Test
    fun realDecode_fixedTonePayload_producesAFullFrame() {
        val decoder = NeoOpusDecoder.create(sampleRate = 16000, channels = 1)
        assertNotNull("decoder must load the native library and construct", decoder)
        val decoded = decoder!!.decode(kFixedTonePayload, frameSize = 320)
        assertNotNull(decoded)
        assertEquals(320, decoded!!.size)
        assertTrue("a real tone must have meaningfully nonzero samples", decoded.any { kotlin.math.abs(it.toInt()) > 500 })
        decoder.close()
    }

    @Test
    fun garbagePayload_isRejectedNotMisdecoded() {
        val decoder = NeoOpusDecoder.create(sampleRate = 16000, channels = 1)!!
        val garbage = ByteArray(40) { ((it * 37 + 5) % 256).toByte() }
        assertNull(decoder.decode(garbage, frameSize = 320))
        decoder.close()
    }

    @Test
    fun emptyPayload_isRejected() {
        val decoder = NeoOpusDecoder.create(sampleRate = 16000, channels = 1)!!
        assertNull(decoder.decode(ByteArray(0), frameSize = 320))
        decoder.close()
    }

    @Test
    fun pipeline_withRealDecoder_decodesOneFragment() {
        val frame = byteArrayOf(0, 0, 0) + kFixedTonePayload // 3-byte BLE header
        val mel: MelHook = { FloatArray(WakeSpotter.MEL_FRAMES_PER_STEP * WakeSpotter.MEL_BIN_COUNT) }
        val embed: EmbedHook = { _, _ -> FloatArray(WakeSpotter.EMBEDDING_DIM) }
        val classify: ClassifyHook = { _, _ -> 0.0 }
        val spotter = WakeSpotter(0.9, mel, embed, classify)
        val pipeline = WakeCodecPipeline(
            spotter = spotter, codec = NeoWakeAudioCodec.OPUS, samplesPerFrame = 320,
            headerLenOverride = 3, decoderFactory = { NeoOpusDecoder.create(16000, 1)!! }
        )

        var lastSteps: List<WakeSpotterStep> = emptyList()
        // frameSize (320) doesn't divide WakeSpotter.ADVANCE_SAMPLES (1280)
        // by 1 fragment, but 4 fragments of 320 = 1280 exactly.
        repeat(4) { lastSteps = pipeline.onFragment(frame) }

        assertEquals(1, lastSteps.size)
        assertEquals(0, pipeline.decodeFailed)
    }
}
