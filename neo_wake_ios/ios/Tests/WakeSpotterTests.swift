import XCTest
@testable import neo_wake_ios

// NOTE (matches U2's NeoWakeSessionsTests note): `neo_wake_ios` has no
// `example/` app, so this file is not wired to a runnable Xcode test target
// in this checkout. UNLIKE the ORT-backed U2 tests, this one needs no
// native library at all (WakeSpotter's mel/embed/classify are injected
// hooks — see WakeSpotter.swift), so it WAS actually run and passed: as a
// standalone SwiftPM package mirroring these exact production sources
// (`swift test`, macOS host, Swift 6.4), not through this Xcode target.
// Wire this into a real XCTest bundle once an example app exists.
//
// Ported 1:1 from `test/core/neo_agent/wake_spotter_test.dart` — same
// scenarios, same warm-up arithmetic (first embed at step 10, first score at
// step 25), same fakes-record-shape-not-behaviour approach. This is the
// numeric-parity evidence for the native port: every assertion here matches
// an assertion already proven against the Dart original.
private let kFirstEmbedAtStep = 10 // ceil(76 / 8)
private let kFirstScoreAtStep = kFirstEmbedAtStep + WakeSpotter.embeddingRingDepth - 1 // 25

private final class RecordingChain {
    var melValue: Double
    var melValueForCall: ((Int) -> Double)?
    var classifyReturn: Double = 0.0

    private(set) var melCalls = 0
    private(set) var embedCalls = 0
    private(set) var classifyCalls = 0

    private(set) var melInputs: [[Float]] = []
    private(set) var embedInputs: [[Float]] = []
    private(set) var embedShapes: [[Int]] = []
    private(set) var classifyInputs: [[Float]] = []
    private(set) var classifyShapes: [[Int]] = []

    init(melValue: Double = 1.0) {
        self.melValue = melValue
    }

    func mel(_ audio: [Float]) throws -> [Float] {
        let callIndex = melCalls
        melCalls += 1
        melInputs.append(audio)
        let v = melValueForCall?(callIndex) ?? melValue
        return [Float](repeating: Float(v), count: WakeSpotter.melFramesPerStep * WakeSpotter.melBinCount)
    }

    func embed(_ melWindow: [Float], _ shape: [Int]) throws -> [Float] {
        embedCalls += 1
        embedInputs.append(melWindow)
        embedShapes.append(shape)
        return [Float](repeating: Float(embedCalls), count: WakeSpotter.embeddingDim)
    }

    func classify(_ embeddingWindow: [Float], _ shape: [Int]) throws -> Double {
        classifyCalls += 1
        classifyInputs.append(embeddingWindow)
        classifyShapes.append(shape)
        return classifyReturn
    }
}

private func makeSpotter(_ chain: RecordingChain, threshold: Double = 0.3) -> WakeSpotter {
    WakeSpotter(threshold: threshold, mel: chain.mel, embed: chain.embed, classify: chain.classify)
}

private func frameOf(_ value: Int16) -> [Int16] {
    [Int16](repeating: value, count: WakeSpotter.advanceSamples)
}

@discardableResult
private func runSteps(_ spotter: WakeSpotter, _ count: Int) throws -> WakeSpotterStep {
    var last: WakeSpotterStep!
    for _ in 0..<count {
        last = try spotter.process(frameOf(1))
    }
    return last
}

final class WakeSpotterTests: XCTestCase {
    func testSteadyStateStep_feeds1760SamplesAnd8MelFrames() throws {
        let chain = RecordingChain()
        let spotter = makeSpotter(chain)

        try spotter.process(frameOf(100))

        XCTAssertEqual(chain.melInputs.count, 1)
        XCTAssertEqual(chain.melInputs[0].count, WakeSpotter.melInputSamples)
        XCTAssertEqual(spotter.melBufferLength, WakeSpotter.melFramesPerStep)
    }

    func testFirstStep_zeroPaddedOverlap_noScore() throws {
        let chain = RecordingChain()
        let spotter = makeSpotter(chain)

        let result = try spotter.process(frameOf(50))
        let audio = chain.melInputs[0]

        XCTAssertEqual(audio.count, WakeSpotter.melInputSamples)
        XCTAssertTrue(audio[0..<WakeSpotter.overlapSamples].allSatisfy { $0 == 0.0 })
        XCTAssertTrue(audio[WakeSpotter.overlapSamples...].allSatisfy { $0 == 50.0 })
        XCTAssertNil(result.score)
    }

    func testMelBufferNeverExceedsBound_slidesByExactly8() throws {
        let chain = RecordingChain()
        chain.melValueForCall = { Double($0 + 1) }
        let spotter = makeSpotter(chain)

        for _ in 0..<14 {
            try spotter.process(frameOf(1))
            XCTAssertLessThanOrEqual(spotter.melBufferLength, WakeSpotter.melBufferFrames)
        }
        XCTAssertEqual(spotter.melBufferLength, WakeSpotter.melBufferFrames)

        let slideFloats = WakeSpotter.melFramesPerStep * WakeSpotter.melBinCount
        let totalFloats = WakeSpotter.melBufferFrames * WakeSpotter.melBinCount
        let windowA = chain.embedInputs[chain.embedInputs.count - 2]
        let windowB = chain.embedInputs[chain.embedInputs.count - 1]
        XCTAssertEqual(
            Array(windowA[slideFloats..<totalFloats]),
            Array(windowB[0..<(totalFloats - slideFloats)])
        )
    }

