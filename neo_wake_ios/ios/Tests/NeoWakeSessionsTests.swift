import XCTest

@testable import neo_wake_ios

#if canImport(onnxruntime_objc)
  import onnxruntime_objc
#endif

/// Device-gated session + hook tests for the chorus6 graphs
/// (`NeoWakeSessions`, `NeoWakeOrtHooks`).
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
        let first = NeoWakeSessions.shared.session(for: .frontend)
        try NeoWakeSessions.shared.ensureInitialized()
        let second = NeoWakeSessions.shared.session(for: .frontend)

        XCTAssertNotNil(first)
        XCTAssertTrue(first === second, "second ensureInitialized() must not recreate the session")
        XCTAssertNotNil(NeoWakeSessions.shared.session(for: .body))
    }

    func testFrontendHook_returnsExactly8000Floats() throws {
        try NeoWakeSessions.shared.ensureInitialized()
        let logmel = try NeoWakeOrtHooks.frontendHook()([Float](repeating: 0, count: WakeSpotter.windowSamples))
        XCTAssertEqual(logmel.count, WakeSpotter.logmelFloats)
    }

    func testBodyHook_rowsSumToOne_softmaxIsInGraph() throws {
        try NeoWakeSessions.shared.ensureInitialized()
        let logmel = try NeoWakeOrtHooks.frontendHook()([Float](repeating: 0, count: WakeSpotter.windowSamples))
        let probs = try NeoWakeOrtHooks.bodyHook()(logmel)
        XCTAssertEqual(probs.count, WakeSpotter.classCount)
        XCTAssertEqual(probs.reduce(0, +), 1.0, accuracy: 1e-3)
    }

    func testReferenceInputs_reproduceTheBundlesWakeScores() throws {
        try NeoWakeSessions.shared.ensureInitialized()
        let dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("Fixtures/wakeword")
        let reference = try JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent("reference_vectors.json"))) as! [String: [String: Any]]
        let frontend = NeoWakeOrtHooks.frontendHook()
        let body = NeoWakeOrtHooks.bodyHook()
        for name in ["positive_wake_up_neo", "positive_neo_wake_up", "burst_-30dBFS", "burst_-45dBFS"] {
            let data = try Data(contentsOf: dir.appendingPathComponent("\(name).wav"))
            let pcm = data.dropFirst(44).withUnsafeBytes { Array($0.bindMemory(to: Int16.self)) }
            var audio = [Float](repeating: 0, count: WakeSpotter.windowSamples)
            for i in 0..<min(pcm.count, audio.count) { audio[i] = Float(Int16(littleEndian: pcm[i])) * WakeSpotter.int16Scale }
            let probs = try body(try frontend(audio))
            let expected = (reference[name]!["wake_score"] as! NSNumber).doubleValue
            XCTAssertEqual(Double(probs[1]) + Double(probs[2]), expected, accuracy: 0.01, name)
        }
        let silence = try body(try frontend([Float](repeating: 0, count: WakeSpotter.windowSamples)))
        XCTAssertEqual(Double(silence[1]) + Double(silence[2]), (reference["silence"]!["wake_score"] as! NSNumber).doubleValue, accuracy: 0.01)
    }

    func testFrontendHook_rejectsAWrongLengthInput() throws {
        try NeoWakeSessions.shared.ensureInitialized()
        XCTAssertThrowsError(try NeoWakeOrtHooks.frontendHook()([Float](repeating: 0, count: 1760)))
    }

    func testLowPowerSessionOptions_appliesWithoutThrowing() throws {
        // onnxruntime-objc's ORTSessionOptions has no getters (unlike the
        // Android Java API's getConfigEntries()), so this can only assert
        // that the low-power config applies without error, not read it back.
        XCTAssertNoThrow(try NeoWakeSessions.makeLowPowerSessionOptions())
    }
}
