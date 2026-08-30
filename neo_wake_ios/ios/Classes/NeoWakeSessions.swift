import Foundation

#if canImport(onnxruntime_objc)
  import onnxruntime_objc
#endif

/// Process-scoped holder for the three ONNX Runtime sessions the wake-word
/// pipeline runs (melspectrogram, embedding, classifier).
///
/// U2 is ONLY the session layer: create the env once, create one session per
/// bundled graph once, and hand sessions back out for reuse. No audio decode
/// and no mel/embed/classify state machine here — that is U3.
///
/// Low-power session config (plan R6): a single intra-op thread, intra- AND
/// inter-op thread spinning explicitly disabled, and the CPU execution
/// provider only (no NNAPI/CoreML providers are appended).
///
/// Deviation from flutter_onnxruntime / R6 wording: onnxruntime-objc 1.23.0's
/// `ORTSessionOptions` has no `setInterOpNumThreads` / execution-mode setter
/// (the ObjC wrapper only exposes `setIntraOpNumThreads` and
/// `addConfigEntry`; inter-op threads and execution mode are C-API-only on
/// this platform). ORT's default execution mode is already `ORT_SEQUENTIAL`
/// (parallel mode is opt-in and requires inter-op threads > 1), so leaving it
/// unset is behaviorally sequential; only the two spinning config keys are
/// set explicitly here.
final class NeoWakeSessions {
    static let shared = NeoWakeSessions()

    /// Graph names the wake-word pipeline runs, named for the bundled model
    /// files (KTD7: keep the version-templated classifier name).
    enum Graph: String, CaseIterable {
        case melspectrogram = "melspectrogram_v1"
        case embedding = "embedding_model_v1"
        case classifier = "neo_sim_sim_encore"
    }

    enum NeoWakeSessionsError: Error {
        case resourceBundleNotFound
        case modelNotFound(String)
    }

    private let lock = NSLock()
    private var env: ORTEnv?
    private var sessions: [String: ORTSession] = [:]

    private init() {}

    /// Creates the ORT environment and the three graph sessions if they do
    /// not already exist. Idempotent: a second call is a no-op for any graph
    /// that already has a live session.
    func ensureInitialized() throws {
        lock.lock()
        defer { lock.unlock() }

        if env == nil {
            env = try ORTEnv(loggingLevel: .warning)
        }
        guard let env else { return }

        for graph in Graph.allCases where sessions[graph.rawValue] == nil {
            let modelPath = try Self.copyBundledModelToTemp(named: graph.rawValue)
            let options = try Self.makeLowPowerSessionOptions()
            sessions[graph.rawValue] = try ORTSession(env: env, modelPath: modelPath, sessionOptions: options)
        }
    }

    /// The session for a graph, if `ensureInitialized()` has run.
    func session(for graph: Graph) -> ORTSession? {
        lock.lock()
        defer { lock.unlock() }
        return sessions[graph.rawValue]
    }

    // MARK: - Session options

    /// Builds session options tuned for 24/7 low-power inference (plan R6).
    static func makeLowPowerSessionOptions() throws -> ORTSessionOptions {
        let options = try ORTSessionOptions()
        try options.setIntraOpNumThreads(1)
        // ORT session config keys: onnxruntime_session_options_config_keys.h
        try options.addConfigEntry(withKey: "session.intra_op.allow_spinning", value: "0")
        try options.addConfigEntry(withKey: "session.inter_op.allow_spinning", value: "0")
        // No providers appended here -> falls back to the CPU EP only.
        return options
    }

    // MARK: - Bundled model resolution

    /// Copies a bundled `.onnx` model out of the plugin's resource bundle to
    /// a temp path (KTD7: ORT's ObjC `createSession` needs a filesystem
    /// path, not an embedded-bundle URL).
    static func copyBundledModelToTemp(named name: String) throws -> String {
        guard let bundle = resourceBundle() else {
            throw NeoWakeSessionsError.resourceBundleNotFound
        }
        guard let sourceURL = bundle.url(forResource: name, withExtension: "onnx", subdirectory: "wakeword")
            ?? bundle.url(forResource: name, withExtension: "onnx") else {
            throw NeoWakeSessionsError.modelNotFound(name)
        }

        let destURL = FileManager.default.temporaryDirectory.appendingPathComponent("\(name).onnx")
        if FileManager.default.fileExists(atPath: destURL.path) {
            try? FileManager.default.removeItem(at: destURL)
        }
        try FileManager.default.copyItem(at: sourceURL, to: destURL)
        return destURL.path
    }

    /// Resolves the `neo_wake_ios.bundle` the podspec's `resource_bundles`
    /// key produces, falling back to the pod's own bundle (SPM layout).
    private static func resourceBundle() -> Bundle? {
        let podBundle = Bundle(for: NeoWakeSessions.self)
        if let bundleURL = podBundle.url(forResource: "neo_wake_ios", withExtension: "bundle"),
           let bundle = Bundle(url: bundleURL) {
            return bundle
        }
        return podBundle
    }
}
