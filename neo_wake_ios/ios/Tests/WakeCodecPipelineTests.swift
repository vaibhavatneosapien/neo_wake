import XCTest
@testable import neo_wake_ios

// NOTE: `WakeCodecPipeline` takes an injected `OpusDecoding` (see
// OpusDecoding.swift), so these run against a fake decoder, no libopus/.a
// needed — actually run and passed via a standalone SwiftPM harness
// mirroring these production sources; not wired to a runnable Xcode target
// here (no example app yet, same as U2's NeoWakeSessionsTests). The real
// libopus decode path (NeoOpusDecoder + opus_bridge.c) is device-gated —
// see OpusBridgeLinkTests.swift.

/// A fake Opus decoder: "decodes" only when the payload starts with
/// `marker` and holds enough bytes, so tests can force decode failure at
/// the wrong header offset without a real libopus — the wrong offset reads
/// the marker byte from the wrong position (or from mid-payload garbage) and
/// naturally fails, exactly mirroring how a real decoder rejects a
/// misaligned Opus bitstream. On success, samples are `payload[1]` repeated
/// `frameSize` times — deterministic and distinguishable per test fixture.
private final class FakeOpusDecoder: OpusDecoding {
    let marker: UInt8
    private(set) var decodeCalls = 0

    init(marker: UInt8 = 0xAB) {
        self.marker = marker
    }

    func decode(_ payload: [UInt8], frameSize: Int) -> [Int16]? {
        decodeCalls += 1
        guard payload.count >= 2, payload[0] == marker else { return nil }
        let value = Int16(payload[1])
        return [Int16](repeating: value, count: frameSize)
    }
}

/// Builds one raw BLE fragment: `[seq lo][seq hi][reserved][cmd?][marker][value]`.
/// `headerLen` is 3 (v0.0.20 neo_ble) or 4 (Neo2, extra command-flag byte).
private func opusFragment(headerLen: Int, marker: UInt8, value: UInt8, seq: Int = 0) -> [UInt8] {
    var frame: [UInt8] = [UInt8(seq & 0xFF), UInt8((seq >> 8) & 0xFF), 0]
    if headerLen == 4 { frame.append(0) }
    frame.append(marker)
    frame.append(value)
    return frame
}

private func recordingHooks() -> (mel: MelHook, embed: EmbedHook, classify: ClassifyHook, melInputs: () -> [[Float]]) {
    final class Box { var melInputs: [[Float]] = [] }
    let box = Box()
    let mel: MelHook = { audio in
        box.melInputs.append(audio)
        return [Float](repeating: 0, count: WakeSpotter.melFramesPerStep * WakeSpotter.melBinCount)
    }
    let embed: EmbedHook = { _, _ in [Float](repeating: 1, count: WakeSpotter.embeddingDim) }
    let classify: ClassifyHook = { _, _ in 0.0 }
    return (mel, embed, classify, { box.melInputs })
}

final class WakeCodecPipelineTests: XCTestCase {
    func testCorrectOffset_decodesAndCarriesOverlapThroughThePipeline() throws {
        let (mel, embed, classify, melInputs) = recordingHooks()
        let spotter = WakeSpotter(threshold: 0.9, mel: mel, embed: embed, classify: classify)
        let fake = FakeOpusDecoder()
        let pipeline = WakeCodecPipeline(
            spotter: spotter, codec: .opus, samplesPerFrame: 160,
            headerLenOverride: 3, decoderFactory: { fake }
        )

        // 8 fragments of 160 decoded samples each = one 1280-sample advance
        // (KTD8). Each fragment decodes to a constant `value` so the
        // resulting frame is a clean, assertable step function.
        for i in 0..<8 {
            let steps = try pipeline.onFragment(opusFragment(headerLen: 3, marker: 0xAB, value: 10, seq: i))
            if i < 7 {
                XCTAssertTrue(steps.isEmpty, "fragment \(i) must not complete an 80ms advance yet")
            } else {
                XCTAssertEqual(steps.count, 1)
            }
        }

        // First WakeSpotter step: overlap is zero-padded (KTD4 R3), advance
        // is all-10s (160 samples * 8 fragments, each fake-decoded to value 10).
        let audio = melInputs()[0]
        XCTAssertEqual(audio.count, WakeSpotter.melInputSamples)
        XCTAssertTrue(audio[0..<WakeSpotter.overlapSamples].allSatisfy { $0 == 0.0 })
        XCTAssertTrue(audio[WakeSpotter.overlapSamples...].allSatisfy { $0 == 10.0 })
    }

    func testWrongHeaderOffset_neverDecodes_neverFires() throws {
        let (mel, embed, classify, _) = recordingHooks()
        let spotter = WakeSpotter(threshold: 0.0, mel: mel, embed: embed, classify: classify) // threshold 0 -> any score fires
        let fake = FakeOpusDecoder()
        // Frames are framed for a 4-byte header, but the pipeline is told 3.
        let pipeline = WakeCodecPipeline(
            spotter: spotter, codec: .opus, samplesPerFrame: 160,
            headerLenOverride: 3, decoderFactory: { fake }
        )

        var anyStepsProduced = false
        for i in 0..<400 {
            let steps = try pipeline.onFragment(opusFragment(headerLen: 4, marker: 0xAB, value: 10, seq: i))
            if !steps.isEmpty { anyStepsProduced = true }
        }

        // The marker lands one byte later than the wrong-offset payload
        // expects, so every decode fails -- exactly the silent-failure this
        // guards against (docs/solutions/.../onnx-wake-chain-silent-frontend-bugs-score-confidently.md):
        // wrong offset in, noise or nothing out, never a spurious fire.
        XCTAssertFalse(anyStepsProduced, "a wrong header offset must never synthesize a spotter step")
        XCTAssertEqual(pipeline.decodeFailed, 400)
    }

