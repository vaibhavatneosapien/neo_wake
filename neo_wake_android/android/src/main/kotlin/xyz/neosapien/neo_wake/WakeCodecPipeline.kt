package xyz.neosapien.neo_wake

/**
 * U3's whole boundary: given a stream of raw seam frames (BLE fragment —
 * `[seq u16][reserved][+cmd flag on Neo2]` header, then Opus or PCM8
 * payload), produce the same score/fire timeline [WakeSpotter] produces for
 * the equivalent Dart chain. Ports `wake_word_service.dart`'s
 * `_onFragment`/`_runHeaderProbe`/`_feedEngine` trio into one class — the
 * pure header-probe ([WakeHeaderProbe]) and framer ([WakePcmFramer]) pieces
 * stay their own small types for the same testability reason the Dart file
 * keeps them `@visibleForTesting` top-level, not nested.
 *
 * No neo_ble wiring here (U5), no command-clip capture (U7), no Dart facade
 * (U6) — this takes bytes in and hands [WakeSpotterStep]s out.
 */
class WakeCodecPipeline(
    private val spotter: WakeSpotter,
    private val codec: NeoWakeAudioCodec,
    private val sampleRate: Int = 16000,
    private val samplesPerFrame: Int = 320, // 20 ms @ 16 kHz mono, one BLE fragment
    headerLenOverride: Int = 0, // 0 = probe at runtime
    probeFramesNeeded: Int = 100,
    private val decoderFactory: () -> OpusDecoding,
) {
    private val framer = WakePcmFramer(WakeSpotter.ADVANCE_SAMPLES)

    private var headerLen = headerLenOverride
    private var probe: WakeHeaderProbe? = if (headerLenOverride == 0) WakeHeaderProbe(probeFramesNeeded) else null
    private val probeDecoders = mutableMapOf<Int, OpusDecoding>()
    private val decoderLazy: Lazy<OpusDecoding> = lazy { decoderFactory() }
    private val decoder: OpusDecoding by decoderLazy

    /** Resolved header length once probing finishes (3 or 4), null while
     * still probing or on an override. */
    var resolvedHeaderLen: Int? = null
        private set

    // Counters mirroring the Dart service's health-tick fields — pure
    // state, no telemetry emission here (that is U5/wiring's job).
    var framesIn = 0
        private set
    var tooShortForHeader = 0
        private set
    var decodeFailed = 0
        private set

    /**
     * Feeds one raw BLE audio fragment through header resolution, decode,
     * re-framing to the chain's 1280-sample advance, and the spotter.
     * Returns zero or more [WakeSpotterStep]s — usually zero (still
     * buffering fragments toward a full advance) or one.
     *
     * [timestampMs] is accepted but not yet consumed — reserved for U5/U7
     * telemetry (matches [NeoWakeAudioFrame.timestampMs]'s same forward
     * surface); do not remove it to silence the unused-parameter warning.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onFragment(raw: ByteArray, timestampMs: Long = 0): List<WakeSpotterStep> {
        framesIn++

        val header = if (headerLen == 0) 4 else headerLen
        if (raw.size <= header) {
            tooShortForHeader++
            breakStream()
            return emptyList()
        }

        if (headerLen == 0) {
            // ponytail: pcm8 has no header-length probe -- the probe is
            // opus-only (it always attempts an Opus decode, see
            // runHeaderProbe's doc comment). U5 wires the negotiated codec +
            // header length in from neo_ble's `cachedCodec`; until then pcm8
            // only functions with an explicit headerLenOverride, never a
            // guessed one.
            if (codec == NeoWakeAudioCodec.PCM8) {
                decodeFailed++
                breakStream()
                return emptyList()
            }
            runHeaderProbe(raw)
            return emptyList()
        }

        val payload = raw.copyOfRange(headerLen, raw.size)
        val samples: ShortArray? = when (codec) {
            NeoWakeAudioCodec.PCM8 -> decodePcm8(payload)
            NeoWakeAudioCodec.OPUS, NeoWakeAudioCodec.UNKNOWN -> decoder.decode(payload, samplesPerFrame)
        }

        if (samples == null) {
            decodeFailed++
            breakStream()
            return emptyList()
        }

        return feedEngine(samples)
    }

    /** A drop is a discontinuity for the chain behind it, not a free slot —
     * see [WakeSpotter]'s file header (R18/KTD13). Every path that skips a
     * fragment routes through here. */
    private fun breakStream() {
        spotter.onFrameDropped()
    }

    private fun feedEngine(samples: ShortArray): List<WakeSpotterStep> {
        val results = mutableListOf<WakeSpotterStep>()
        framer.add(samples) { frame -> results.add(spotter.process(frame)) }
        return results
    }

    /**
     * While probing, decode the SAME fragment at both candidate offsets and
     * let the measurement pick — mirrors `wake_word_service.dart`'s
     * `_runHeaderProbe`, which always attempts an Opus decode: the Dart
     * reference has no pcm8 branch at all. Opus-only — [onFragment] routes
     * pcm8 away from this function before it is ever called (see its
     * `ponytail:` comment); guessing a header length for pcm8 would
     * byte-misalign every sample instead of just failing to fire.
     */
    private fun runHeaderProbe(frame: ByteArray) {
        assert(codec != NeoWakeAudioCodec.PCM8) { "pcm8 must never enter the opus-only header probe" }
        val probe = probe ?: return
        for (len in intArrayOf(3, 4)) {
            if (frame.size <= len) continue
            val dec = probeDecoders.getOrPut(len) { decoderFactory() }
            val payload = frame.copyOfRange(len, frame.size)
            val samples = dec.decode(payload, samplesPerFrame)
            probe.record(len, decoded = samples != null, rms = samples?.let { rmsOf(it) } ?: 0.0)
        }

        if (!probe.done) return

        disposeProbeDecoders()
        headerLen = probe.verdict(3)
        resolvedHeaderLen = headerLen
        this.probe = null
    }

    /** Closes every probe decoder's native handle (they are otherwise only
     * freed via [NeoOpusDecoder.finalize], i.e. whenever GC gets to it) and
     * clears the map. Fakes used in tests don't implement [AutoCloseable],
     * hence the safe cast. */
    private fun disposeProbeDecoders() {
        for (dec in probeDecoders.values) {
            (dec as? AutoCloseable)?.close()
        }
        probeDecoders.clear()
    }

    /** Frees the main lazy decoder's native handle plus any still-live probe
     * decoders, mirroring `wake_word_service.dart`'s `dispose()` /
     * `_disposeProbeDecoders()`. No neo_ble wiring calls this yet (U5's
     * job); exposed here so a disarm/disconnect path can. */
    // ponytail: U5 wires this into the disarm/disconnect path.
    fun close() {
        disposeProbeDecoders()
        // Guard against `by lazy` forcing initialization just to close
        // something that was never created (pcm8-only streams never touch
        // `decoder`).
        if (decoderLazy.isInitialized()) {
            (decoder as? AutoCloseable)?.close()
        }
    }
}
