package xyz.neosapien.neo_wake

/**
 * The pure ONNX streaming chain for "Neo Simsim" detection (KTD3/KTD4).
 *
 * 1:1 port of `lib/core/neo_agent/wake_spotter.dart` — see that file's header
 * for the full rationale. Every stride here is arithmetic with one correct
 * answer, not a tunable:
 *   - 1280-sample (80 ms) advance, fed with the preceding 480 samples of
 *     overlap, so the mel model sees 1760 samples and yields the 8 frames an
 *     80 ms advance represents. Feed it 1280 alone and it yields 5 frames,
 *     silently dropping 37% of the mel timeline.
 *   - `(v / 10) + 2` on every mel value as it enters the mel buffer, at
 *     exactly one place. Omit it and scores stay near zero with no error
 *     anywhere.
 *   - The classifier only ever sees a genuinely full 16-embedding ring. A
 *     half-zeroed ring of otherwise real embeddings scores confidently
 *     positive, not near zero.
 *
 * TWO RESET SCOPES, NOT ONE — see wake_spotter.dart's header for why.
 * [onDetection]/[onFrameDropped] clear the embedding ring only; mel and raw
 * overlap keep running. [reset] clears all three, for a disconnect, an idle
 * stream, or a disarm.
 *
 * Not reentrant: [process] assumes its caller pumps steps serially (mirrors
 * the Dart class exactly, including that assumption).
 *
 * The three ONNX calls are injected hooks, same as the Dart original, so
 * this file has zero ORT/plugin dependency and is exercised by a plain JVM
 * JUnit test with fake hooks standing in for the three sessions — the real
 * sessions ([NeoWakeSessions], U2) are wired in by the codec pipeline, not
 * here.
 */

/** Turns one step's 1760-sample audio window into this step's 8 raw mel
 * frames (256 floats, row-major `[frame][bin]`). Unscaled — `(v / 10) + 2`
 * is applied by [WakeSpotter], not the hook. */
typealias MelHook = (FloatArray) -> FloatArray

/** Turns the mel buffer, flattened to `shape` (`[1, 76, 32, 1]`), into one
 * 96-float embedding. */
typealias EmbedHook = (FloatArray, List<Int>) -> FloatArray

/** Turns the embedding ring, flattened to `shape` (`[1, 16, 96]`), into one
 * score. The sigmoid is already baked into the graph. */
typealias ClassifyHook = (FloatArray, List<Int>) -> Double

/** One step's result, returned on every call to [WakeSpotter.process], not
 * only on a detection — the caller owns logging and this stays a pure
 * transform. */
data class WakeSpotterStep(
    /** Counts calls to [WakeSpotter.process] since construction or the last [WakeSpotter.reset]. */
    val stepIndex: Int,
    /** Null while warming: no score exists until the ring holds 16 real embeddings. */
    val score: Double?,
    /** `score != null && score >= threshold`. */
    val fired: Boolean,
)

/** The streaming detector. Construct one per arm; a pendant reconnect means
 * a new arm. */
