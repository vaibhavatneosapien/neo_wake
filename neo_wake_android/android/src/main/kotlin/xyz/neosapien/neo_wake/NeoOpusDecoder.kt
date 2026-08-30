package xyz.neosapien.neo_wake

/**
 * The real [OpusDecoding] implementation, backed by the vendored libopus
 * through `cpp/opus_bridge.h`/`.c` + `cpp/neo_opus_jni.c` (the SAME bridge
 * file `neo_wake_ios` compiles — see `cpp/third_party/opus/VENDORING.md`).
 * [WakeCodecPipeline] never references this class directly except through
 * the `decoderFactory` lambda — that seam is what lets
 * [WakeCodecPipelineTest] exercise the same pipeline logic with a fake
 * decoder and no native `.so` at all (see OpusDecoding.kt).
 *
 * One persistent decoder per instance — libopus decoders carry LPC/CELT
 * state across packets (loss concealment, continuity), so a fresh decoder
 * per packet would both cost an allocation every 20 ms AND throw away the
 * continuity the codec relies on.
 *
 * DEVICE-GATED: [System.loadLibrary] and every `external fun` below need the
 * real `libneo_wake_native.so`, which only a device/emulator classloader can
 * load (same constraint [NeoWakeSessions] already documents for ORT's own
 * native `.so`) — see [NeoOpusDecoderInstrumentedTest].
 */
class NeoOpusDecoder private constructor(private var handle: Long) : OpusDecoding, AutoCloseable {
    companion object {
        init {
            System.loadLibrary("neo_wake_native")
        }

        /** Returns null if libopus rejects the sample rate/channel count —
         * mirrors `OpusDecoder.create` returning null in the Dart original
         * (`opus_decoder.dart`/`opus_amplitude.dart`), never a thrown
         * exception. */
        fun create(sampleRate: Int = 16000, channels: Int = 1): NeoOpusDecoder? {
            val handle = nativeCreate(sampleRate, channels)
            return if (handle == 0L) null else NeoOpusDecoder(handle)
        }

        @JvmStatic
        private external fun nativeCreate(sampleRate: Int, channels: Int): Long

        @JvmStatic
        private external fun nativeDecode(
            handle: Long,
            payload: ByteArray,
            payloadLen: Int,
            pcmOut: ShortArray,
            frameSize: Int,
        ): Int

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }

    /**
     * `frameSize` is an UPPER BOUND on the output buffer, not the expected
     * decoded length — mirrors `wake_word_service.dart`'s
     * `_kSamplesPerFrame` (320) comment exactly: "it only sizes the output
     * buffer, and a generous bound costs nothing while a tight one
     * truncates." A 10 ms BLE fragment actually decodes to 160 samples;
     * libopus's own `opus_decode` return value says how many samples really
     * came back, and this trims to that length rather than requiring it to
     * equal `frameSize`.
     */
    override fun decode(payload: ByteArray, frameSize: Int): ShortArray? {
        if (payload.isEmpty() || frameSize <= 0 || handle == 0L) return null
        val pcm = ShortArray(frameSize)
        val decodedSamples = nativeDecode(handle, payload, payload.size, pcm, frameSize)
        if (decodedSamples <= 0) return null
        return pcm.copyOf(decodedSamples)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    protected fun finalize() {
        close()
    }
}
