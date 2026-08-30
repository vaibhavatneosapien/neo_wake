import XCTest
@testable import neo_wake_ios

// NOTE: no native dependency (pure Swift/Foundation) — actually run and
// passed via a standalone SwiftPM harness mirroring these production
// sources; not wired to a runnable Xcode target here (no example app yet,
// same as U2's NeoWakeSessionsTests).
//
// Ported 1:1 from the `PcmFramer` and `HeaderProbe`/`rmsOf` groups in
// `test/core/neo_agent/wake_word_service_test.dart`.
private let kSamplesPerFrame = 160
private let kEngineFrame = WakeSpotter.advanceSamples

private func ramp(_ n: Int, start: Int = 0) -> [Int16] {
    (0..<n).map { Int16((start + $0) % 32767) }
}

final class WakePcmFramerTests: XCTestCase {
    func testEmits1280SampleFrames_carryNeverGrowsAcross1000Fragments() {
        let framer = WakePcmFramer(frameLength: kEngineFrame)
        var emitted: [[Int16]] = []
        let fragments = 1000

        for i in 0..<fragments {
            framer.add(ramp(kSamplesPerFrame, start: i * kSamplesPerFrame)) { emitted.append($0) }
            XCTAssertLessThan(framer.pending, kEngineFrame)
        }
        XCTAssertEqual(emitted.count, 125)
        XCTAssertEqual(framer.pending, 0)

        for i in 0..<emitted.count {
            XCTAssertEqual(emitted[i], ramp(kEngineFrame, start: i * kEngineFrame), "frame \(i) mismatch")
        }
    }

    func testEightFragments_produceExactlyOneFrame() {
        let framer = WakePcmFramer(frameLength: kEngineFrame)
        var emitted: [[Int16]] = []
        for i in 0..<8 {
            framer.add(ramp(kSamplesPerFrame, start: i * kSamplesPerFrame)) { emitted.append($0) }
        }
        XCTAssertEqual(emitted.count, 1)
        XCTAssertEqual(framer.pending, 0)
    }

    func testHandsOutACopy_notTheLiveCarryBuffer() {
        let framer = WakePcmFramer(frameLength: 4)
        var emitted: [[Int16]] = []
        framer.add([1, 2, 3, 4]) { emitted.append($0) }
        let first = emitted[0]
        XCTAssertEqual(first, [1, 2, 3, 4])

        framer.add([9, 9, 9, 9]) { emitted.append($0) }
        XCTAssertEqual(first, [1, 2, 3, 4], "the first frame must survive the second frame being built")
    }

    func testReset_dropsThePendingRemainder() {
        let framer = WakePcmFramer(frameLength: kEngineFrame)
        framer.add(ramp(kSamplesPerFrame)) { _ in }
        XCTAssertEqual(framer.pending, kSamplesPerFrame)
        framer.reset()
        XCTAssertEqual(framer.pending, 0)
    }
}

// Ported 1:1 from the `HeaderProbe` and `rmsOf` groups in the same file.
final class WakeHeaderProbeTests: XCTestCase {
    func testPicksTheOffsetThatDecodedMoreOften() {
        let probe = WakeHeaderProbe(framesNeeded: 3)
        for _ in 0..<3 {
            probe.record(headerLen: 3, decoded: false, rms: 0)
            probe.record(headerLen: 4, decoded: true, rms: 0.4)
        }
        XCTAssertTrue(probe.done)
        XCTAssertEqual(probe.verdict(fallback: 3), 4)
    }

    func testBreaksATieOnAccumulatedEnergy() {
        let probe = WakeHeaderProbe(framesNeeded: 2)
        for _ in 0..<2 {
            probe.record(headerLen: 3, decoded: true, rms: 0.1)
            probe.record(headerLen: 4, decoded: true, rms: 0.9)
        }
        XCTAssertEqual(probe.verdict(fallback: 3), 4, "equal decode counts, more energy at 4")
    }

    func testFallsBackWhenNeitherOffsetEverDecoded() {
        let probe = WakeHeaderProbe(framesNeeded: 2)
        for _ in 0..<2 {
            probe.record(headerLen: 3, decoded: false, rms: 0)
            probe.record(headerLen: 4, decoded: false, rms: 0)
        }
        XCTAssertEqual(probe.verdict(fallback: 3), 3)
        XCTAssertEqual(probe.verdict(fallback: 4), 4, "the caller chooses the fallback")
    }

    func testIsNotDoneBeforeItHasSeenEnoughFrames() {
        let probe = WakeHeaderProbe(framesNeeded: 10)
        probe.record(headerLen: 4, decoded: true, rms: 0.5)
        XCTAssertFalse(probe.done)
        XCTAssertEqual(probe.framesSeen, 1)
    }

    func testRmsOf_silenceIsZero() {
        XCTAssertEqual(rmsOf([Int16](repeating: 0, count: 320)), 0.0)
    }

    func testRmsOf_fullScaleIsOne() {
        let full = [Int16](repeating: 32767, count: 320)
        XCTAssertEqual(rmsOf(full), 1.0, accuracy: 1e-6)
    }

    func testRmsOf_emptyBlockIsZero() {
        XCTAssertEqual(rmsOf([]), 0.0)
    }
}