class WakeSpotter(
    /** Fires at scores `>= threshold`. Required, with no default. */
    val threshold: Double,
    private val mel: MelHook,
    private val embed: EmbedHook,
    private val classify: ClassifyHook,
) {
    companion object {
        // Geometry, exposed so the codec pipeline reads it rather than
        // duplicating it — the framer's frame length is built from
        // ADVANCE_SAMPLES.
        const val ADVANCE_SAMPLES = 1280 // 80 ms @ 16 kHz
        const val OVERLAP_SAMPLES = 480
        const val MEL_INPUT_SAMPLES = ADVANCE_SAMPLES + OVERLAP_SAMPLES // 1760
        const val MEL_FRAMES_PER_STEP = 8
        const val MEL_BIN_COUNT = 32
        const val MEL_BUFFER_FRAMES = 76
        const val EMBEDDING_DIM = 96
        const val EMBEDDING_RING_DEPTH = 16
    }

    // The 480 samples immediately behind the next advance. Zero at
    // construction and after reset() — that zero-fill IS the first-step
    // padding, not a special case handled separately in process().
    private var rawOverlap = FloatArray(OVERLAP_SAMPLES)

    // Starts EMPTY, unlike a pre-seeded window: the first embedding must not
    // run until 76 REAL mel frames exist (~800 ms).
    private val melBuffer = ArrayDeque<FloatArray>()

    // Also starts empty, depth 16, never pre-seeded with zeros: the
    // classifier must never see a placeholder entry.
    private val embeddingRing = ArrayDeque<FloatArray>()

    private var step = 0

    val melBufferLength: Int get() = melBuffer.size
    val embeddingRingLength: Int get() = embeddingRing.size

    /** Clears the embedding ring only. See the file header for why mel and
     * raw audio are deliberately left running. */
    fun onDetection() {
        embeddingRing.clear()
    }

    /** Clears the embedding ring only, same scope as [onDetection] — a drop
     * is a discontinuity for the ring, not a reason to blind the whole
     * chain. Takes no audio: a dropped frame's audio is by definition never
     * fed to [process]. */
    fun onFrameDropped() {
        embeddingRing.clear()
    }

    /** Clears mel and raw audio too, for a disconnect, a stream going idle,
     * or a disarm — points where there is no audio continuity to preserve. */
    fun reset() {
        rawOverlap = FloatArray(OVERLAP_SAMPLES)
        melBuffer.clear()
        embeddingRing.clear()
        step = 0
    }

    /** Feeds one 1280-sample (80 ms) advance through the chain. */
    fun process(frame: ShortArray): WakeSpotterStep {
        require(frame.size == ADVANCE_SAMPLES) {
            "WakeSpotter.process expects $ADVANCE_SAMPLES-sample advance, got ${frame.size}"
        }

        // 1. Assemble this step's fixed-shape 1760-sample window: last
        // step's 480-sample tail ahead of this step's 1280-sample advance.
        val audioWindow = FloatArray(MEL_INPUT_SAMPLES)
        rawOverlap.copyInto(audioWindow, 0)
        for (i in 0 until ADVANCE_SAMPLES) {
            audioWindow[OVERLAP_SAMPLES + i] = frame[i].toFloat()
        }

        // Carry this step's own tail forward for the NEXT step, before
        // anything below can throw and leave the overlap stale.
        val nextOverlap = FloatArray(OVERLAP_SAMPLES)
        for (i in 0 until OVERLAP_SAMPLES) {
            nextOverlap[i] = frame[ADVANCE_SAMPLES - OVERLAP_SAMPLES + i].toFloat()
        }
        rawOverlap = nextOverlap

        // 2. Mel: 1760 samples in, 8 frames out, scaled at exactly one place
        // as they enter the buffer.
        val rawMel = mel(audioWindow)
        for (f in 0 until MEL_FRAMES_PER_STEP) {
            val scaled = FloatArray(MEL_BIN_COUNT)
            for (b in 0 until MEL_BIN_COUNT) {
                scaled[b] = rawMel[f * MEL_BIN_COUNT + b] / 10f + 2f
            }
            melBuffer.addLast(scaled)
        }
        while (melBuffer.size > MEL_BUFFER_FRAMES) melBuffer.removeFirst()

        var score: Double? = null

        // 3. Embed, only once the buffer holds 76 real frames — never a
        // partial window.
        if (melBuffer.size == MEL_BUFFER_FRAMES) {
            val melFlat = FloatArray(MEL_BUFFER_FRAMES * MEL_BIN_COUNT)
            var i = 0
            for (frameVals in melBuffer) {
                frameVals.copyInto(melFlat, i)
                i += MEL_BIN_COUNT
            }
            val embedding = embed(melFlat, listOf(1, MEL_BUFFER_FRAMES, MEL_BIN_COUNT, 1))
            embeddingRing.addLast(embedding)
            while (embeddingRing.size > EMBEDDING_RING_DEPTH) embeddingRing.removeFirst()

            // 4. Classify, only once the ring holds 16 real embeddings — the
            // gate that makes the zero-padding above safe.
            if (embeddingRing.size == EMBEDDING_RING_DEPTH) {
                val embFlat = FloatArray(EMBEDDING_RING_DEPTH * EMBEDDING_DIM)
                var j = 0
                for (e in embeddingRing) {
                    e.copyInto(embFlat, j)
                    j += EMBEDDING_DIM
                }
                score = classify(embFlat, listOf(1, EMBEDDING_RING_DEPTH, EMBEDDING_DIM))
            }
        }

        val result = WakeSpotterStep(step, score, score != null && score >= threshold)
        step++
        return result
    }
}
