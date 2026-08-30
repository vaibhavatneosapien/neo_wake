import Foundation

// Native command-clip capture (U7 / KTD5) — mirrors the Dart capture
// machinery in `lib/core/neo_agent/wake_word_service.dart` (`_openClip`/
// `_closeClip`/`_discardClip`, the pre-roll ring, the toggle, the tail-trim,
// the wall-clock ceiling) so a wake fire assembles the SAME clip shape
// natively, kill-survival included once U8 wires this to live BLE audio and
// to `NeoAudioUploader`.
//
// DORMANT BY DESIGN. `feed(_:nowMs:)` is the hook a live audio tap will call
// per frame (from `WakeCodecPipeline` or a parallel tap on the same seam —
// see U8), and `onFire`/`tick` are what a live `WakeSpotterStep.fired` and a
// periodic driver will call. Nothing in this plugin calls any of them yet —
// see `NeoWakePlugin`'s own scaffold doc. Unit-tested here with synthetic
// frames per plan U7.
//
// **The cross-plugin hand-off (KTD9).** `onClipReady` is a plain callback,
// not a hard neo_wake_ios -> neo_ble_ios pod dependency. neo_wake does not
// import any neo_ble type today (U5 only modified neo_ble's own fan-out
// registry); adding a real pod dependency here would need a Podfile
// static-linkage change this bounded task cannot `pod install`/build to
// verify. A registered-callback seam is also the pattern this plugin already
// uses for its own Flutter boundary (`NeoWakePlugin`'s `FlutterEventSink`),
// so U8 — which DOES wire live audio and can `pod install`/build for real —
// is the natural place to either bind this callback to
// `NeoAudioUploader.shared.enqueueCommand(...)` directly, or add the pod dep
// then if a tighter binding turns out to be warranted.
//
// U9 / KTD11 adds two more deferred-binding hooks on the SAME seam:
// `onCaptureOpened`/`onCaptureClosed`, fired on the capture's open/close
// (not the clip's — see their own docs). The live binding (U8) is
// `NeoAudioUploader.shared.setAmbientSuppressed(true, captureId:)` on open
// and `resumeAmbient()` on close — isolating command audio from neo_ble's
// ambient notes feed for the duration of one capture.

/// One assembled command clip, ready for the cross-plugin hand-off.
/// `audioBytes` is ALREADY FRAMED (`flattenOpus`), matching what
/// `NeoAudioUploader.enqueueCommand` expects verbatim — no re-encoding on
/// the receiving side.
public struct WakeCommandClip {
    public let commandId: String
    public let source: String
    public let audioBytes: [UInt8]
    public let wakeEndMs: Int
    public let durationMs: Int
    public let closedAtMs: Int64
    public let reason: String
}

/// Contract `source` values (app-contract §2) — mirrors Dart's
/// `_kSourceWake`/`_kSourceButton`.
public enum WakeCommandSource {
    public static let wakePhrase = "wake_phrase"
    public static let pendantButton = "pendant_button"
}

/// What the capture is doing right now. Mirrors Dart's `WakePhase` — two
/// states, no `armed`: nothing here gates capture on speech onset.
public enum WakeCaptureState {
    case idle
    case capturing
}

/// Configuration for one arm. Mirrors the Dart `--dart-define` defaults in
/// `wake_word_service.dart` exactly, as plain fields instead — this plugin
/// has no build-time flag mechanism of its own yet.
public struct WakeCommandCaptureConfig {
    public init(
        prerollWindowMs: Int = 1000,
        lagMs: Int = 0,
        tailTrimMs: Int = 1500,
        maxClipMs: Int = 60_000,
        minCommandMs: Int = 200,
        frameMs: Int = 10
    ) {
        self.prerollWindowMs = prerollWindowMs
        self.lagMs = lagMs
        self.tailTrimMs = tailTrimMs
        self.maxClipMs = maxClipMs
        self.minCommandMs = minCommandMs
        self.frameMs = frameMs
    }

    public var prerollWindowMs: Int
    /// The classifier's fire-lag (Remote Config on the Dart side; a plain
    /// field here). The ring is sized `prerollWindowMs + lagMs`.
    public var lagMs: Int
    public var tailTrimMs: Int
    public var maxClipMs: Int
    public var minCommandMs: Int
    public var frameMs: Int

    func framesFor(_ ms: Int) -> Int { max(1, ms / frameMs) }
}

// ---------------------------------------------------------------------------
// Pure helpers (unit-tested — no platform, no plugin state)
// ---------------------------------------------------------------------------

/// The pre-roll ring: the last `maxFrames` raw Opus payloads, uncompressed
/// only in the sense that they are NEVER decoded/re-encoded — same bytes in,
/// same bytes out. Mirrors Dart's `PrerollRing`.
public final class WakePrerollRing {
    public init(maxFrames: Int) {
        self.maxFrames = maxFrames
    }

    public private(set) var maxFrames: Int
    private var frames: [[UInt8]] = []

