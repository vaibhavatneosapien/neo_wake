package xyz.neosapien.neo_wake

import kotlin.math.sqrt

/**
 * Ported from `wake_word_service.dart`'s `_HeaderProbe`/`rmsOf` (grep
 * `HeaderProbe|rmsOf` there). Neo2 firmware adds a 4th header byte (the
 * command flag) ahead of the codec payload; v0.0.20 of neo_ble strips 3.
 * Feeding the wrong offset to the decoder yields noise and the spotter
 * simply never fires — a silent failure, not a crash — so this measures
 * which offset actually decodes instead of trusting a constant that is
 * right on one firmware and silently wrong on the other.
 */
class WakeHeaderProbe(private val framesNeeded: Int = 100) {
    private val decoded = mutableMapOf(3 to 0, 4 to 0)
    private val energy = mutableMapOf(3 to 0.0, 4 to 0.0)
    private var seen = 0

    val done: Boolean get() = seen >= framesNeeded
    val framesSeen: Int get() = seen
    val decodeCounts: Map<Int, Int> get() = decoded.toMap()

    fun record(headerLen: Int, decoded: Boolean, rms: Double) {
        if (!this.decoded.containsKey(headerLen)) return
        if (decoded) {
            this.decoded[headerLen] = this.decoded[headerLen]!! + 1
            this.energy[headerLen] = this.energy[headerLen]!! + rms
        }
        if (headerLen == 4) seen++ // one tick per source frame
    }

    /** The offset that decoded more often (energy breaks a tie), or
     * [fallback] when neither produced anything usable. */
    fun verdict(fallback: Int): Int {
        val three = decoded[3]!!
        val four = decoded[4]!!
        if (three == 0 && four == 0) return fallback
        if (three == four) return if (energy[4]!! > energy[3]!!) 4 else 3
        return if (four > three) 4 else 3
    }
}

/** RMS of interleaved little-endian Int16 PCM, normalised to 0..1. */
fun rmsOf(samples: ShortArray): Double {
    if (samples.isEmpty()) return 0.0
    var sumSq = 0.0
    for (s in samples) {
        val v = s / 32767.0
        sumSq += v * v
    }
    val rms = sqrt(sumSq / samples.size)
    return rms.coerceIn(0.0, 1.0)
}
