import Foundation

#if canImport(onnxruntime_objc)
import onnxruntime_objc
#endif

/// Builds the real `MelHook`/`EmbedHook`/`ClassifyHook` trio `WakeSpotter`
/// needs, backed by the three ORT sessions U2's `NeoWakeSessions` already
/// created — this is the "running on the three ORT sessions U2 built" half
/// of KTD4. `WakeSpotter` itself stays plugin-free (see its own file
/// header); this is the one place that actually touches ORT, mirroring why
/// `wake_word_service.dart` (not `wake_spotter.dart`) is where the Dart
/// original wires `OrtSession.run` into the same three hook slots.
///
/// DEVICE-GATED: `ORTSession.run` loads ORT's native runtime, so nothing
/// here can be exercised by a plain `swift test` — see OpusBridgeTests.swift's
/// own note for the same constraint on the codec bridge, and
/// NeoWakeSessionsTests.swift (U2) for the ORT input/output tensor names
/// this mirrors exactly (`"input"`/`"output"` for mel, `"input_1"`/
/// `"conv2d_19"` for embed, `"onnx::Flatten_0"`/`"output"` for classify).
public enum NeoWakeOrtHooksError: Error {
    case sessionNotLoaded(NeoWakeSessions.Graph)
    case unexpectedOutputShape([NSNumber])
}

public enum NeoWakeOrtHooks {
    /// Builds a `MelHook` over the `melspectrogram` session. Input tensor
    /// `"input"`, shape `[1, 1760]` (batch, samples — see
    /// `NeoWakeSessionsTests.testMelspectrogramSession_dummyInput_returnsExpectedOutputRank`).
    /// Output `"output"`, shape `[time=8, 1, ?, 32]` — flattened here to the
    /// 256 raw floats `WakeSpotter` expects (8 frames * 32 bins), row-major.
    public static func melHook() -> MelHook {
        { audioWindow in
            guard let session = NeoWakeSessions.shared.session(for: .melspectrogram) else {
                throw NeoWakeOrtHooksError.sessionNotLoaded(.melspectrogram)
            }
            let inputData = NSMutableData(
                bytes: audioWindow,
                length: audioWindow.count * MemoryLayout<Float>.stride
            )
            let input = try ORTValue(
                tensorData: inputData,
                elementType: .float,
                shape: [1, audioWindow.count as NSNumber]
            )
            let outputs = try session.run(
                withInputs: ["input": input],
                outputNames: Set(["output"]),
                runOptions: nil
            )
            guard let output = outputs["output"] else {
                throw NeoWakeOrtHooksError.sessionNotLoaded(.melspectrogram)
            }
            return try floatArray(from: output)
        }
    }

    /// Builds an `EmbedHook` over the `embedding` session. Input `"input_1"`,
    /// shape `[1, 76, 32, 1]` (exactly what `WakeSpotter` already flattens
    /// its mel buffer to). Output `"conv2d_19"`, shape `[1, 1, 1, 96]`.
    public static func embedHook() -> EmbedHook {
        { melWindow, shape in
            guard let session = NeoWakeSessions.shared.session(for: .embedding) else {
                throw NeoWakeOrtHooksError.sessionNotLoaded(.embedding)
            }
            let inputData = NSMutableData(
                bytes: melWindow,
                length: melWindow.count * MemoryLayout<Float>.stride
            )
            let input = try ORTValue(
                tensorData: inputData,
                elementType: .float,
                shape: shape.map { NSNumber(value: $0) }
            )
            let outputs = try session.run(
                withInputs: ["input_1": input],
                outputNames: Set(["conv2d_19"]),
                runOptions: nil
            )
            guard let output = outputs["conv2d_19"] else {
                throw NeoWakeOrtHooksError.sessionNotLoaded(.embedding)
            }
            return try floatArray(from: output)
        }
    }

    /// Builds a `ClassifyHook` over the `classifier` session. Input
    /// `"onnx::Flatten_0"`, shape `[1, 16, 96]`. Output `"output"`, shape
    /// `[1, 1]` — the sigmoid score, already baked into the graph (see
    /// `WakeSpotter`'s file header).
    public static func classifyHook() -> ClassifyHook {
        { embeddingWindow, shape in
            guard let session = NeoWakeSessions.shared.session(for: .classifier) else {
                throw NeoWakeOrtHooksError.sessionNotLoaded(.classifier)
            }
            let inputData = NSMutableData(
                bytes: embeddingWindow,
                length: embeddingWindow.count * MemoryLayout<Float>.stride
            )
            let input = try ORTValue(
                tensorData: inputData,
                elementType: .float,
                shape: shape.map { NSNumber(value: $0) }
            )
            let outputs = try session.run(
                withInputs: ["onnx::Flatten_0": input],
                outputNames: Set(["output"]),
                runOptions: nil
            )
            guard let output = outputs["output"] else {
                throw NeoWakeOrtHooksError.sessionNotLoaded(.classifier)
            }
            let scores = try floatArray(from: output)
            guard let first = scores.first else {
                throw NeoWakeOrtHooksError.unexpectedOutputShape([])
            }
            return Double(first)
        }
    }

    private static func floatArray(from value: ORTValue) throws -> [Float] {
        let data = try value.tensorData() as Data
        return data.withUnsafeBytes { rawBuffer in
            Array(rawBuffer.bindMemory(to: Float.self))
        }
    }
}
