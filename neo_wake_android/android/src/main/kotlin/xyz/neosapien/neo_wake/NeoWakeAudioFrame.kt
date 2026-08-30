package xyz.neosapien.neo_wake

/**
 * The two codecs the pendant negotiates (mirrors neo_ble's `NeoAudioCodec`:
 * wire `1`=pcm8, `20`=opus, cached at connect time, defaulting to Opus if
 * the read fails — see neo_ble docs/03-ble-protocol.md §5).
 */
enum class NeoWakeAudioCodec { OPUS, PCM8, UNKNOWN }

/**
 * One BLE audio fragment, decoded down to a typed value instead of loose
 * params — carries everything a decode/probe/discontinuity decision needs.
 *
 * [payloadOffset] is the header length ALREADY consumed by the caller
 * (3 bytes on v0.0.20 neo_ble; Neo2 firmware adds a 4th command-flag byte —
 * see [WakeHeaderProbe]), so [payload] below is codec bytes only, header
 * stripped.
 */
data class NeoWakeAudioFrame(
    /** Codec payload, header already stripped. */
    val payload: ByteArray,
    /** The header length that was stripped to produce [payload] (3 or 4). */
    val payloadOffset: Int,
    val codec: NeoWakeAudioCodec,
    val sampleRate: Int,
    val timestampMs: Long,
    /** True when this frame is known NOT to be adjacent to the previous one
     * (a queue eviction upstream, a decode failure just before it, or a
     * resumed stream) — informational for callers that log or gate on it;
     * [WakeCodecPipeline] itself decides ring resets from decode/framing
     * outcomes, not from this flag. */
    val discontinuity: Boolean = false,
)
