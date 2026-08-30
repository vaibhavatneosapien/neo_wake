import Foundation

/// The two codecs the pendant negotiates (mirrors neo_ble's `NeoAudioCodec`:
/// wire `1`=pcm8, `20`=opus, cached at connect time, defaulting to Opus if
/// the read fails — see neo_ble docs/03-ble-protocol.md §5).
public enum NeoWakeAudioCodec {
    case opus
    case pcm8
    case unknown
}

/// One BLE audio fragment, decoded down to a typed value instead of loose
/// params — carries everything a decode/probe/discontinuity decision needs.
///
/// `payloadOffset` is the header length ALREADY consumed by the caller
/// (3 bytes on v0.0.20 neo_ble; Neo2 firmware adds a 4th command-flag byte —
/// see `WakeHeaderProbe`), so `payload` below is codec bytes only, header
/// stripped.
public struct NeoWakeAudioFrame {
    public init(
        payload: [UInt8],
        payloadOffset: Int,
        codec: NeoWakeAudioCodec,
        sampleRate: Int,
        timestampMs: Int64,
        discontinuity: Bool = false
    ) {
        self.payload = payload
        self.payloadOffset = payloadOffset
        self.codec = codec
        self.sampleRate = sampleRate
        self.timestampMs = timestampMs
        self.discontinuity = discontinuity
    }

    /// Codec payload, header already stripped.
    public let payload: [UInt8]
    /// The header length that was stripped to produce `payload` (3 or 4).
    public let payloadOffset: Int
    public let codec: NeoWakeAudioCodec
    public let sampleRate: Int
    public let timestampMs: Int64
    /// True when this frame is known NOT to be adjacent to the previous one
    /// (a queue eviction upstream, a decode failure just before it, or a
    /// resumed stream) — informational for callers that log or gate on it;
    /// `WakeCodecPipeline` itself decides ring resets from decode/framing
    /// outcomes, not from this flag.
    public let discontinuity: Bool
}
