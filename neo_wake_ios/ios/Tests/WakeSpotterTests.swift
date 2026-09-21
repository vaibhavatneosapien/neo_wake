import XCTest
@testable import neo_wake_ios

// NOTE (matches NeoWakeSessionsTests' note): `neo_wake_ios` has no
// `example/` app, so this file is not wired to a runnable Xcode test target
// in this checkout. It needs no native library (WakeSpotter's frontend/body
// are injected hooks) so it runs as a standalone SwiftPM package mirroring
// these exact production sources. 1:1 mirror of WakeSpotterTest.kt.
private final class FakeChain {
    var probsForCall: (Int) -> [Float] = { _ in kBackground }
    private(set) var frontendInputs: [[Float]] = []
    private(set) var bodyCalls = 0

    func frontend(_ audio: [Float]) throws -> [Float] {
        frontendInputs.append(audio)
        return [Float](repeating: 0, count: WakeSpotter.logmelFloats)
    }

    func body(_ logmel: [Float]) throws -> [Float] {
        XCTAssertEqual(logmel.count, WakeSpotter.logmelFloats)
        let p = probsForCall(bodyCalls)
        bodyCalls += 1
        return p
    }

    func script(_ probs: [Float]...) {
        probsForCall = { i in probs[min(i, probs.count - 1)] }
    }

    func clearInputs() { frontendInputs.removeAll() }
}

private let kBackground: [Float] = [0.96, 0.02, 0.02]
private let kHigh: [Float] = [0.1, 0.5, 0.4] // sum 0.9
private let kTail = WakeSpotter.windowSamples - WakeSpotter.advanceSamples

private func makeSpotter(_ chain: FakeChain, threshold: Double = 0.45) -> WakeSpotter {
    WakeSpotter(threshold: threshold, frontend: chain.frontend, body: chain.body)
}

private func frameOf(_ value: Int16) -> [Int16] {
    [Int16](repeating: value, count: WakeSpotter.advanceSamples)
}

final class WakeSpotterTests: XCTestCase {
    func testFrameOf16384_landsInRingTailAsHalf_behind30720Zeros() throws {
        let chain = FakeChain()
        let spotter = makeSpotter(chain)

        try spotter.process(frameOf(16384))

        let audio = chain.frontendInputs[0]
        XCTAssertEqual(audio.count, WakeSpotter.windowSamples)
        XCTAssertTrue(audio[0..<kTail].allSatisfy { $0 == 0 })
        XCTAssertTrue(audio[kTail...].allSatisfy { $0 == 0.5 })
    }

    func testRingShiftsByExactlyOneAdvancePerStep() throws {
        let chain = FakeChain()
        let spotter = makeSpotter(chain)

        try spotter.process(frameOf(16384))
        try spotter.process(frameOf(-16384))

        let audio = chain.frontendInputs.last!
        let prev = kTail - WakeSpotter.advanceSamples
        XCTAssertTrue(audio[0..<prev].allSatisfy { $0 == 0 })
        XCTAssertTrue(audio[prev..<kTail].allSatisfy { $0 == 0.5 })
        XCTAssertTrue(audio[kTail...].allSatisfy { $0 == -0.5 })
    }

    func testEveryHopScores_scoreIsSumOfTwoWakeClasses() throws {
        let chain = FakeChain()
        chain.script([0.15, 0.45, 0.40])
        let spotter = makeSpotter(chain)

        let step = try spotter.process(frameOf(1))

        XCTAssertEqual(step.score!, 0.85, accuracy: 1e-6)
        XCTAssertEqual(step.stepIndex, 0)
    }

    func testTwoConsecutiveHopsFireOnce_thirdDoesNot() throws {
        let chain = FakeChain()
        chain.script(kHigh)
        let spotter = makeSpotter(chain)

        XCTAssertFalse(try spotter.process(frameOf(1)).fired)
        XCTAssertEqual(spotter.consecutiveOverThreshold, 1)
        XCTAssertTrue(try spotter.process(frameOf(1)).fired)
        XCTAssertFalse(spotter.isArmed)
        XCTAssertFalse(try spotter.process(frameOf(1)).fired, "disarmed after a fire")
    }

    func testSingleHopOverThreshold_neverFires() throws {
        let chain = FakeChain()
        chain.script(kHigh, kBackground)
        let spotter = makeSpotter(chain)

        XCTAssertFalse(try spotter.process(frameOf(1)).fired)
        XCTAssertFalse(try spotter.process(frameOf(1)).fired)
        XCTAssertEqual(spotter.consecutiveOverThreshold, 0)
    }

    func testHysteresisRearmsOnlyBelowRelease() throws {
        let chain = FakeChain()
        let aboveRelease: [Float] = [0.70, 0.20, 0.10] // 0.30 > release 0.2475
        let belowRelease: [Float] = [0.80, 0.10, 0.10] // 0.20 < release
        chain.script(kHigh, kHigh, aboveRelease, kHigh, kHigh, belowRelease, kHigh, kHigh)
        let spotter = makeSpotter(chain)

        try spotter.process(frameOf(1))
        XCTAssertTrue(try spotter.process(frameOf(1)).fired) // fire #1
        try spotter.process(frameOf(1)) // 0.30: still disarmed
        XCTAssertFalse(spotter.isArmed)
        try spotter.process(frameOf(1))
        XCTAssertFalse(try spotter.process(frameOf(1)).fired, "two high hops while disarmed must not fire")
        try spotter.process(frameOf(1)) // 0.20: re-arms
        XCTAssertTrue(spotter.isArmed)
        try spotter.process(frameOf(1))
        XCTAssertTrue(try spotter.process(frameOf(1)).fired) // fire #2
    }

