package xyz.neosapien.neo_wake

import java.nio.ByteBuffer
import java.nio.ByteOrder

// Native command-clip capture (U7 / KTD5) — mirrors the Dart capture
// machinery in `lib/core/neo_agent/wake_word_service.dart` (`_openClip`/
// `_closeClip`/`_discardClip`, the pre-roll ring, the toggle, the tail-trim,
// the wall-clock ceiling) so a wake fire assembles the SAME clip shape
// natively, kill-survival included once U8 wires this to live BLE audio and
// to `NeoAudioUploader`.
//
// DORMANT BY DESIGN — see the iOS twin (`WakeCommandCapture.swift`) for the
// full rationale, incl. why the cross-plugin hand-off (KTD9) is a plain
// callback (`onClipReady`) rather than a hard neo_wake_android ->
// neo_ble_android module dependency: neo_wake does not depend on neo_ble
// today (U5 only modified neo_ble's own fan-out registry), and this bounded
// task cannot Gradle-build a new cross-module dependency to verify it.
//
// U9 / KTD11 adds two more deferred-binding hooks on the SAME seam:
// `onCaptureOpened`/`onCaptureClosed`, fired on the capture's open/close
// (not the clip's — see their own docs). The live binding (U8) is
// `NeoAudioUploader.setAmbientSuppressed(true, id)` on open and
// `resumeAmbient()` on close — isolating command audio from neo_ble's
// ambient notes feed for the duration of one capture.

/** One assembled command clip, ready for the cross-plugin hand-off. */
data class WakeCommandClip(
    val commandId: String,
    val source: String,
    val audioBytes: ByteArray,
    val wakeEndMs: Int,
    val durationMs: Int,
    val closedAtMs: Long,
    val reason: String,
)

/** Contract `source` values (app-contract §2). */
object WakeCommandSource {
    const val WAKE_PHRASE = "wake_phrase"
    const val PENDANT_BUTTON = "pendant_button"
}

/** What the capture is doing right now — mirrors Dart's `WakePhase`. */
enum class WakeCaptureState { IDLE, CAPTURING }

/**
 * Configuration for one arm. Mirrors the Dart `--dart-define` defaults in
 * `wake_word_service.dart` exactly, as plain fields.
 */
data class WakeCommandCaptureConfig(
    val prerollWindowMs: Int = 1000,
    /** The classifier's fire-lag; the ring is sized `prerollWindowMs + lagMs`. */
    val lagMs: Int = 0,
    val tailTrimMs: Int = 1500,
    val maxClipMs: Int = 60_000,
    val minCommandMs: Int = 200,
    val frameMs: Int = 10,
) {
    fun framesFor(ms: Int): Int = maxOf(1, ms / frameMs)
}

// ---------------------------------------------------------------------------
// Pure helpers (unit-tested — no platform, no plugin state)
// ---------------------------------------------------------------------------

/** The pre-roll ring: the last [maxFrames] raw Opus payloads, never decoded/re-encoded. */
class WakePrerollRing(maxFrames: Int) {
    var maxFrames: Int = maxFrames
        private set
    private val frames = ArrayDeque<ByteArray>()

    val count: Int get() = frames.size

    fun add(frame: ByteArray) {
        frames.addLast(frame)
        while (frames.size > maxFrames) frames.removeFirst()
    }

    /** Grows or shrinks the ring live (the fire-lag can change without a relaunch). */
    fun resize(newMaxFrames: Int) {
        maxFrames = newMaxFrames
        while (frames.size > maxFrames) frames.removeFirst()
    }

    /** Hands the buffer to a clip and empties it. */
    fun drain(): List<ByteArray> {
        val out = frames.toList()
        frames.clear()
        return out
    }

    fun clear() = frames.clear()
}

/**
 * `[u32 BE len][opus frame]…` — the SAME framing `NeoAudioUploader`'s
 * ambient path expects for `is_bytes=true`, and what Dart's `flattenOpus`
 * produces for a command clip.
 */
fun flattenOpus(frames: List<ByteArray>): ByteArray {
    val total = frames.sumOf { 4 + it.size }
    val buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
    for (f in frames) {
        buf.putInt(f.size)
        buf.put(f)
    }
    return buf.array()
}

