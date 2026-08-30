import XCTest
@testable import neo_wake_ios

// NOTE: DEVICE-GATED — this is the one test file in this task that genuinely
// needs the compiled native bridge (opus_bridge.c + vendored libopus), so it
// cannot run via a bare `swift test` the way the other Tests/*.swift files
// here did (see their own notes). It was, however, verified for real: this
// EXACT opus_bridge.h/.c and the EXACT vendored source under
// Classes/ThirdParty/opus/ were compiled and linked into a standalone
// SwiftPM package (a C target wrapping the vendored source + bridge, a Swift
// target depending on it) during this task, and a real encode->decode round
// trip plus a garbage-payload rejection check both passed there — see
// Classes/ThirdParty/opus/VENDORING.md's "Verification performed" section.
// `kFixedTonePayload` below is a real Opus packet captured from that
// harness's `opus_encode` (a 440 Hz test tone, 20 ms @ 16 kHz mono) — Opus
// decode is bit-exact per RFC 6716, so a fixed valid bitstream decodes
// identically on any conformant decoder, including the real iOS ARM64
// build this file cannot compile in this environment (no example app —
// same gap NeoWakeSessionsTests.swift already documents).
final class OpusBridgeTests: XCTestCase {
    /// Real Opus packet: 320-sample (20 ms @ 16 kHz mono) 440 Hz tone,
    /// encoded with `opus_encoder_create(16000, 1, OPUS_APPLICATION_VOIP)`
    /// against the exact vendored source in Classes/ThirdParty/opus/.
    private static let kFixedTonePayload: [UInt8] = [
        72, 130, 181, 3, 108, 158, 153, 172, 0, 0, 4, 95, 248, 48, 4, 138, 97, 143, 225, 63,
        97, 233, 72, 153, 168, 247, 168, 151, 84, 233, 250, 147, 11, 70, 79, 9, 57, 94, 166, 234,
        253, 93, 255, 191, 217, 104, 158, 6, 36, 174, 118, 155, 36, 136, 230, 13, 238, 198, 28, 220,
        139, 55, 180, 16, 37, 206, 56, 10, 49, 192,
    ]

    func testRealDecode_fixedTonePayload_producesAFullFrame() throws {
        let decoder = try XCTUnwrap(NeoOpusDecoder(sampleRate: 16000, channels: 1))
        let decoded = try XCTUnwrap(decoder.decode(Self.kFixedTonePayload, frameSize: 320))
        XCTAssertEqual(decoded.count, 320)
        // A real tone, not silence: some samples must be meaningfully nonzero.
        XCTAssertTrue(decoded.contains { abs($0) > 500 })
    }

    func testGarbagePayload_isRejectedNotMisdecoded() throws {
        let decoder = try XCTUnwrap(NeoOpusDecoder(sampleRate: 16000, channels: 1))
        let garbage: [UInt8] = (0..<40).map { UInt8(($0 * 37 + 5) % 256) }
        XCTAssertNil(decoder.decode(garbage, frameSize: 320))
    }

    func testEmptyPayload_isRejected() throws {
        let decoder = try XCTUnwrap(NeoOpusDecoder(sampleRate: 16000, channels: 1))
        XCTAssertNil(decoder.decode([], frameSize: 320))
    }

    /// End-to-end through `WakeCodecPipeline` with the REAL decoder factory
    /// (not the fake used by `WakeCodecPipelineTests`) — the fixed payload
    /// fragment, header-stripped and fed through the whole chain.
    func testPipeline_withRealDecoder_decodesOneFragment() throws {
        var frame: [UInt8] = [0, 0, 0] // 3-byte BLE header
        frame.append(contentsOf: Self.kFixedTonePayload)

        let mel: MelHook = { _ in [Float](repeating: 0, count: WakeSpotter.melFramesPerStep * WakeSpotter.melBinCount) }
        let embed: EmbedHook = { _, _ in [Float](repeating: 0, count: WakeSpotter.embeddingDim) }
        let classify: ClassifyHook = { _, _ in 0.0 }
        let spotter = WakeSpotter(threshold: 0.9, mel: mel, embed: embed, classify: classify)
        let pipeline = WakeCodecPipeline(
            spotter: spotter, codec: .opus, samplesPerFrame: 320,
            headerLenOverride: 3, decoderFactory: { NeoOpusDecoder(sampleRate: 16000, channels: 1)! }
        )

        // frameSize (320) doesn't divide WakeSpotter.advanceSamples (1280)
        // by 1 fragment, but 4 fragments of 320 = 1280 exactly.
        var lastSteps: [WakeSpotterStep] = []
        for _ in 0..<4 {
            lastSteps = try pipeline.onFragment(frame)
        }
        XCTAssertEqual(lastSteps.count, 1)
        XCTAssertEqual(pipeline.decodeFailed, 0)
    }
}
