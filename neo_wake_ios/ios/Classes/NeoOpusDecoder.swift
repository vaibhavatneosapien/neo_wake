import Foundation

/// The real `OpusDecoding` implementation, backed by the vendored libopus
/// through `opus_bridge.h`/`.c`. `WakeCodecPipeline` never imports this
/// directly except through the `decoderFactory` closure — that seam is what
/// lets `WakeCodecPipelineTests` exercise the same pipeline logic with a
/// fake decoder and no native `.a` at all (see OpusDecoding.swift).
///
/// One persistent decoder per instance — libopus decoders carry LPC/CELT
/// state across packets (loss concealment, continuity), so a fresh decoder
/// per packet would both cost an allocation every 20 ms AND throw away the
/// continuity the codec relies on.
public final class NeoOpusDecoder: OpusDecoding {
    /// Fails (returns nil) if libopus rejects the sample rate/channel count
    /// — mirrors `OpusDecoder.create` returning null in the Dart original
    /// (`opus_decoder.dart`/`opus_amplitude.dart`), never a thrown error.
    public init?(sampleRate: Int32 = 16000, channels: Int32 = 1) {
        guard let created = neo_opus_decoder_create(sampleRate, channels) else { return nil }
        handle = created
    }

    private let handle: OpaquePointer

    /// `frameSize` is an UPPER BOUND on the output buffer, not the expected
    /// decoded length — mirrors `wake_word_service.dart`'s `_kSamplesPerFrame`
    /// (320) comment exactly: "it only sizes the output buffer, and a
    /// generous bound costs nothing while a tight one truncates." A 10 ms
    /// BLE fragment actually decodes to 160 samples; libopus's own
    /// `opus_decode` return value says how many samples really came back,
    /// and this trims to that length rather than requiring it to equal
    /// `frameSize` — an equality check here would reject every real fragment.
    public func decode(_ payload: [UInt8], frameSize: Int) -> [Int16]? {
        // Guarded non-empty before either baseAddress is force-unwrapped
        // below: the bridge's C signature declares both pointers
        // `_Nonnull` (it does not support opus's NULL-means-PLC mode — see
        // opus_bridge.h), so Swift imports them as non-Optional params; an
        // empty buffer's baseAddress is nil, which this guard rules out.
        guard !payload.isEmpty, frameSize > 0 else { return nil }

        var pcm = [Int16](repeating: 0, count: frameSize)
        let decodedSamples: Int32 = payload.withUnsafeBufferPointer { dataPtr in
            pcm.withUnsafeMutableBufferPointer { pcmPtr in
                neo_opus_decode(
                    handle,
                    dataPtr.baseAddress!,
                    Int32(payload.count),
                    pcmPtr.baseAddress!,
                    Int32(frameSize)
                )
            }
        }

        guard decodedSamples > 0 else { return nil }
        return Array(pcm.prefix(Int(decodedSamples)))
    }

    deinit {
        neo_opus_decoder_destroy(handle)
    }
}
