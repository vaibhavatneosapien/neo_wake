import Foundation

/// Seam between the codec state machine (`WakeCodecPipeline`) and the real
/// libopus bridge (`NeoOpusDecoder`), so pure-logic tests can fake decode
/// outcomes without loading the native `.a`/`.so` — same reason
/// `WakeSpotter`'s mel/embed/classify are injected hooks rather than direct
/// ORT calls.
public protocol OpusDecoding {
    /// Decodes one Opus packet to AT MOST `frameSize` samples of mono
    /// PCM16 — `frameSize` is an output-buffer capacity bound, not the
    /// guaranteed/expected length (a real 10 ms BLE fragment decodes to 160
    /// samples; `NeoOpusDecoder` is generally called with a larger bound,
    /// mirroring `wake_word_service.dart`'s `_kSamplesPerFrame`). Returns
    /// nil on any failure (short/garbage packet, decoder not created, etc —
    /// the caller does not distinguish reasons, mirroring
    /// `wake_word_service.dart`'s `_decodeWith`).
    func decode(_ payload: [UInt8], frameSize: Int) -> [Int16]?
}

/// PCM8 has no Dart-side precedent to port from: `wake_word_service.dart`
/// unconditionally Opus-decodes every fragment regardless of the pendant's
/// negotiated codec (see U3 plan notes) — there is no existing contract to
/// mirror. This defines one from neo_ble's own protocol docs
/// (docs/03-ble-protocol.md §4-5): the codec payload is the same little-endian
/// PCM16 mono format the Opus path decodes TO, just never compressed, so no
/// scaling and no resampling — the bytes ARE the samples, at whatever rate
/// the pendant negotiated (16 kHz for this chain). An odd byte count cannot
/// be evenly divided into Int16 samples and is treated as a decode failure,
/// same severity as a bad Opus packet.
public func decodePcm8(_ payload: [UInt8]) -> [Int16]? {
    guard !payload.isEmpty, payload.count % 2 == 0 else { return nil }
    var samples = [Int16]()
    samples.reserveCapacity(payload.count / 2)
    var i = 0
    while i < payload.count {
        let lo = UInt16(payload[i])
        let hi = UInt16(payload[i + 1])
        let bits = lo | (hi << 8)
        samples.append(Int16(bitPattern: bits))
        i += 2
    }
    return samples
}