    func testSingleClassAtHalfAlsoFires_sumNotOneClass() throws {
        let chain = FakeChain()
        chain.script([0.5, 0.45, 0.05]) // sum 0.50 >= 0.45
        let spotter = makeSpotter(chain)

        try spotter.process(frameOf(1))
        XCTAssertTrue(try spotter.process(frameOf(1)).fired)
    }

    func testScoreAtThresholdCounts_justBelowDoesNot() throws {
        let at = FakeChain()
        at.script([0.55, 0.25, 0.20]) // 0.45
        let atSpotter = makeSpotter(at)
        try atSpotter.process(frameOf(1))
        XCTAssertTrue(try atSpotter.process(frameOf(1)).fired)

        let below = FakeChain()
        below.script([0.5501, 0.25, 0.1999]) // 0.4499
        let belowSpotter = makeSpotter(below)
        try belowSpotter.process(frameOf(1))
        XCTAssertFalse(try belowSpotter.process(frameOf(1)).fired)
    }

    func testAfterAFire_ringHolds30720ZerosAndOnlyTheNewFrame() throws {
        let chain = FakeChain()
        chain.script(kHigh, kHigh, kBackground)
        let spotter = makeSpotter(chain)

        try spotter.process(frameOf(16384))
        XCTAssertTrue(try spotter.process(frameOf(16384)).fired)
        try spotter.process(frameOf(-16384))

        let audio = chain.frontendInputs.last!
        XCTAssertTrue(audio[0..<kTail].allSatisfy { $0 == 0 }, "consumed audio must be zeroed")
        XCTAssertTrue(audio[kTail...].allSatisfy { $0 == -0.5 })
        XCTAssertEqual(spotter.consecutiveOverThreshold, 0)
    }

    func testDroppedFrame_zeroesRingAndRun_leavesArmedUnchanged() throws {
        let chain = FakeChain()
        let aboveRelease: [Float] = [0.70, 0.20, 0.10] // 0.30: below threshold, above release
        chain.script(kHigh, kHigh, aboveRelease, kHigh, kBackground)
        let spotter = makeSpotter(chain)

        try spotter.process(frameOf(16384))
        XCTAssertTrue(try spotter.process(frameOf(16384)).fired)
        try spotter.process(frameOf(16384)) // 0.30 keeps it disarmed
        XCTAssertFalse(spotter.isArmed)
        try spotter.process(frameOf(16384)) // run = 1 while disarmed
        XCTAssertEqual(spotter.consecutiveOverThreshold, 1)

        spotter.onFrameDropped()

        XCTAssertEqual(spotter.consecutiveOverThreshold, 0)
        XCTAssertFalse(spotter.isArmed, "a drop is not a fire and not a re-arm")
        try spotter.process(frameOf(-16384))
        XCTAssertTrue(chain.frontendInputs.last![0..<kTail].allSatisfy { $0 == 0 })
    }

    func testFullReset_rearms_zeroesRing_restartsStepIndex() throws {
        let chain = FakeChain()
        chain.script(kHigh)
        let spotter = makeSpotter(chain)
        try spotter.process(frameOf(16384))
        XCTAssertTrue(try spotter.process(frameOf(16384)).fired)
        XCTAssertFalse(spotter.isArmed)

        spotter.reset()

        XCTAssertTrue(spotter.isArmed)
        let first = try spotter.process(frameOf(1))
        XCTAssertEqual(first.stepIndex, 0)
        XCTAssertTrue(chain.frontendInputs.last![0..<kTail].allSatisfy { $0 == 0 })
    }

    func testWrongLengthFrameThrows() {
        let spotter = makeSpotter(FakeChain())
        XCTAssertThrowsError(try spotter.process([Int16](repeating: 0, count: WakeSpotter.advanceSamples - 1))) { error in
            XCTAssertTrue("\(error)".contains("1280"))
        }
    }

    func testBodyReturningWrongClassCountThrows() {
        let chain = FakeChain()
        chain.probsForCall = { _ in [1] }
        let spotter = makeSpotter(chain)
        XCTAssertThrowsError(try spotter.process(frameOf(1))) { error in
            XCTAssertTrue("\(error)".contains("expected 3"))
        }
    }

    func testRingMemoryFlatAcross10000Steps() throws {
        let chain = FakeChain()
        let spotter = makeSpotter(chain)
        for _ in 0..<10000 {
            try spotter.process(frameOf(1))
            XCTAssertEqual(chain.frontendInputs.last!.count, WakeSpotter.windowSamples)
            chain.clearInputs()
        }
        XCTAssertEqual(chain.bodyCalls, 10000)
    }
}
