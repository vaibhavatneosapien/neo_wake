package xyz.neosapien.neo_wake

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-gated golden: streams the bundle's held-out positives (padded with
 * 1.5 s / 2.5 s of silence, exactly as `script/chorus6_reference.py` does)
 * through the REAL chorus6 sessions and the real [WakeSpotter] gate at the
 * shipped threshold. The laptop reference fires both at hop 38 (the hop
 * ending 3120 ms); ORT on-device numerics may differ by a hair, so ±1 hop.
 *
 * Every post-fire hop's score is logged so re-arm latency after the ring
 * reset is visible in logcat, not just the fire count.
 */
@RunWith(AndroidJUnit4::class)
class WakeSpotterDeviceGoldenTest {
    private companion object {
        const val TAG = "WakeSpotterDeviceGolden"
        const val THRESHOLD = 0.45
        const val LEAD_SAMPLES = 24_000 // 1.5 s
        const val TAIL_SAMPLES = 40_000 // 2.5 s
        const val REFERENCE_FIRE_HOP = 38
    }

    private data class Run(val fires: List<Int>, val scores: List<Double>, val maxFrontendMs: Double, val maxBodyMs: Double)

    private fun stream(pcm: ShortArray): Run {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        NeoWakeSessions.ensureInitialized(target)
        val spotter = WakeSpotter(THRESHOLD, NeoWakeOrtHooks.frontendHook(), NeoWakeOrtHooks.bodyHook())
        val padded = ShortArray(LEAD_SAMPLES) + pcm + ShortArray(TAIL_SAMPLES)
        val fires = mutableListOf<Int>()
        val scores = mutableListOf<Double>()
        var maxFrontend = 0.0
        var maxBody = 0.0
        var hop = 0
        var start = 0
        while (start + WakeSpotter.ADVANCE_SAMPLES <= padded.size) {
            val step = spotter.process(padded.copyOfRange(start, start + WakeSpotter.ADVANCE_SAMPLES))
            scores.add(step.score!!)
            maxFrontend = maxOf(maxFrontend, step.frontendMs)
            maxBody = maxOf(maxBody, step.bodyMs)
            if (step.fired) fires.add(hop)
            if (fires.isNotEmpty()) {
                Log.i(TAG, "post-fire hop=$hop score=${String.format(java.util.Locale.US, "%.3f", step.score)} armed=${spotter.isArmed}")
            }
            hop++
            start += WakeSpotter.ADVANCE_SAMPLES
        }
        Log.i(TAG, "fires=$fires max_frontend_ms=${String.format(java.util.Locale.US, "%.2f", maxFrontend)} max_body_ms=${String.format(java.util.Locale.US, "%.2f", maxBody)}")
        return Run(fires, scores, maxFrontend, maxBody)
    }

    @Test
    fun positiveWakeUpNeo_firesExactlyOnce_withinOneHopOfTheReference() {
        val test = InstrumentationRegistry.getInstrumentation().context
        val run = stream(WakeTestAudio.loadWavPcm(test, "wakeword/positive_wake_up_neo.wav"))
        assertEquals("fires=${run.fires}", 1, run.fires.size)
        assertTrue("fire hop ${run.fires[0]} vs reference $REFERENCE_FIRE_HOP", Math.abs(run.fires[0] - REFERENCE_FIRE_HOP) <= 1)
    }

    @Test
    fun positiveNeoWakeUp_firesExactlyOnce() {
        val test = InstrumentationRegistry.getInstrumentation().context
        val run = stream(WakeTestAudio.loadWavPcm(test, "wakeword/positive_neo_wake_up.wav"))
        assertEquals("fires=${run.fires}", 1, run.fires.size)
    }

    @Test
    fun silence_neverFires_andEveryScoreStaysNearBackground() {
        val run = stream(ShortArray(32_000))
        assertTrue("fires=${run.fires}", run.fires.isEmpty())
        assertTrue("max score ${run.scores.max()}", run.scores.max() <= 0.05)
    }

    @Test
    fun perStepCost_isLoggedAndUnderTheStopCondition() {
        val test = InstrumentationRegistry.getInstrumentation().context
        val run = stream(WakeTestAudio.loadWavPcm(test, "wakeword/positive_wake_up_neo.wav"))
        // Success criterion is 5 ms; 10 ms is the plan's stop condition. Assert
        // only the stop condition so a mid-range device does not fail CI on
        // the softer target — the log line above carries the real number.
        assertTrue("frontend ${run.maxFrontendMs} ms + body ${run.maxBodyMs} ms", run.maxFrontendMs + run.maxBodyMs < 10.0)
    }
}
