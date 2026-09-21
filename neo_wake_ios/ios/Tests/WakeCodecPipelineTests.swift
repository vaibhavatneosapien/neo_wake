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

private let kBackground: [Float] = [0.96, 0.02, 0.02]
private let kHigh: [Float] = [0.1, 0.5, 0.4] // sum 0.9
private let kRingTail = WakeSpotter.windowSamples - WakeSpotter.advanceSamples
private let kScale10: Float = 10.0 / 32768.0
private let kScale42: Float = 42.0 / 32768.0

private func recordingHooks(probs: [Float] = kBackground) -> (frontend: FrontendHook, body: BodyHook, frontendInputs: () -> [[Float]]) {
    final class Box { var inputs: [[Float]] = [] }
    let box = Box()
    let frontend: FrontendHook = { audio in
        box.inputs.append(audio)
        return [Float](repeating: 0, count: WakeSpotter.logmelFloats)
    }
    let body: BodyHook = { _ in probs }
    return (frontend, body, { box.inputs })
}

final class WakeCodecPipelineTests: XCTestCase {
    func testCorrectOffset_decodesAndCarriesOverlapThroughThePipeline() throws {
        let (frontend, body, frontendInputs) = recordingHooks()
        let spotter = WakeSpotter(threshold: 0.9, frontend: frontend, body: body)
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

        // First WakeSpotter step: the ring is zero except the newest advance,
        // all-10s scaled by 1/32768 (160 samples * 8 fragments, each
        // fake-decoded to value 10).
        let audio = frontendInputs()[0]
        XCTAssertEqual(audio.count, WakeSpotter.windowSamples)
        XCTAssertTrue(audio[0..<kRingTail].allSatisfy { $0 == 0.0 })
        XCTAssertTrue(audio[kRingTail...].allSatisfy { $0 == kScale10 })
    }

    func testWrongHeaderOffset_neverDecodes_neverFires() throws {
        let (frontend, body, _) = recordingHooks()
        let spotter = WakeSpotter(threshold: 0.0, frontend: frontend, body: body) // threshold 0 -> any score fires
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
        let (frontend, body, _) = recordingHooks()
        let spotter = WakeSpotter(threshold: 0.9, frontend: frontend, body: body)
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

    func testDroppedFrame_onDecodeFailure_zeroesTheRingAndDoesNotFire() throws {
        let (frontend, body, frontendInputs) = recordingHooks()
        let spotter = WakeSpotter(threshold: 0.5, frontend: frontend, body: body)
        for _ in 0..<40 {
            try spotter.process([Int16](repeating: 16384, count: WakeSpotter.advanceSamples))
        }
        XCTAssertTrue(frontendInputs().last!.allSatisfy { $0 == 0.5 })

        let fake = FakeOpusDecoder()
        let pipeline = WakeCodecPipeline(
            spotter: spotter, codec: .opus, samplesPerFrame: 160,
            headerLenOverride: 3, decoderFactory: { fake }
        )
        // Wrong marker -> guaranteed decode failure -> breakStream().
        let steps = try pipeline.onFragment(opusFragment(headerLen: 3, marker: 0xFF, value: 1))

        XCTAssertTrue(steps.isEmpty)
        XCTAssertEqual(pipeline.decodeFailed, 1)
        // The next real advance sees only itself: everything older was zeroed.
        try spotter.process([Int16](repeating: 16384, count: WakeSpotter.advanceSamples))
        let audio = frontendInputs().last!
        XCTAssertTrue(audio[0..<kRingTail].allSatisfy { $0 == 0.0 })
        XCTAssertTrue(audio[kRingTail...].allSatisfy { $0 == 0.5 })
    }

    func testTwoConsecutiveOverThresholdHops_fireOnce_thirdDoesNot_throughThePipeline() throws {
        let (frontend, body, _) = recordingHooks(probs: kHigh)
        let spotter = WakeSpotter(threshold: 0.45, frontend: frontend, body: body)
        let fake = FakeOpusDecoder()
        let pipeline = WakeCodecPipeline(
            spotter: spotter, codec: .opus, samplesPerFrame: 160,
            headerLenOverride: 3, decoderFactory: { fake }
        )

        var fired: [Int] = []
        for i in 0..<24 { // 3 advances
            let steps = try pipeline.onFragment(opusFragment(headerLen: 3, marker: 0xAB, value: 10, seq: i))
            fired.append(contentsOf: steps.filter { $0.fired }.map { $0.stepIndex })
        }

        XCTAssertEqual(fired, [1], "hysteresis, not a clock: one fire on the second hop, none on the third")
    }

    func testPcm8Path_decodesWithoutOpus() throws {
        let (frontend, body, frontendInputs) = recordingHooks()
        let spotter = WakeSpotter(threshold: 0.9, frontend: frontend, body: body)
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
        let audio = frontendInputs()[0]
        XCTAssertTrue(audio[kRingTail...].allSatisfy { $0 == kScale42 })
    }

    func testNeutralInput_neverFires() throws {
        // A structural guard, not a numeric one: with neutral fake hooks
        // (body pinned at background) unrelated input never synthesizes a
        // fire out of pipeline wiring alone. Proving the REAL graph resists
        // off-distribution input needs the real ONNX weights -- that is the
        // device-gated section of WakeSpotterGoldenTest.
        let (frontend, body, _) = recordingHooks()
        let spotter = WakeSpotter(threshold: 0.99, frontend: frontend, body: body)
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
