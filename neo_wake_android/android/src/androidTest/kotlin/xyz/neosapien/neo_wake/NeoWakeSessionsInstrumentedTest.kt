package xyz.neosapien.neo_wake

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer

/**
 * U2 native session-layer tests for [NeoWakeSessions].
 *
 * DEVICE-GATED: instrumented (`connectedAndroidTest`), not a plain JVM unit
 * test — `OrtEnvironment`/`OrtSession` load ORT's native `.so`, which is only
 * present on a real Android device/emulator classloader. This was written
 * but NOT run in this environment (no emulator available); see the U2 task's
 * verification notes.
 */
@RunWith(AndroidJUnit4::class)
class NeoWakeSessionsInstrumentedTest {
    @Test
    fun ensureInitialized_isIdempotent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NeoWakeSessions.ensureInitialized(context)
        val melAfterFirst = NeoWakeSessions.session(NeoWakeSessions.Graph.MELSPECTROGRAM)
        NeoWakeSessions.ensureInitialized(context)
        val melAfterSecond = NeoWakeSessions.session(NeoWakeSessions.Graph.MELSPECTROGRAM)

        assertNotNull(melAfterFirst)
        assertSame("second ensureInitialized() must not recreate the session", melAfterFirst, melAfterSecond)
    }

    @Test
    fun melspectrogramSession_dummyInput_returnsExpectedOutputRank() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NeoWakeSessions.ensureInitialized(context)
        val session = NeoWakeSessions.session(NeoWakeSessions.Graph.MELSPECTROGRAM)
        assertNotNull("melspectrogram session not created", session)

        // OrtEnvironment.getEnvironment() is a process-wide singleton shared
        // with NeoWakeSessions itself — do not close it here.
        val env = OrtEnvironment.getEnvironment()
        // Model input: [batch_size, samples] float32. 1280 samples = one
        // 80ms/16kHz advance (plan R1).
        val samples = FloatArray(1280)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, 1280)).use { input ->
            session!!.run(mapOf("input" to input)).use { result ->
                val output = result[0] as OnnxTensor
                val shape = output.info.shape
                // [time, 1, ?, 32] — last dim is the fixed mel-bin count.
                assertTrue(shape.size == 4)
                assertTrue(shape.last() == 32L)
            }
        }
    }

    @Test
    fun embeddingSession_dummyInput_returnsExpectedOutputShape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NeoWakeSessions.ensureInitialized(context)
        val session = NeoWakeSessions.session(NeoWakeSessions.Graph.EMBEDDING)
        assertNotNull("embedding session not created", session)

        val env = OrtEnvironment.getEnvironment()
        // Model input: [batch, 76, 32, 1] float32 mel frames.
        val samples = FloatArray(1 * 76 * 32 * 1)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, 76, 32, 1)).use { input ->
            session!!.run(mapOf("input_1" to input)).use { result ->
                val output = result[0] as OnnxTensor
                assertArrayEquals(longArrayOf(1, 1, 1, 96), output.info.shape)
            }
        }
    }

    @Test
    fun classifierSession_dummyInput_returnsExpectedOutputShape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NeoWakeSessions.ensureInitialized(context)
        val session = NeoWakeSessions.session(NeoWakeSessions.Graph.CLASSIFIER)
        assertNotNull("classifier session not created", session)

        val env = OrtEnvironment.getEnvironment()
        // Model input: [batch, 16, 96] float32 stacked embeddings.
        val samples = FloatArray(1 * 16 * 96)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, 16, 96)).use { input ->
            session!!.run(mapOf("onnx::Flatten_0" to input)).use { result ->
                val output = result[0] as OnnxTensor
                assertArrayEquals(longArrayOf(1, 1), output.info.shape)
            }
        }
    }

    @Test
    fun lowPowerSessionOptions_appliedConfigEntriesMatch() {
        // Unlike onnxruntime-objc, the Android Java API exposes
        // getConfigEntries(), so this can assert the applied values, not just
        // that setting them didn't throw.
        val options = NeoWakeSessionConfig.newLowPowerSessionOptions()
        val entries = options.configEntries
        assertTrue(entries[NeoWakeSessionConfig.INTRA_OP_ALLOW_SPINNING_KEY] == NeoWakeSessionConfig.ALLOW_SPINNING_DISABLED_VALUE)
        assertTrue(entries[NeoWakeSessionConfig.INTER_OP_ALLOW_SPINNING_KEY] == NeoWakeSessionConfig.ALLOW_SPINNING_DISABLED_VALUE)
    }
}