/** `wake_end_ms` per the app contract. Mirrors Dart's `wakeEndMsFromPreroll` exactly. */
fun wakeEndMsFromPreroll(prerollFrames: Int, lagMs: Int, frameMs: Int = 10): Int =
    maxOf(0, prerollFrames * frameMs - lagMs)

/** Mirrors Dart's `tailTrimCoversLag`. */
fun tailTrimCoversLag(tailTrimMs: Int, lagMs: Int, phraseMs: Int = 1000): Boolean =
    tailTrimMs > lagMs + phraseMs

/**
 * Mirrors Dart's `isLongEnoughToBeACommand` with `postRollFrames` fixed at
 * 0 — this capture unit only ever produces a wake-phrase toggle clip, never
 * a hold-to-talk one (that stays Dart-side, KD4).
 */
fun isLongEnoughToBeACommand(commandFrames: Int, minFrames: Int): Boolean =
    commandFrames >= minFrames

// ---------------------------------------------------------------------------
// The capture unit
// ---------------------------------------------------------------------------

/**
 * Native mirror of `WakeWordService`'s capture state machine, scoped to
 * JUST the wake-phrase toggle path (U7) — no hold-to-talk here.
 */
class WakeCommandCapture(private var config: WakeCommandCaptureConfig = WakeCommandCaptureConfig()) {

    private val ring = WakePrerollRing(config.framesFor(config.prerollWindowMs + config.lagMs))

    var state: WakeCaptureState = WakeCaptureState.IDLE
        private set
    private var clip: MutableList<ByteArray> = mutableListOf()
    private var clipPrerollFrames = 0
    private var clipOpenedAtMs: Long = 0
    private var clipCounter = 0
    private var captureCounter = 0
    private var currentCaptureId: String? = null

    /**
     * Delivered once per closed, non-discarded clip. Set by the
     * cross-plugin hand-off (see this file's header doc) — null is a
     * legitimate, expected state until U8 wires it.
     */
    var onClipReady: ((WakeCommandClip) -> Unit)? = null

    // ---- Ambient/command isolation seam (U9 / KTD11) ----------------------
    //
    // Deferred-binding hooks, same pattern as [onClipReady] — the LIVE
    // binding to neo_ble's `NeoAudioUploader.setAmbientSuppressed`/
    // `resumeAmbient` happens in U8. These fire on the CAPTURE lifecycle
    // (open/close), not the CLIP lifecycle: unlike [onClipReady] (which only
    // fires for a usable, non-discarded clip), [onCaptureClosed] fires on
    // EVERY close — including a too-short capture that gets discarded —
    // because the ambient feed must resume the instant the command WINDOW
    // ends, whether or not that window produced an uploadable clip.

    /**
     * Fired the instant a capture OPENS (a wake-fire while idle) — the
     * moment the ambient feed must start gating, with [captureId] as the
     * correlation id journaled alongside the suppression (see
     * `NeoAmbientSuppression` in neo_ble). Never fired for the SECOND fire
     * of a toggle (that's a close, not an open).
     */
    var onCaptureOpened: ((captureId: String) -> Unit)? = null

    /**
     * Fired the instant a capture CLOSES — via the toggling second fire,
     * the wall-clock ceiling ([tick]), or [onDisconnect] — regardless of
     * whether it produced a usable [WakeCommandClip]. This is the ambient
     * feed's resume signal; it must not wait on a clip actually existing.
     */
    var onCaptureClosed: ((captureId: String) -> Unit)? = null

    /** Re-applies a new lag (Remote Config equivalent) without a relaunch. */
    fun reconfigure(newConfig: WakeCommandCaptureConfig) {
        config = newConfig
        ring.resize(newConfig.framesFor(newConfig.prerollWindowMs + newConfig.lagMs))
    }

    /**
     * Buffers one raw Opus frame — into the ring while idle, into the open
     * clip while capturing. This is the "hook the pipeline calls per frame"
     * entry point; see this file's header doc for why it is unwired today.
     */
    fun feed(payload: ByteArray, nowMs: Long) {
        // TODO(U8-harden): mid-command clip JOURNAL — `clip` below is
        // RAM-only. A jetsam/kill between `openClip` and `closeClip` loses
        // every frame captured so far; incrementally journaling `clip` to
        // disk (mirroring `NeoAmbientSuppressionJournal`'s atomic-write
        // pattern) and rehydrating it on the next attach() would let a
        // mid-command kill still deliver a (partial) clip instead of none.
        if (state == WakeCaptureState.CAPTURING) {
            clip.add(payload)
        } else {
            ring.add(payload)
        }
    }

