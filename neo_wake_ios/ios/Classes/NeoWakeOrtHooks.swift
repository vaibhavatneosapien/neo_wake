import Foundation

#if canImport(onnxruntime_objc)
import onnxruntime_objc
#endif

/// Builds the real `FrontendHook`/`BodyHook` pair `WakeSpotter` needs,
/// backed by the two ORT sessions `NeoWakeSessions` created. `WakeSpotter`
/// itself stays plugin-free (see its own file header); this is the one place
/// that actually touches ORT.
///
/// Tensor contract (chorus6.config.json):
///   frontend  "audio"  float32 [1, 32000]   -> "logmel" float32 [1, 200, 40]
///   body      "logmel" float32 [1, 200, 40] -> "probs"  float32 [1, 3], softmax
/// The frontend's output time dim is labelled dynamic in the graph, so both
/// hooks assert the element count they hand back — a shape drift must fail
/// loudly here, never score confidently downstream (see docs/solutions
/// onnx-wake-chain-silent-frontend-bugs-score-confidently).
///
/// DEVICE-GATED: `ORTSession.run` loads ORT's native runtime, so nothing
/// here can be exercised by a plain `swift test` — see
/// NeoWakeSessionsTests.swift for the device tier.
enum NeoWakeOrtHooksError: Error {
    case sessionNotLoaded(NeoWakeSessions.Graph)
    case unexpectedInputCount(expected: Int, got: Int)
    case unexpectedOutputCount(expected: Int, got: Int)
}

enum NeoWakeOrtHooks {
    static let frontendInput = "audio"
    static let frontendOutput = "logmel"
    static let bodyInput = "logmel"
    static let bodyOutput = "probs"

    /// Frontend: `WakeSpotter.windowSamples` floats in `[-1, 1]` -> 200 x 40 log-Mel.
    public static func frontendHook() -> FrontendHook {
        { audioWindow in
            guard audioWindow.count == WakeSpotter.windowSamples else {
                throw NeoWakeOrtHooksError.unexpectedInputCount(expected: WakeSpotter.windowSamples, got: audioWindow.count)
            }
            guard let session = NeoWakeSessions.shared.session(for: .frontend) else {
                throw NeoWakeOrtHooksError.sessionNotLoaded(.frontend)
            }
            let out = try run(session, input: audioWindow, shape: [1, audioWindow.count as NSNumber],
                              inputName: frontendInput, outputName: frontendOutput)
            guard out.count == WakeSpotter.logmelFloats else {
                throw NeoWakeOrtHooksError.unexpectedOutputCount(expected: WakeSpotter.logmelFloats, got: out.count)
            }
            return out
        }
    }

    /// Body: 200 x 40 log-Mel -> 3 softmax probabilities (background, wake_up_neo, neo_wake_up).
    public static func bodyHook() -> BodyHook {
        { logmel in
            guard logmel.count == WakeSpotter.logmelFloats else {
                throw NeoWakeOrtHooksError.unexpectedInputCount(expected: WakeSpotter.logmelFloats, got: logmel.count)
            }
            guard let session = NeoWakeSessions.shared.session(for: .body) else {
                throw NeoWakeOrtHooksError.sessionNotLoaded(.body)
            }
            let out = try run(session, input: logmel,
                              shape: [1, WakeSpotter.logmelFrames as NSNumber, WakeSpotter.logmelBins as NSNumber],
                              inputName: bodyInput, outputName: bodyOutput)
            guard out.count == WakeSpotter.classCount else {
                throw NeoWakeOrtHooksError.unexpectedOutputCount(expected: WakeSpotter.classCount, got: out.count)
            }
            return out
        }
    }

    private static func run(_ session: ORTSession, input: [Float], shape: [NSNumber],
                            inputName: String, outputName: String) throws -> [Float] {
        let inputData = NSMutableData(bytes: input, length: input.count * MemoryLayout<Float>.stride)
        let value = try ORTValue(tensorData: inputData, elementType: .float, shape: shape)
        let outputs = try session.run(withInputs: [inputName: value], outputNames: Set([outputName]), runOptions: nil)
        guard let output = outputs[outputName] else {
            throw NeoWakeOrtHooksError.unexpectedOutputCount(expected: 1, got: 0)
        }
        let data = try output.tensorData() as Data
        return data.withUnsafeBytes { rawBuffer in
            Array(rawBuffer.bindMemory(to: Float.self))
        }
    }
}