    func testHeaderProbe_pick4ByteOffset_thenDecodesSteadyState() throws {
        let (mel, embed, classify, _) = recordingHooks()
        let spotter = WakeSpotter(threshold: 0.9, mel: mel, embed: embed, classify: classify)
        let fake = FakeOpusDecoder()
        // headerLenOverride 0 => probe at runtime, same as
        // `_kHeaderLenOverride == 0` in the Dart service.
        let pipeline = WakeCodecPipeline(
            spotter: spotter, codec: .opus, samplesPerFrame: 160,
            headerLenOverride: 0, probeFramesNeeded: 5, decoderFactory: { fake }
        )

        for i in 0..<5 {
            let steps = try pipeline.onFragment(opusFragment(headerLen: 4, marker: 0xAB, value: 3, seq: i))
            XCTAssertTrue(steps.isEmpty, "still probing")
        }

        XCTAssertEqual(pipeline.resolvedHeaderLen, 4)

        // Post-probe fragments decode normally at the resolved offset.
        let steps = try pipeline.onFragment(opusFragment(headerLen: 4, marker: 0xAB, value: 3, seq: 5))
        XCTAssertTrue(steps.isEmpty) // just one more fragment, not yet a full 1280-sample advance
    }

    func testDroppedFrame_onDecodeFailure_resetsEmbeddingRingOnly() throws {
        // Mirrors wake_word_service_test.dart's EngineFrameQueue overflow
        // scenario, but the discontinuity source here is a decode failure
        // rather than a queue eviction -- same `onFrameDropped()` contract.
        let mel: MelHook = { _ in [Float](repeating: 0, count: WakeSpotter.melFramesPerStep * WakeSpotter.melBinCount) }
        let embed: EmbedHook = { _, _ in [Float](repeating: 0, count: WakeSpotter.embeddingDim) }
        let classify: ClassifyHook = { _, _ in 0.0 }
        let spotter = WakeSpotter(threshold: 0.5, mel: mel, embed: embed, classify: classify)
        for _ in 0..<40 {
            try spotter.process([Int16](repeating: 0, count: WakeSpotter.advanceSamples))
        }
        XCTAssertEqual(spotter.embeddingRingLength, WakeSpotter.embeddingRingDepth, "ring must be genuinely full first")
        let melBufferBefore = spotter.melBufferLength

        let fake = FakeOpusDecoder()
        let pipeline = WakeCodecPipeline(
            spotter: spotter, codec: .opus, samplesPerFrame: 160,
            headerLenOverride: 3, decoderFactory: { fake }
        )
        // Wrong marker -> guaranteed decode failure -> breakStream().
        _ = try pipeline.onFragment(opusFragment(headerLen: 3, marker: 0xFF, value: 1))

        XCTAssertEqual(spotter.embeddingRingLength, 0, "a drop clears the ring, not the mel buffer (R6/R18)")
        XCTAssertEqual(spotter.melBufferLength, melBufferBefore, "mel/raw overlap survive a drop untouched")
        XCTAssertEqual(pipeline.decodeFailed, 1)
    }

    func testPcm8Path_decodesWithoutOpus() throws {
        let (mel, embed, classify, melInputs) = recordingHooks()
        let spotter = WakeSpotter(threshold: 0.9, mel: mel, embed: embed, classify: classify)
        let fake = FakeOpusDecoder() // must never be called on the pcm8 path
        let pipeline = WakeCodecPipeline(
            spotter: spotter, codec: .pcm8, samplesPerFrame: 160,
            headerLenOverride: 3, decoderFactory: { fake }
        )

        // pcm8 payload = raw little-endian Int16 samples, no codec framing.
        // 160 samples/fragment * 8 fragments = one 1280-sample advance.
        for i in 0..<8 {
            var frame: [UInt8] = [UInt8(i & 0xFF), 0, 0] // 3-byte BLE header
            for _ in 0..<160 {
                frame.append(0x2A) // low byte
                frame.append(0x00) // high byte -> value 0x002A = 42
            }
            let steps = try pipeline.onFragment(frame)
            if i == 7 { XCTAssertEqual(steps.count, 1) }
        }

        XCTAssertEqual(fake.decodeCalls, 0, "pcm8 must never reach the Opus decoder")
        let audio = melInputs()[0]
        XCTAssertTrue(audio[WakeSpotter.overlapSamples...].allSatisfy { $0 == 42.0 })
    }

    func testNeutralInput_neverFiresBeforeRingIsGenuinelyFull() throws {
        // A structural guard, not a numeric one: with neutral fake hooks
        // (classify pinned below any real threshold) unrelated/incomplete
        // input never synthesizes a fire out of pipeline wiring alone.
        // Proving the REAL graph resists off-distribution input (the
        // documented confident-positive failure mode) needs the real ONNX
        // weights -- that is the U4 golden-corpus gate, device/emulator-only.
        let (mel, embed, classify, _) = recordingHooks()
        let spotter = WakeSpotter(threshold: 0.99, mel: mel, embed: embed, classify: classify)
        let fake = FakeOpusDecoder()
        let pipeline = WakeCodecPipeline(
            spotter: spotter, codec: .opus, samplesPerFrame: 160,
            headerLenOverride: 3, decoderFactory: { fake }
        )

        var rng = SystemRandomNumberGenerator()
        var anyFired = false
        for i in 0..<400 {
            let value = UInt8.random(in: 0...255, using: &rng)
            let steps = try pipeline.onFragment(opusFragment(headerLen: 3, marker: 0xAB, value: value, seq: i))
            if steps.contains(where: { $0.fired }) { anyFired = true }
        }
        XCTAssertFalse(anyFired)
    }
}