    func testSteadyState_oneEmbeddingPer80msStep() throws {
        let chain = RecordingChain()
        let spotter = makeSpotter(chain)

        let steps = 20
        try runSteps(spotter, steps)
        XCTAssertEqual(chain.embedCalls, steps - kFirstEmbedAtStep + 1)

        let before = chain.embedCalls
        try spotter.process(frameOf(1))
        XCTAssertEqual(chain.embedCalls, before + 1)
    }

    func testEveryMelValueReachingEmbed_isScaledOutOfDbBand() throws {
        let chain = RecordingChain(melValue: -80.0)
        let spotter = makeSpotter(chain)

        try runSteps(spotter, kFirstEmbedAtStep)

        XCTAssertFalse(chain.embedInputs.isEmpty)
        let expectedScaled: Float = -80.0 / 10 + 2 // -6.0
        XCTAssertTrue(chain.embedInputs.last!.allSatisfy { abs($0 - expectedScaled) < 1e-6 })
    }

    func testClassifyNotCalledUntil16RealEmbeddings_neverZeroFilled() throws {
        let chain = RecordingChain()
        let spotter = makeSpotter(chain)

        let warmedUp = try runSteps(spotter, kFirstScoreAtStep - 1)
        XCTAssertEqual(chain.classifyCalls, 0)
        XCTAssertNil(warmedUp.score)

        let result = try spotter.process(frameOf(1))
        XCTAssertEqual(chain.classifyCalls, 1)
        XCTAssertNotNil(result.score)
        XCTAssertEqual(chain.classifyInputs[0].count, WakeSpotter.embeddingRingDepth * WakeSpotter.embeddingDim)
        XCTAssertFalse(chain.classifyInputs[0].contains(0.0))
    }

    func testTensorShapes() throws {
        let chain = RecordingChain()
        let spotter = makeSpotter(chain)

        try runSteps(spotter, kFirstScoreAtStep)

        XCTAssertEqual(chain.embedShapes.first!, [1, WakeSpotter.melBufferFrames, WakeSpotter.melBinCount, 1])
        XCTAssertEqual(chain.classifyShapes[0], [1, WakeSpotter.embeddingRingDepth, WakeSpotter.embeddingDim])
    }

    func testScoreAtThresholdFires_justBelowDoesNot() throws {
        let threshold = 0.3

        let chainAt = RecordingChain()
        chainAt.classifyReturn = threshold
        let resultAt = try runSteps(makeSpotter(chainAt), kFirstScoreAtStep)
        XCTAssertTrue(resultAt.fired)

        let chainBelow = RecordingChain()
        chainBelow.classifyReturn = threshold - 0.0001
        let resultBelow = try runSteps(makeSpotter(chainBelow), kFirstScoreAtStep)
        XCTAssertFalse(resultBelow.fired)
    }

    func testDetection_clearsRingOnly_melAndRawIntact() throws {
        let chain = RecordingChain()
        let spotter = makeSpotter(chain)
        try runSteps(spotter, kFirstScoreAtStep)
        XCTAssertEqual(spotter.embeddingRingLength, WakeSpotter.embeddingRingDepth)

        spotter.onDetection()
        XCTAssertEqual(spotter.embeddingRingLength, 0)
        XCTAssertEqual(spotter.melBufferLength, WakeSpotter.melBufferFrames)

        let embedCallsBefore = chain.embedCalls
        let result = try spotter.process(frameOf(1))

        XCTAssertEqual(chain.embedCalls, embedCallsBefore + 1)
        XCTAssertEqual(spotter.embeddingRingLength, 1)
        XCTAssertNil(result.score)
    }

    func testDroppedFrame_clearsRingOnly_noAudioReachesMel() throws {
        let chain = RecordingChain()
        let spotter = makeSpotter(chain)
        try runSteps(spotter, kFirstScoreAtStep)
        let melCallsBefore = chain.melCalls

        spotter.onFrameDropped()

        XCTAssertEqual(spotter.embeddingRingLength, 0)
        XCTAssertEqual(chain.melCalls, melCallsBefore)
        XCTAssertEqual(spotter.melBufferLength, WakeSpotter.melBufferFrames)
    }

    func testFullReset_clearsAllThree() throws {
        let chain = RecordingChain()
        let spotter = makeSpotter(chain)
        try runSteps(spotter, kFirstScoreAtStep)
        XCTAssertEqual(spotter.embeddingRingLength, WakeSpotter.embeddingRingDepth)

        spotter.reset()
        XCTAssertEqual(spotter.melBufferLength, 0)
        XCTAssertEqual(spotter.embeddingRingLength, 0)

        let first = try spotter.process(frameOf(1))
        XCTAssertTrue(chain.melInputs.last![0..<WakeSpotter.overlapSamples].allSatisfy { $0 == 0.0 })
        XCTAssertEqual(first.stepIndex, 0)
        XCTAssertNil(first.score)

        for _ in 0..<(kFirstScoreAtStep - 2) {
            let r = try spotter.process(frameOf(1))
            XCTAssertNil(r.score)
        }
        let last = try spotter.process(frameOf(1))
        XCTAssertNotNil(last.score)
    }

    func testRingAndBufferMemoryFlatAcross10000Steps() throws {
        let chain = RecordingChain()
        let spotter = makeSpotter(chain)

        for _ in 0..<10000 {
            try spotter.process(frameOf(1))
            XCTAssertLessThanOrEqual(spotter.melBufferLength, WakeSpotter.melBufferFrames)
            XCTAssertLessThanOrEqual(spotter.embeddingRingLength, WakeSpotter.embeddingRingDepth)
        }
        XCTAssertEqual(spotter.melBufferLength, WakeSpotter.melBufferFrames)
        XCTAssertEqual(spotter.embeddingRingLength, WakeSpotter.embeddingRingDepth)
    }
}
