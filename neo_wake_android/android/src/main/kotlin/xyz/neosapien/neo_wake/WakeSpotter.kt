package xyz.neosapien.neo_wake

/**
 * The streaming chorus6 detector for "wake up neo" / "neo wake up".
 *
 * Every constant here is arithmetic from `chorus6.config.json`, not a
 * tunable — except [threshold], the one runtime value pushed on `arm`:
 *   - a 2.0 s ring of 32000 float samples, int16 scaled by 1/32768. The
 *     model was trained on `[-1, 1]` audio; feeding raw int16 magnitudes
 *     saturates the frontend's AGC and scores near zero with no error.
 *   - 1280-sample (80 ms) advances. Every hop the whole ring goes through
 *     the frontend graph (raw audio -> 200x40 log-Mel, AGC v2 inside) and the
 *     body graph (log-Mel -> 3 softmax probabilities).
 *   - score = probs[1] + probs[2]: the two phrase orders compete with
 *     background, not with each other, so either class alone under-reads a
 *     real saying. The argmax between them is NOT used anywhere (71.7%
 *     accurate per the bundle).
 *   - fire = two consecutive hops at or over [threshold] while armed;
 *     disarm on fire; re-arm when a later score drops below
 *     [RELEASE_FRACTION] x threshold. Hysteresis, never a wall-clock timer —
 *     a timer produced a 21-fire rampage in the bundle's own testing.
 *   - [resetRing] on every fire: everything in the ring is consumed audio, so
 *     zeroing it is the bundle's "wipe history" with no index bookkeeping,
 *     and the next hop scores background (~0.04), which re-arms on its own.
 *
 * Reset scopes: a fire zeroes the ring and disarms; a dropped fragment
 * ([onFrameDropped]) zeroes the ring but leaves `armed` alone — a
 * discontinuity is never spliced into the 2 s window; [reset] is the full
 * disconnect/disarm reset.
 *
 * Not reentrant: [process], [onFrameDropped] and [reset] all run on the
 * frame worker's single thread — [NeoWakeFrameWorker] delivers the overflow
 * marker in-band on that thread, never from the BLE callback.
 *
 * The two ONNX calls are injected hooks, so this file has zero ORT/plugin
 * dependency and is exercised by a plain JVM JUnit test with fakes standing
 * in for the sessions — the real hooks ([NeoWakeOrtHooks]) are wired in by
 * [NeoWakeAttach], not here.
 */

/** Turns the 32000-float audio window into 8000 log-Mel floats (row-major `[frame][bin]`). */
typealias FrontendHook = (FloatArray) -> FloatArray

/** Turns the 8000 log-Mel floats into the 3 class probabilities (softmax already applied). */
typealias BodyHook = (FloatArray) -> FloatArray

/** One step's result, returned on every call to [WakeSpotter.process], not
 * only on a detection — the caller owns logging and this stays a pure
 * transform. */
data class WakeSpotterStep(
    /** Counts calls to [WakeSpotter.process] since construction or the last [WakeSpotter.reset]. */
    val stepIndex: Int,
    /** `probs[1] + probs[2]` for this hop. Nullable only for API stability with
     * callers that log `score ?: 0.0`; the chorus6 chain scores every hop. */
    val score: Double?,
    /** True on the hop that fires (see the file header for the gate). */
    val fired: Boolean,
    /** Wall-clock cost of the frontend hook on this hop, for the cost gate. */
    val frontendMs: Double = 0.0,
    /** Wall-clock cost of the body hook on this hop, for the cost gate. */
    val bodyMs: Double = 0.0,
)

/** The streaming detector. Construct one per arm; a pendant reconnect means
 * a new arm. */
class WakeSpotter(
    /** Fires at scores `>= threshold` (two hops in a row). Required, with no default. */
    val threshold: Double,
    private val frontend: FrontendHook,
    private val body: BodyHook,
) {
    companion object {
        // Geometry, exposed so the codec pipeline reads it rather than
        // duplicating it — the framer's frame length is built from
        // ADVANCE_SAMPLES.
        const val ADVANCE_SAMPLES = 1280 // 80 ms @ 16 kHz
        const val WINDOW_SAMPLES = 32000 // 2.0 s @ 16 kHz
        const val LOGMEL_FRAMES = 200
        const val LOGMEL_BINS = 40
        const val LOGMEL_FLOATS = LOGMEL_FRAMES * LOGMEL_BINS
        const val CLASS_COUNT = 3
        const val RELEASE_FRACTION = 0.55
        const val CONSECUTIVE_HOPS = 2
        const val INT16_SCALE = 1.0f / 32768.0f
    }

    /** Re-arm level: `RELEASE_FRACTION * threshold`. */
    val release: Double = RELEASE_FRACTION * threshold

    // Zero at construction and after every reset — zeros ARE the
    // "no audio yet" state the frontend's AGC maps to background.
    private val ring = FloatArray(WINDOW_SAMPLES)

    private var armed = true
    private var run = 0
    private var step = 0

    /** True unless a fire has happened and no hop has since dropped below [release]. */
    val isArmed: Boolean get() = armed

    /** Consecutive hops at or over [threshold] so far. */
    val consecutiveOverThreshold: Int get() = run

    /** Zeroes the ring and the consecutive-hop counter. Leaves `armed` as-is. */
    private fun resetRing() {
        ring.fill(0f)
        run = 0
    }

    /** A discarded fragment (decode failure, short header, worker overflow):
     * the window would otherwise splice non-adjacent audio, so it restarts
     * from silence. `armed` is left alone — a drop is not a fire. */
    fun onFrameDropped() {
        resetRing()
    }

    /** Full reset for a disconnect, an idle stream, or a disarm. */
    fun reset() {
        resetRing()
        armed = true
        step = 0
    }

    /** Feeds one 1280-sample (80 ms) advance through the chain. */
    fun process(frame: ShortArray): WakeSpotterStep {
        require(frame.size == ADVANCE_SAMPLES) {
            "WakeSpotter.process expects $ADVANCE_SAMPLES-sample advance, got ${frame.size}"
        }

        // 1. Shift the ring left by one advance and append the new samples,
        // scaled into [-1, 1].
        System.arraycopy(ring, ADVANCE_SAMPLES, ring, 0, WINDOW_SAMPLES - ADVANCE_SAMPLES)
        val tail = WINDOW_SAMPLES - ADVANCE_SAMPLES
        for (i in 0 until ADVANCE_SAMPLES) {
            ring[tail + i] = frame[i] * INT16_SCALE
        }

        // 2. Frontend then body on a copy (the hooks may hand the buffer to
        // ORT, which must never alias the live ring).
        val t0 = System.nanoTime()
        val logmel = frontend(ring.copyOf())
        val t1 = System.nanoTime()
        val probs = body(logmel)
        val t2 = System.nanoTime()
        check(probs.size == CLASS_COUNT) { "body hook returned ${probs.size} classes, expected $CLASS_COUNT" }
        val score = probs[1].toDouble() + probs[2].toDouble()

        // 3. Gate — order matters: re-arm check first so a hop that sits
        // below release re-arms before it is counted.
        if (score < release) armed = true
        run = if (score >= threshold) run + 1 else 0
        var fired = false
        if (run >= CONSECUTIVE_HOPS && armed) {
            fired = true
            armed = false
            resetRing()
        }

        val result = WakeSpotterStep(step, score, fired, (t1 - t0) / 1e6, (t2 - t1) / 1e6)
        step++
        return result
    }
}
