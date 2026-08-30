package xyz.neosapien.neo_wake

/**
 * Seam between the codec state machine ([WakeCodecPipeline]) and the real
 * libopus JNI bridge ([NeoOpusDecoder]), so pure-logic tests can fake decode
 * outcomes without loading the native `.so` — same reason [WakeSpotter]'s
 * mel/embed/classify are injected hooks rather than direct ORT calls.
 */
interface OpusDecoding {
    /** Decodes one Opus packet to AT MOST [frameSize] samples of mono
     * PCM16 — [frameSize] is an output-buffer capacity bound, not the
     * guaranteed/expected length (a real 10 ms BLE fragment decodes to 160
     * samples; [NeoOpusDecoder] is generally called with a larger bound,
     * mirroring `wake_word_service.dart`'s `_kSamplesPerFrame`). Returns
     * null on any failure (short/garbage packet, decoder not created, etc —
     * the caller does not distinguish reasons, mirroring
     * `wake_word_service.dart`'s `_decodeWith`). */
    fun decode(payload: ByteArray, frameSize: Int): ShortArray?
}

/**
 * PCM8 has no Dart-side precedent to port from: `wake_word_service.dart`
 * unconditionally Opus-decodes every fragment regardless of the pendant's
 * negotiated codec (see U3 plan notes) — there is no existing contract to
 * mirror. This defines one from neo_ble's own protocol docs
 * (docs/03-ble-protocol.md §4-5): the codec payload is the same
 * little-endian PCM16 mono format the Opus path decodes TO, just never
 * compressed, so no scaling and no resampling — the bytes ARE the samples,
 * at whatever rate the pendant negotiated (16 kHz for this chain). An odd
 * byte count cannot be evenly divided into Int16 samples and is treated as
 * a decode failure, same severity as a bad Opus packet.
 */
fun decodePcm8(payload: ByteArray): ShortArray? {
    if (payload.isEmpty() || payload.size % 2 != 0) return null
    val samples = ShortArray(payload.size / 2)
    var i = 0
    var s = 0
    while (i < payload.size) {
        val lo = payload[i].toInt() and 0xFF
        val hi = payload[i + 1].toInt() and 0xFF
        samples[s] = ((lo or (hi shl 8)) and 0xFFFF).toShort()
        i += 2
        s++
    }
    return samples
}
