import XCTest

@testable import neo_wake_ios

#if canImport(onnxruntime_objc)
  import onnxruntime_objc
#endif

/// U2 native session-layer tests for `NeoWakeSessions`.
///
/// NOTE: `neo_wake_ios` has no `example/` app yet, so this file is not wired
/// to a runnable Xcode test target — there is nowhere to host an XCTest
/// bundle in this plugin checkout. It documents the U2 contract and is meant
/// to be dropped into a future example app's `RunnerTests` (or an SPM test
/// target) once one exists. ORT ships no iOS-simulator slice, so even once
/// wired these are DEVICE-ONLY tests — see the U2 task's verification notes.
final class NeoWakeSessionsTests: XCTestCase {
    func testEnsureInitialized_isIdempotent() throws {
        try NeoWakeSessions.shared.ensureInitialized()
        let melAfterFirst = NeoWakeSessions.shared.session(for: .melspectrogram)
        try NeoWakeSessions.shared.ensureInitialized()
        let melAfterSecond = NeoWakeSessions.shared.session(for: .melspectrogram)

        XCTAssertNotNil(melAfterFirst)
        XCTAssertTrue(melAfterFirst === melAfterSecond,
                       "second ensureInitialized() must not recreate the session")
    }

    func testMelspectrogramSession_dummyInput_returnsExpectedOutputRank() throws {
        try NeoWakeSessions.shared.ensureInitialized()
        guard let session = NeoWakeSessions.shared.session(for: .melspectrogram) else {
            return XCTFail("melspectrogram session not created")
        }
        // Model input: [batch_size, samples] float32. 1280 samples = one
        // 80ms/16kHz advance (plan R1).
        let samples = [Float](repeating: 0, count: 1280)
        let inputData = NSMutableData(bytes: samples, length: samples.count * MemoryLayout<Float>.stride)
        let input = try ORTValue(tensorData: inputData, elementType: .float, shape: [1, 1280])

        let outputs = try session.run(withInputs: ["input": input],
                                       outputNames: Set(["output"]),
                                       runOptions: nil)
        let output = try XCTUnwrap(outputs["output"])
        let shape = try output.tensorTypeAndShapeInfo().shape.map { Int(truncating: $0) }

        // [time, 1, ?, 32] — last dim is the fixed mel-bin count.
        XCTAssertEqual(shape.count, 4)
        XCTAssertEqual(shape.last, 32)
    }

    func testEmbeddingSession_dummyInput_returnsExpectedOutputShape() throws {
        try NeoWakeSessions.shared.ensureInitialized()
        guard let session = NeoWakeSessions.shared.session(for: .embedding) else {
            return XCTFail("embedding session not created")
        }
        // Model input: [batch, 76, 32, 1] float32 mel frames.
        let count = 1 * 76 * 32 * 1
        let samples = [Float](repeating: 0, count: count)
        let inputData = NSMutableData(bytes: samples, length: samples.count * MemoryLayout<Float>.stride)
        let input = try ORTValue(tensorData: inputData, elementType: .float, shape: [1, 76, 32, 1])

        let outputs = try session.run(withInputs: ["input_1": input],
                                       outputNames: Set(["conv2d_19"]),
                                       runOptions: nil)
        let output = try XCTUnwrap(outputs["conv2d_19"])
        let shape = try output.tensorTypeAndShapeInfo().shape.map { Int(truncating: $0) }

        XCTAssertEqual(shape, [1, 1, 1, 96])
    }

    func testClassifierSession_dummyInput_returnsExpectedOutputShape() throws {
        try NeoWakeSessions.shared.ensureInitialized()
        guard let session = NeoWakeSessions.shared.session(for: .classifier) else {
            return XCTFail("classifier session not created")
        }
        // Model input: [batch, 16, 96] float32 stacked embeddings.
        let count = 1 * 16 * 96
        let samples = [Float](repeating: 0, count: count)
        let inputData = NSMutableData(bytes: samples, length: samples.count * MemoryLayout<Float>.stride)
        let input = try ORTValue(tensorData: inputData, elementType: .float, shape: [1, 16, 96])

        let outputs = try session.run(withInputs: ["onnx::Flatten_0": input],
                                       outputNames: Set(["output"]),
                                       runOptions: nil)
        let output = try XCTUnwrap(outputs["output"])
        let shape = try output.tensorTypeAndShapeInfo().shape.map { Int(truncating: $0) }

        XCTAssertEqual(shape, [1, 1])
    }

    func testLowPowerSessionOptions_appliesWithoutThrowing() throws {
        // onnxruntime-objc's ORTSessionOptions has no getters (unlike the
        // Android Java API's getConfigEntries()), so this can only assert
        // that the low-power config applies without error, not read it back.
        XCTAssertNoThrow(try NeoWakeSessions.makeLowPowerSessionOptions())
    }
}
