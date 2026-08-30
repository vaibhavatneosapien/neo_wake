package xyz.neosapien.neo_wake

/**
 * Ported from `wake_word_service.dart`'s `PcmFramer` — repacks the pendant's
 * 160-sample fragments into the chain's fixed advance
 * ([WakeSpotter.ADVANCE_SAMPLES], 1280 @ 16 kHz / 80 ms), carrying the
 * remainder across calls. 160 divides 1280 exactly (8 fragments = 1
 * advance), so the carry is never mid-frame across an arm boundary.
 */
class WakePcmFramer(private val frameLength: Int) {
    private val carry = ShortArray(frameLength)
    private var len = 0

    /** Samples held back, waiting for the next call. */
    val pending: Int get() = len

    /**
     * Feeds [samples], invoking [onFrame] once per complete frame.
     *
     * [onFrame] receives its OWN copy, never the internal carry buffer —
     * mirrors the Dart original's guard against a caller holding a view
     * into a buffer this class is about to overwrite.
     */
    fun add(samples: ShortArray, onFrame: (ShortArray) -> Unit) {
        var offset = 0
        while (offset < samples.size) {
            val take = minOf(frameLength - len, samples.size - offset)
            samples.copyInto(carry, len, offset, offset + take)
            len += take
            offset += take
            if (len == frameLength) {
                onFrame(carry.copyOf())
                len = 0
            }
        }
    }

    fun reset() {
        len = 0
    }
}
