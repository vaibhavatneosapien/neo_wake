package xyz.neosapien.neo_wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.FloatBuffer

/**
 * Builds the real [MelHook]/[EmbedHook]/[ClassifyHook] trio [WakeSpotter]
 * needs, backed by the three ORT sessions U2's [NeoWakeSessions] already
 * created — this is the "running on the three ORT sessions U2 built" half
 * of KTD4. [WakeSpotter] itself stays plugin-free (see its own file
 * header); this is the one place that actually touches ORT, mirroring why
 * `wake_word_service.dart` (not `wake_spotter.dart`) is where the Dart
 * original wires `OrtSession.run` into the same three hook slots.
 *
 * DEVICE-GATED: [ai.onnxruntime.OrtSession.run] loads ORT's native runtime,
 * so nothing here can be exercised by a plain JVM `gradle test` — see
 * [NeoOpusDecoderInstrumentedTest]'s own note for the same constraint on the
 * codec bridge, and [NeoWakeSessionsInstrumentedTest] (U2) for the ORT
 * input/output tensor names this mirrors exactly (`"input"`/`"output"` for
 * mel, `"input_1"`/`"conv2d_19"` for embed, `"onnx::Flatten_0"`/`"output"`
 * for classify).
 */
object NeoWakeOrtHooks {
    /**
     * Builds a [MelHook] over the `melspectrogram` session. Input tensor
     * `"input"`, shape `[1, 1760]`. Output `"output"`, shape
     * `[time=8, 1, ?, 32]` — flattened here to the 256 raw floats
     * [WakeSpotter] expects (8 frames * 32 bins), row-major.
     */
    fun melHook(): MelHook = { audioWindow ->
        val session = NeoWakeSessions.session(NeoWakeSessions.Graph.MELSPECTROGRAM)
            ?: error("melspectrogram session not loaded — call NeoWakeSessions.ensureInitialized first")
        val env = OrtEnvironment.getEnvironment()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(audioWindow), longArrayOf(1, audioWindow.size.toLong())).use { input ->
            session.run(mapOf("input" to input)).use { result ->
                floatArrayFrom(result[0] as OnnxTensor)
            }
        }
    }

    /**
     * Builds an [EmbedHook] over the `embedding` session. Input `"input_1"`,
     * shape `[1, 76, 32, 1]` (exactly what [WakeSpotter] already flattens
     * its mel buffer to). Output `"conv2d_19"`, shape `[1, 1, 1, 96]`.
     */
    fun embedHook(): EmbedHook = { melWindow, shape ->
        val session = NeoWakeSessions.session(NeoWakeSessions.Graph.EMBEDDING)
            ?: error("embedding session not loaded — call NeoWakeSessions.ensureInitialized first")
        val env = OrtEnvironment.getEnvironment()
        val longShape = shape.map { it.toLong() }.toLongArray()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(melWindow), longShape).use { input ->
            session.run(mapOf("input_1" to input)).use { result ->
                floatArrayFrom(result[0] as OnnxTensor)
            }
        }
    }

    /**
     * Builds a [ClassifyHook] over the `classifier` session. Input
     * `"onnx::Flatten_0"`, shape `[1, 16, 96]`. Output `"output"`, shape
     * `[1, 1]` — the sigmoid score, already baked into the graph (see
     * [WakeSpotter]'s file header).
     */
    fun classifyHook(): ClassifyHook = { embeddingWindow, shape ->
        val session = NeoWakeSessions.session(NeoWakeSessions.Graph.CLASSIFIER)
            ?: error("classifier session not loaded — call NeoWakeSessions.ensureInitialized first")
        val env = OrtEnvironment.getEnvironment()
        val longShape = shape.map { it.toLong() }.toLongArray()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(embeddingWindow), longShape).use { input ->
            session.run(mapOf("onnx::Flatten_0" to input)).use { result ->
                val scores = floatArrayFrom(result[0] as OnnxTensor)
                scores.first().toDouble()
            }
        }
    }

    private fun floatArrayFrom(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer ?: error("expected a float tensor output")
        val out = FloatArray(buffer.remaining())
        buffer.get(out)
        return out
    }
}