    /**
     * One wake-phrase fire. Idle -> opens (drains the pre-roll ring into
     * the clip). Capturing -> closes with the tail trimmed (the SAME
     * phrase toggles both edges — mirrors Dart's own `_onDetection`).
     */
    fun onFire(nowMs: Long, prerollFramesAtArrival: Int? = null): WakeCommandClip? {
        return if (state == WakeCaptureState.IDLE) {
            openClip(nowMs, prerollFramesAtArrival ?: ring.count)
            null
        } else {
            closeClip(nowMs, reason = "wake_word", trimTail = true)
        }
    }

    /**
     * Wall-clock ceiling check — call periodically (a live timer, U8) or
     * directly in a test. Closes an open capture that has run past
     * [WakeCommandCaptureConfig.maxClipMs] even with no second fire.
     */
    fun tick(nowMs: Long): WakeCommandClip? {
        if (state != WakeCaptureState.CAPTURING) return null
        if (nowMs - clipOpenedAtMs < config.maxClipMs) return null
        return closeClip(nowMs, reason = "ceiling", trimTail = false)
    }

    /**
     * The pendant stopped (disconnect / stopped recording) — finalizes
     * whatever was captured so far, NOT trimmed (mirrors Dart's
     * `_closeOnPendantStop`).
     */
    fun onDisconnect(nowMs: Long): WakeCommandClip? {
        ring.clear()
        if (state != WakeCaptureState.CAPTURING) return null
        return closeClip(nowMs, reason = "pendant_disconnected", trimTail = false)
    }

    private fun openClip(nowMs: Long, prerollFramesAtArrival: Int) {
        val pre = ring.drain()
        clip = pre.toMutableList()
        clipPrerollFrames = minOf(prerollFramesAtArrival, pre.size)
        clipOpenedAtMs = nowMs
        state = WakeCaptureState.CAPTURING
        val captureId = "cap-$nowMs-$captureCounter"
        captureCounter += 1
        currentCaptureId = captureId
        onCaptureOpened?.invoke(captureId)
    }

    private fun closeClip(nowMs: Long, reason: String, trimTail: Boolean): WakeCommandClip? {
        val captureId = currentCaptureId ?: "cap-$nowMs-unknown"
        var frames: List<ByteArray> = clip
        if (trimTail) {
            val trim = minOf(config.framesFor(config.tailTrimMs), frames.size)
            // ponytail: was `frames.subList(...)`, a live view backed by
            // `clip` — correct only because `resetCapture()` below
            // reassigns `clip` to a new list rather than clearing it in
            // place. `dropLast` snapshots instead, so the frames handed to
            // `flattenOpus` below can't be corrupted by a future
            // clear-in-place change to `clip`.
            frames = frames.dropLast(trim)
        }

        val prerollFrames = minOf(clipPrerollFrames, frames.size)
        val commandFrames = frames.size - prerollFrames

        resetCapture()
        // Fires on EVERY close, including a too-short/discarded capture
        // below — the ambient feed must resume the instant the command
        // WINDOW ends, not only when it produced an uploadable clip.
        onCaptureClosed?.invoke(captureId)

        if (!isLongEnoughToBeACommand(commandFrames, config.framesFor(config.minCommandMs))) {
            return null
        }

        val commandId = "cmd-$nowMs-$clipCounter"
        clipCounter += 1
        val body = flattenOpus(frames)
        val wakeEndMs = wakeEndMsFromPreroll(prerollFrames, config.lagMs, config.frameMs)
        val durationMs = frames.size * config.frameMs

        val result = WakeCommandClip(
            commandId = commandId,
            source = WakeCommandSource.WAKE_PHRASE,
            audioBytes = body,
            wakeEndMs = wakeEndMs,
            durationMs = durationMs,
            closedAtMs = nowMs,
            reason = reason,
        )
        onClipReady?.invoke(result)
        return result
    }

    private fun resetCapture() {
        clip = mutableListOf()
        clipPrerollFrames = 0
        clipOpenedAtMs = 0
        currentCaptureId = null
        ring.clear()
        state = WakeCaptureState.IDLE
    }
}
