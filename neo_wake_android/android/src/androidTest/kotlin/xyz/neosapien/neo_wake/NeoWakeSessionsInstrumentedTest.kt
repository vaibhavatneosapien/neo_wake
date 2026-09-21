package xyz.neosapien.neo_wake

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-gated session + hook tests for the chorus6 graphs.
 *
 * Instrumented (`connectedAndroidTest`), not a plain JVM unit test —
 * `OrtEnvironment`/`OrtSession` load ORT's native `.so`, which is only
 * present on a real Android device/emulator classloader. The bundle's
 * reference audio ships as androidTest assets (`wakeword/*.wav`,
 * `reference_vectors.json`) and is read through the TEST apk's context.
 */
@RunWith(AndroidJUnit4::class)
class NeoWakeSessionsInstrumentedTest {
    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun ensureInitialized_isIdempotent() {
        NeoWakeSessions.ensureInitialized(targetContext)
        val first = NeoWakeSessions.session(NeoWakeSessions.Graph.FRONTEND)
        NeoWakeSessions.ensureInitialized(targetContext)
        val second = NeoWakeSessions.session(NeoWakeSessions.Graph.FRONTEND)

        assertNotNull(first)
        assertSame("second ensureInitialized() must not recreate the session", first, second)
        assertNotNull(NeoWakeSessions.session(NeoWakeSessions.Graph.BODY))
    }

    @Test
    fun frontendHook_returnsExactly8000Floats() {
        NeoWakeSessions.ensureInitialized(targetContext)
        val logmel = NeoWakeOrtHooks.frontendHook()(FloatArray(WakeSpotter.WINDOW_SAMPLES))
        assertEquals(WakeSpotter.LOGMEL_FLOATS, logmel.size)
    }

    @Test
    fun bodyHook_rowsSumToOne_softmaxIsInGraph() {
        NeoWakeSessions.ensureInitialized(targetContext)
        val logmel = NeoWakeOrtHooks.frontendHook()(FloatArray(WakeSpotter.WINDOW_SAMPLES))
        val probs = NeoWakeOrtHooks.bodyHook()(logmel)
        assertEquals(WakeSpotter.CLASS_COUNT, probs.size)
        assertEquals(1.0, probs.sumOf { it.toDouble() }, 1e-3)
    }

    @Test
    fun referenceInputs_reproduceTheBundlesWakeScores() {
        NeoWakeSessions.ensureInitialized(targetContext)
        val reference = JSONObject(testContext.assets.open("wakeword/reference_vectors.json").bufferedReader().readText())
        val frontend = NeoWakeOrtHooks.frontendHook()
        val body = NeoWakeOrtHooks.bodyHook()
        for (name in listOf("positive_wake_up_neo", "positive_neo_wake_up", "burst_-30dBFS", "burst_-45dBFS")) {
            val audio = WakeTestAudio.loadWav(testContext, "wakeword/$name.wav")
            val probs = body(frontend(audio))
            val wake = probs[1].toDouble() + probs[2].toDouble()
            assertEquals(name, reference.getJSONObject(name).getDouble("wake_score"), wake, 0.01)
        }
        val silence = body(frontend(FloatArray(WakeSpotter.WINDOW_SAMPLES)))
        assertEquals("silence", reference.getJSONObject("silence").getDouble("wake_score"), silence[1].toDouble() + silence[2].toDouble(), 0.01)
    }

    @Test
    fun frontendHook_rejectsAWrongLengthInput() {
        NeoWakeSessions.ensureInitialized(targetContext)
        try {
            NeoWakeOrtHooks.frontendHook()(FloatArray(1760))
            fail("a 1760-sample input must throw, never score")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("32000"))
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