    public var count: Int { frames.count }

    public func add(_ frame: [UInt8]) {
        frames.append(frame)
        while frames.count > maxFrames {
            frames.removeFirst()
        }
    }

    /// Grows or shrinks the ring live (the fire-lag can change without a
    /// relaunch). A shrink trims immediately, same as `add`'s own eviction.
    public func resize(_ newMaxFrames: Int) {
        maxFrames = newMaxFrames
        while frames.count > maxFrames {
            frames.removeFirst()
        }
    }

    /// Hands the buffer to a clip and empties it.
    public func drain() -> [[UInt8]] {
        let out = frames
        frames = []
        return out
    }

    public func clear() { frames = [] }
}

/// `[u32 BE len][opus frame]…` — the SAME framing `NeoAudioUploader`'s
/// ambient path expects for `is_bytes=true`, and what Dart's `flattenOpus`
/// produces for a command clip.
public func flattenOpus(_ frames: [[UInt8]]) -> [UInt8] {
    var out: [UInt8] = []
    out.reserveCapacity(frames.reduce(0) { $0 + 4 + $1.count })
    for frame in frames {
        let len = UInt32(frame.count).bigEndian
        withUnsafeBytes(of: len) { out.append(contentsOf: $0) }
        out.append(contentsOf: frame)
    }
    return out
}

/// `wake_end_ms` per the app contract: first sample of the clip to the end
/// of the spoken phrase. Mirrors Dart's `wakeEndMsFromPreroll` exactly.
public func wakeEndMsFromPreroll(prerollFrames: Int, lagMs: Int, frameMs: Int = 10) -> Int {
    max(0, prerollFrames * frameMs - lagMs)
}

/// Whether `tailTrimMs` still swallows a closing "neo simsim" — must exceed
/// the classifier's fire-lag plus one spoken phrase. Mirrors Dart's
/// `tailTrimCoversLag`.
public func tailTrimCoversLag(tailTrimMs: Int, lagMs: Int, phraseMs: Int = 1000) -> Bool {
    tailTrimMs > lagMs + phraseMs
}

/// Whether the clip holds enough SPOKEN audio to be a command, excluding
/// post-roll (which this capture unit never produces — a wake capture has
/// no post-roll, only hold-to-talk does, and that stays Dart-side). Mirrors
/// Dart's `isLongEnoughToBeACommand` with `postRollFrames` fixed at 0.
public func isLongEnoughToBeACommand(commandFrames: Int, minFrames: Int) -> Bool {
    commandFrames >= minFrames
}

// ---------------------------------------------------------------------------
// The capture unit
// ---------------------------------------------------------------------------

/// Native mirror of `WakeWordService`'s capture state machine, scoped to
/// JUST the wake-phrase toggle path (U7) — no hold-to-talk here, that stays
/// Dart-side (KD4's fallback trigger needs no kill-survival: the phone is,
/// by definition, in the user's hand and the app alive while they hold a
/// button on its screen).
public final class WakeCommandCapture {
    public init(config: WakeCommandCaptureConfig = WakeCommandCaptureConfig()) {
        self.config = config
        self.ring = WakePrerollRing(maxFrames: config.framesFor(config.prerollWindowMs + config.lagMs))
    }

    private var config: WakeCommandCaptureConfig
    private let ring: WakePrerollRing

    public private(set) var state: WakeCaptureState = .idle
    private var clip: [[UInt8]] = []
    private var clipPrerollFrames = 0
    private var clipOpenedAtMs: Int64 = 0
    private var clipCounter = 0
    private var captureCounter = 0
    private var currentCaptureId: String?

    /// Delivered once per closed, non-discarded clip. Set by the cross-plugin
    /// hand-off (see this file's header doc) — nil is a legitimate, expected
    /// state until U8 wires it.
    public var onClipReady: ((WakeCommandClip) -> Void)?

    // MARK: - Ambient/command isolation seam (U9 / KTD11)
    //
    // Deferred-binding hooks, same pattern as `onClipReady` — the LIVE
    // binding to neo_ble's `NeoAudioUploader.setAmbientSuppressed`/
    // `resumeAmbient` happens in U8. These fire on the CAPTURE lifecycle
    // (open/close), not the CLIP lifecycle: unlike `onClipReady` (which only
    // fires for a usable, non-discarded clip), `onCaptureClosed` fires on
    // EVERY close — including a too-short capture that gets discarded —
    // because the ambient feed must resume the instant the command WINDOW
    // ends, whether or not that window produced an uploadable clip.

    /// Fired the instant a capture OPENS (a wake-fire while idle) — the
    /// moment the ambient feed must start gating, with `captureId` as the
    /// correlation id journaled alongside the suppression (see
    /// `NeoAmbientSuppression` in neo_ble). Never fired for the SECOND fire
    /// of a toggle (that's a close, not an open).
    public var onCaptureOpened: ((String) -> Void)?

