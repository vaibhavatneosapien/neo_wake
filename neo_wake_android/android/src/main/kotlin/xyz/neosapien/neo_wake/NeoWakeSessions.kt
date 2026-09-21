package xyz.neosapien.neo_wake

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped holder for the two ONNX Runtime sessions the chorus6
 * wake-word pipeline runs: the frontend (raw 2 s audio -> log-Mel, AGC v2
 * inside) and the body (log-Mel -> 3-class softmax).
 *
 * ONLY the session layer: create the environment once, create one session
 * per bundled graph once, and hand sessions back out for reuse. No audio
 * decode and no decision logic here — that is [WakeSpotter].
 *
 * Low-power session config (plan R6): a single intra-op thread, intra- AND
 * inter-op thread spinning explicitly disabled, sequential execution mode,
 * and the CPU execution provider only (no NNAPI/CoreML providers added).
 */
object NeoWakeSessions {
    /** Graph names, named for the bundled model files under assets/wakeword.
     * New filenames on every model swap — the runtime cache below is keyed
     * on the name, so a same-named retrain would keep the old weights (see
     * docs/solutions onnx-wake-chain-silent-frontend-bugs-score-confidently). */
    enum class Graph(val fileName: String) {
        FRONTEND("chorus6_frontend"),
        BODY("chorus6"),
    }

    private val lock = Any()
    private var environment: OrtEnvironment? = null
    private val sessions = ConcurrentHashMap<String, OrtSession>()

    /**
     * Creates the ORT environment and the two graph sessions if they do not
     * already exist. Idempotent: a second call is a no-op for any graph that
     * already has a live session.
     */
    fun ensureInitialized(context: Context) {
        synchronized(lock) {
            val env = environment ?: OrtEnvironment.getEnvironment().also { environment = it }

            for (graph in Graph.entries) {
                if (sessions.containsKey(graph.fileName)) continue
                val modelPath = copyBundledModelToCache(context, graph.fileName)
                val options = NeoWakeSessionConfig.newLowPowerSessionOptions()
                sessions[graph.fileName] = env.createSession(modelPath, options)
            }
        }
    }

    /** The session for a graph, if [ensureInitialized] has run. */
    fun session(graph: Graph): OrtSession? = sessions[graph.fileName]

    /**
     * Copies a bundled `.onnx` model out of `assets/wakeword` to the app
     * cache dir (KTD7: `createSession` needs a filesystem path).
     */
    private fun copyBundledModelToCache(context: Context, name: String): String {
        val dest = File(context.cacheDir, "neo_wake/$name.onnx")
        dest.parentFile?.mkdirs()
        context.assets.open("wakeword/$name.onnx").use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest.absolutePath
    }
}

/**
 * Builds [OrtSession.SessionOptions] tuned for 24/7 low-power inference
 * (plan R6). Split out from [NeoWakeSessions] so the config values it
 * applies (thread counts, spin keys) are assertable from a plain JVM unit
 * test that never touches the native ORT library.
 */
object NeoWakeSessionConfig {
    const val INTRA_OP_NUM_THREADS = 1
    const val INTER_OP_NUM_THREADS = 1

    // ORT session config keys: onnxruntime_session_options_config_keys.h
    const val INTRA_OP_ALLOW_SPINNING_KEY = "session.intra_op.allow_spinning"
    const val INTER_OP_ALLOW_SPINNING_KEY = "session.inter_op.allow_spinning"
    const val ALLOW_SPINNING_DISABLED_VALUE = "0"

    /** Touches the native ORT library — real device/emulator only. */
    fun newLowPowerSessionOptions(): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(INTRA_OP_NUM_THREADS)
        options.setInterOpNumThreads(INTER_OP_NUM_THREADS)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        options.addConfigEntry(INTRA_OP_ALLOW_SPINNING_KEY, ALLOW_SPINNING_DISABLED_VALUE)
        options.addConfigEntry(INTER_OP_ALLOW_SPINNING_KEY, ALLOW_SPINNING_DISABLED_VALUE)
        // No addNnapi()/addCoreML() -> CPU EP only.
        options.addCPU(true)
        return options
    }
}
