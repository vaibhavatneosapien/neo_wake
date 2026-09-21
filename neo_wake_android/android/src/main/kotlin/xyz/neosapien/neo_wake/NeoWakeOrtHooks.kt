package xyz.neosapien.neo_wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.FloatBuffer

/**
 * Builds the real [FrontendHook]/[BodyHook] pair [WakeSpotter] needs,
 * backed by the two ORT sessions [NeoWakeSessions] created. [WakeSpotter]
 * itself stays plugin-free (see its own file header); this is the one place
 * that actually touches ORT.
 *
 * Tensor contract (chorus6.config.json):
 *   frontend  "audio"  float32 [1, 32000]   -> "logmel" float32 [1, 200, 40]
 *   body      "logmel" float32 [1, 200, 40] -> "probs"  float32 [1, 3], softmax
 * The frontend's output time dim is labelled dynamic in the graph, so both
 * hooks assert the element count they hand back — a shape drift must fail
 * loudly here, never score confidently downstream (see docs/solutions
 * onnx-wake-chain-silent-frontend-bugs-score-confidently).
 *
 * DEVICE-GATED: [ai.onnxruntime.OrtSession.run] loads ORT's native runtime,
 * so nothing here can be exercised by a plain JVM `gradle test` — see
 * [NeoWakeSessionsInstrumentedTest] for the device tier.
 */
object NeoWakeOrtHooks {
    const val FRONTEND_INPUT = "audio"
    const val FRONTEND_OUTPUT = "logmel"
    const val BODY_INPUT = "logmel"
    const val BODY_OUTPUT = "probs"

    /** Frontend: [WakeSpotter.WINDOW_SAMPLES] floats in `[-1, 1]` -> 200 x 40 log-Mel. */
    fun frontendHook(): FrontendHook = { audioWindow ->
        require(audioWindow.size == WakeSpotter.WINDOW_SAMPLES) {
            "frontendHook expects ${WakeSpotter.WINDOW_SAMPLES} samples, got ${audioWindow.size}"
        }
        val session = NeoWakeSessions.session(NeoWakeSessions.Graph.FRONTEND)
            ?: error("frontend session not loaded — call NeoWakeSessions.ensureInitialized first")
        val env = OrtEnvironment.getEnvironment()
        val out = OnnxTensor.createTensor(env, FloatBuffer.wrap(audioWindow), longArrayOf(1, audioWindow.size.toLong())).use { input ->
            session.run(mapOf(FRONTEND_INPUT to input)).use { result ->
                floatArrayFrom(result.get(FRONTEND_OUTPUT).get() as OnnxTensor)
            }
        }
        check(out.size == WakeSpotter.LOGMEL_FLOATS) {
            "frontend returned ${out.size} floats, expected ${WakeSpotter.LOGMEL_FLOATS}"
        }
        out
    }

    /** Body: 200 x 40 log-Mel -> 3 softmax probabilities (background, wake_up_neo, neo_wake_up). */
    fun bodyHook(): BodyHook = { logmel ->
        require(logmel.size == WakeSpotter.LOGMEL_FLOATS) {
            "bodyHook expects ${WakeSpotter.LOGMEL_FLOATS} floats, got ${logmel.size}"
        }
        val session = NeoWakeSessions.session(NeoWakeSessions.Graph.BODY)
            ?: error("body session not loaded — call NeoWakeSessions.ensureInitialized first")
        val env = OrtEnvironment.getEnvironment()
        val out = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(logmel),
            longArrayOf(1, WakeSpotter.LOGMEL_FRAMES.toLong(), WakeSpotter.LOGMEL_BINS.toLong()),
        ).use { input ->
            session.run(mapOf(BODY_INPUT to input)).use { result ->
                floatArrayFrom(result.get(BODY_OUTPUT).get() as OnnxTensor)
            }
        }
        check(out.size == WakeSpotter.CLASS_COUNT) {
            "body returned ${out.size} floats, expected ${WakeSpotter.CLASS_COUNT}"
        }
        out
    }

    private fun floatArrayFrom(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer ?: error("expected a float tensor output")
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }
}