    /// Fired the instant a capture CLOSES — via the toggling second fire,
    /// the wall-clock ceiling (`tick`), or `onDisconnect` — regardless of
    /// whether it produced a usable `WakeCommandClip`. This is the ambient
    /// feed's resume signal; it must not wait on a clip actually existing.
    public var onCaptureClosed: ((String) -> Void)?

    /// Re-applies a new lag (Remote Config equivalent) without a relaunch —
    /// mirrors Dart's live `PrerollRing.resize` on re-arm.
    public func reconfigure(_ newConfig: WakeCommandCaptureConfig) {
        config = newConfig
        ring.resize(newConfig.framesFor(newConfig.prerollWindowMs + newConfig.lagMs))
    }

    /// Buffers one raw Opus frame — into the ring while idle, into the open
    /// clip while capturing. This is the "hook the pipeline calls per frame"
    /// entry point; see this file's header doc for why it is unwired today.
    public func feed(_ payload: [UInt8], nowMs: Int64) {
        if state == .capturing {
            clip.append(payload)
        } else {
            ring.add(payload)
        }
    }

    /// One wake-phrase fire. Idle -> opens (drains the pre-roll ring into
    /// the clip). Capturing -> closes with the tail trimmed (the SAME
    /// phrase toggles both edges until an "end word" model exists — mirrors
    /// Dart's own `_onDetection`).
    @discardableResult
    public func onFire(nowMs: Int64, prerollFramesAtArrival: Int? = nil) -> WakeCommandClip? {
        if state == .idle {
            openClip(nowMs: nowMs, prerollFramesAtArrival: prerollFramesAtArrival ?? ring.count)
            return nil
        } else {
            return closeClip(nowMs: nowMs, reason: "wake_word", trimTail: true)
        }
    }

    /// Wall-clock ceiling check — call periodically (a live timer, U8) or
    /// directly in a test. Closes an open capture that has run past
    /// `maxClipMs` even with no second fire, so a disconnect still
    /// finalizes the window (mirrors Dart's `_ceiling` timer).
    @discardableResult
    public func tick(nowMs: Int64) -> WakeCommandClip? {
        guard state == .capturing else { return nil }
        guard nowMs - clipOpenedAtMs >= Int64(config.maxClipMs) else { return nil }
        return closeClip(nowMs: nowMs, reason: "ceiling", trimTail: false)
    }

    /// The pendant stopped (disconnect / stopped recording) — finalizes
    /// whatever was captured so far, NOT trimmed (mirrors Dart's
    /// `_closeOnPendantStop`: the device stopping is not the wake phrase,
    /// so there is no closing phrase to trim).
    @discardableResult
    public func onDisconnect(nowMs: Int64) -> WakeCommandClip? {
        ring.clear()
        guard state == .capturing else { return nil }
        return closeClip(nowMs: nowMs, reason: "pendant_disconnected", trimTail: false)
    }

    private func openClip(nowMs: Int64, prerollFramesAtArrival: Int) {
        let pre = ring.drain()
        clip = pre
        clipPrerollFrames = min(prerollFramesAtArrival, pre.count)
        clipOpenedAtMs = nowMs
        state = .capturing
        let captureId = "cap-\(nowMs)-\(captureCounter)"
        captureCounter += 1
        currentCaptureId = captureId
        onCaptureOpened?(captureId)
    }

    private func closeClip(nowMs: Int64, reason: String, trimTail: Bool) -> WakeCommandClip? {
        let captureId = currentCaptureId ?? "cap-\(nowMs)-unknown"
        var frames = clip
        if trimTail {
            let trim = min(config.framesFor(config.tailTrimMs), frames.count)
            frames.removeLast(trim)
        }

        let prerollFrames = min(clipPrerollFrames, frames.count)
        let commandFrames = frames.count - prerollFrames

        resetCapture()
        // Fires on EVERY close, including a too-short/discarded capture
        // below — the ambient feed must resume the instant the command
        // WINDOW ends, not only when it produced an uploadable clip.
        onCaptureClosed?(captureId)

        guard isLongEnoughToBeACommand(commandFrames: commandFrames, minFrames: config.framesFor(config.minCommandMs)) else {
            return nil
        }

        let commandId = "cmd-\(nowMs)-\(clipCounter)"
        clipCounter += 1
        let body = flattenOpus(frames)
        let wakeEndMs = wakeEndMsFromPreroll(prerollFrames: prerollFrames, lagMs: config.lagMs, frameMs: config.frameMs)
        let durationMs = frames.count * config.frameMs

        let result = WakeCommandClip(
            commandId: commandId,
            source: WakeCommandSource.wakePhrase,
            audioBytes: body,
            wakeEndMs: wakeEndMs,
            durationMs: durationMs,
            closedAtMs: nowMs,
            reason: reason
        )
        onClipReady?(result)
        return result
    }

    private func resetCapture() {
        clip = []
        clipPrerollFrames = 0
        clipOpenedAtMs = 0
        currentCaptureId = nil
        ring.clear()
        state = .idle
    }
}
