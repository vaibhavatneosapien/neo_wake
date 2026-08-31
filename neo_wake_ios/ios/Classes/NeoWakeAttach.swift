import Foundation
import neo_ble_ios

/// The live wiring (U8): reads the KTD8 arm record, attaches to neo_ble's
/// native audio fan-out, and binds every seam U2/U3/U7/U9 already built —
/// `WakeCodecPipeline` (detect), `WakeCommandCapture` (pre-roll ring / clip /
/// ambient-suppression hooks), `NeoAudioUploader` (U7/U9's already-public
/// hand-off methods).
///
/// **Cross-plugin attach mechanism (KTD9): a real pod dependency**, not a
/// service locator. `neo_wake_ios.podspec` now depends on `neo_ble_ios`
/// (see its own comment for why), and `BleEventSinks.shared`/
/// `addAudioListener`/`removeAudioListener` were widened to `public` in
/// neo_ble_ios for exactly this consumer — the only neo_ble_ios change this
/// unit makes. Swift has no practical reflection-based alternative to a real
/// import the way Kotlin's `Class.forName`/`Method.invoke` lets the Android
/// side stay Gradle-dependency-free (see `NeoBleAudioBridge.kt`'s doc for
/// that asymmetry's justification) — dynamic dispatch of a Swift closure
/// parameter through the ObjC runtime isn't a realistic option here. This
/// pod dependency is PARSE-LEVEL ONLY in this bounded task: no `pod
/// install`/device build was run to confirm it links — see the U8 task
/// report.
///
/// Process-scoped and idempotent by design (KTD9): [bootstrap]/[attach] may
/// be called from the app's `didFinishLaunching` hook AND from a live Dart
/// `arm()` call — both funnel through the same guard, so a second call while
/// already attached is a no-op rather than a duplicate listener/session.
public enum NeoWakeAttach {
    private static let listenerKey = "wake"

    /// neo_ble's OWN UserDefaults key for its persisted signed-in uid
    /// (`NeoUploadConfig.swift`'s `Keys.userId`) — read by CONVENTION, not
    /// by importing `NeoUploadConfig` (which stays `internal` in neo_ble_ios;
    /// widening it is out of this unit's minimal cross-repo footprint, see
    /// the task report). This is the fail-closed owner check's only source
    /// of "who is signed in right now", at both arm-time and headless
    /// bootstrap-time.
    private static let neoBleUploadConfigUserIdKey = "neo.uploadcfg.userId"

    /// A little past neo_wake's own capture ceiling — mirrors neo_ble's own
    /// `NeoAudioUploaderConfig.ambientSuppressionMaxMs` intent without
    /// importing it (that constant is also internal).
    private static let ambientSuppressionMaxMs: Int64 = 65_000

    /// Safety margin on top of preroll+lag when sizing neo_ble's ambient
    /// delay buffer (U9 Fix 2 coupling).
    private static let ambientDelayMarginMs = 250

    /// U8-harden / KTD11: directory for `WakeCommandClipJournalStore`.
    /// Application Support — same root neo_ble's `NeoAmbientSuppressionJournal`
    /// uses; distinct filenames, no coupling needed for that to be safe.
    private static var clipJournalDir: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
    }

    private static let lock = NSLock()
    private static var attached = false
    /// Fix 4: held across the ENTIRE span from the check to `attached = true`
    /// — `ensureInitialized()` + pipeline/session build below runs with the
    /// lock released (it can take real time: ORT session load, decoder
    /// init), so `attached` alone isn't enough to stop a second concurrent
    /// caller starting a second build in that window. Both callers
    /// (`didFinishLaunching`, a live Dart `arm()`) are main-thread-only
    /// today, which is what has made that window safe in practice — this
    /// flag is the real guard, not a reliance on that assumption holding.
    private static var initInProgress = false
    private static var pipeline: WakeCodecPipeline?
    private static var spotter: WakeSpotter?
    private static var commandCapture: WakeCommandCapture?
    private static var frameWorker: NeoWakeFrameWorker?
    private static var configuredHeaderLenOverride = 0
    /// 60s wall-clock ceiling driver: fires every 1s on its own thread and
    /// hands `WakeCommandCapture.tick()` onto `frameWorker`'s serial queue
    /// (via `submitTask`) so a capture with no 2nd wake fire / mic-stop /
    /// disconnect still force-closes and uploads. `tick()` is a cheap no-op
    /// unless a capture is open and past its deadline.
    private static var ceilingTimer: DispatchSourceTimer?

    public static var isAttached: Bool { lock.lock(); defer { lock.unlock() }; return attached }

    /// Native command-state truth (command-mode UI parity plan, U2/U4):
    /// ON iff a capture is actually open right now. Used both as the
    /// snapshot posted to a fresh Dart `command_state` subscriber and as the
    /// PULL closure neo_ble's connect-ready reconcile reads.
    public static func currentCommandMode() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return commandCapture?.state == .capturing
    }

    /// neo_ble invokes this when the pendant mic stops while still connected
    /// (sleep / double-tap stop). Force-close any live command capture — its
    /// onCaptureClosed fires setCommandMode(false)+emitCommandState(false),
    /// clearing the UI. No-op if idle (onDisconnect guards state==.capturing).
    static func forceCommandCaptureClosedOnMicStop() {
        lock.lock()
        let capture = commandCapture
        lock.unlock()
        capture?.onDisconnect(nowMs: Int64(Date().timeIntervalSince1970 * 1000))
    }

    private static func currentUidFromNeoBle() -> String? {
        let uid = UserDefaults.standard.string(forKey: neoBleUploadConfigUserIdKey)
        return (uid?.isEmpty == false) ? uid : nil
    }

    /// Engine-independent bootstrap entry (KTD9) — called from the app's
    /// `AppDelegate.application(_:didFinishLaunchingWithOptions:)`, BEFORE
    /// `super.application(...)` triggers `GeneratedPluginRegistrant`
    /// (which is what brings up neo_ble's restoration-aware
    /// `CBCentralManager` via `NeoBleManager.shared`) — see the app-repo
    /// wiring. Reads the KTD8 record; attaches iff it fail-closed-resolves
    /// as armed for the CURRENT signed-in user. No Flutter engine required
    /// or used.
    public static func bootstrap() {
        lock.lock()
        let alreadyAttached = attached
        lock.unlock()
        guard !alreadyAttached else { return }
        guard let record = resolveArmed(NeoWakeArmStore.read(), currentUid: currentUidFromNeoBle()) else {
            NSLog("[NeoWakeAttach] %@", "bootstrap: no fail-closed-resolved armed record — staying detached")
            return
        }
        // KTD13 (U8-harden, resolved): the bounded, sequence-aware replay
        // buffer now lives in neo_ble (`NeoWakeReplayBuffer`, armed from the
        // very first line of `NeoBleManager.init()` — i.e. before its
        // restoration-aware `CBCentralManager` is constructed). `attach()`
        // below calls `BleEventSinks.shared.replayBufferedFrames(toListenerKey:)`
        // right after registering the "wake" listener, which drains
        // whatever arrived during model-load/warm-up into that listener,
        // in sequence order, once — see both files' headers for the
        // no-double-deliver reasoning.
        //
        // R8 reboot policy (U8-harden, assessed — NOT changed here): the
        // gate that decides whether a headless relaunch reconnects lives in
        // neo_ble (`NeoBleManager.connectivityAllowed` /
        // `mayServiceLinkHeadless` — see its doc for the full assessment).
        // This bootstrap doesn't need its own reboot special-case: it
        // already treats every process start identically (read the KTD8
        // record, attach if fail-closed-armed) — whether wake actually SEES
        // any audio afterward is entirely gated by neo_ble's own reconnect
        // decision, which deliberately still refuses a different-boot-
        // session headless reconnect (recommendation: narrow R8 to exclude
        // reboot on iOS, rather than loosen that gate).
        attach(record: record)
    }

    /// Live arm (U6 Dart facade -> `NeoWakePlugin.arm`). Persists the KTD8
    /// record — stamping `ownerUid` from the SAME cross-plugin read
    /// [bootstrap] re-checks against later — THEN attaches.
    public static func arm(threshold: Double, lagMs: Int, modelVersion: String) {
        let uid = currentUidFromNeoBle()
        let record = NeoWakeArmRecord(
            armed: true, ownerUid: uid, modelVersion: modelVersion, threshold: threshold, lagMs: lagMs
        )
        NeoWakeArmStore.persist(record)
        guard let uid, !uid.isEmpty else {
            NeoLog.w("NeoWakeAttach", "arm: no signed-in uid resolvable — record stamped ownerless, staying detached", metadata: [:])
            detach()
            return
        }
        attach(record: record)
    }

    /// Live disarm (U6 Dart facade -> `NeoWakePlugin.disarm`). Clears the
    /// KTD8 record and detaches — an auth change purges buffers/queued
    /// mismatched audio by dropping the whole pipeline/capture/worker.
    public static func disarm() {
        NeoWakeArmStore.clear()
        detach()
    }

    private static func attach(record: NeoWakeArmRecord) {
        lock.lock()
        if attached || initInProgress {
            lock.unlock()
            NSLog("[NeoWakeAttach] %@", "attach: already attached or already in progress — idempotent no-op")
            return
        }
        initInProgress = true
        lock.unlock()
        defer {
            lock.lock()
            initInProgress = false
            lock.unlock()
        }

        do {
            try NeoWakeSessions.shared.ensureInitialized()
        } catch {
            NeoLog.w("NeoWakeAttach", "attach: ORT session init failed — staying detached: \(error)", metadata: [:])
            return
        }

        // ponytail: codec defaults to Opus (neo_ble's own on-read-failure
        // default, and the near-universal real case) — reading neo_ble's
        // real negotiated codec would need `NeoAudioCodec`/`NeoBleManager`
        // widened to public too; out of this unit's minimal cross-repo
        // footprint (only `BleEventSinks` was widened). See the Android
        // twin for the same simplification.
        configuredHeaderLenOverride = 0 // 0 = probe (Opus path)

        let newSpotter = WakeSpotter(
            threshold: record.threshold,
            mel: NeoWakeOrtHooks.melHook(),
            embed: NeoWakeOrtHooks.embedHook(),
            classify: NeoWakeOrtHooks.classifyHook()
        )
        let newPipeline = WakeCodecPipeline(
            spotter: newSpotter,
            codec: .opus,
            headerLenOverride: configuredHeaderLenOverride,
            decoderFactory: {
                // Force-unwrap mirrors the Android twin's `error(...)` on a
                // nil create — both are DEVICE-GATED paths (native libopus),
                // never reached in a host-side unit test.
                NeoOpusDecoder()!
            }
        )
        var captureConfig = WakeCommandCaptureConfig()
        captureConfig.lagMs = record.lagMs
        // U8-harden / KTD11: journal the in-progress clip's audio to disk
        // (Application Support — same root neo_ble's own journals use,
        // distinct filenames) so a mid-command jetsam doesn't lose it.
        let newCapture = WakeCommandCapture(config: captureConfig, journal: WakeCommandClipJournalStore(dir: clipJournalDir))
        newCapture.onClipReady = { clip in
            NeoAudioUploader.shared.enqueueCommand(
                commandId: clip.commandId,
                source: clip.source,
                wakeEndMs: clip.wakeEndMs,
                durationMs: clip.durationMs,
                closedAtMs: clip.closedAtMs,
                audioBytes: Data(clip.audioBytes)
            )
        }
        newCapture.onCaptureOpened = { captureId in
            let deadline = Int64(Date().timeIntervalSince1970 * 1000) + ambientSuppressionMaxMs
            NeoAudioUploader.shared.setAmbientSuppressed(true, captureId: captureId, deadlineMs: deadline)
            NeoBleManager.shared.setCommandMode(true)
            NeoWakePlugin.emitCommandState(true)
        }
        newCapture.onCaptureClosed = { _ in
            NeoAudioUploader.shared.resumeAmbient()
            NeoBleManager.shared.setCommandMode(false)
            NeoWakePlugin.emitCommandState(false)
        }
        // Jetsam-survived resume: firmware cleared the LEDs on the jetsam
        // disconnect, so re-issue command-mode ONLY — no ambient re-suppress
        // (that's neo_ble's own journal's job, see `onCaptureResumed`'s doc).
        newCapture.onCaptureResumed = { _ in
            NeoBleManager.shared.setCommandMode(true)
            NeoWakePlugin.emitCommandState(true)
        }
        // U8-harden / KTD11: rehydrate a mid-command clip left journaled by
        // a prior process (jetsam between openClip/closeClip). MUST run
        // after the three handlers above are wired (a rehydrate-expired
        // finalize fires onCaptureClosed/onClipReady same as a live close)
        // and BEFORE the audio listener registers below (a resumed capture
        // must see the very next live frame as a continuation, not a fresh
        // idle ring fill).
        newCapture.rehydrate(nowMs: Int64(Date().timeIntervalSince1970 * 1000))
        // U9 Fix 2 coupling: size neo_ble's ambient pre-roll delay from the
        // SAME config the capture's own pre-roll ring is sized from.
        NeoAudioUploader.shared.setAmbientDelayMs(captureConfig.prerollWindowMs + captureConfig.lagMs + ambientDelayMarginMs)

        let worker = NeoWakeFrameWorker(
            onOverflow: { newSpotter.onFrameDropped() },
            process: { bytes in onFrame(pipeline: newPipeline, capture: newCapture, raw: bytes) }
        )

        // 60s wall-clock ceiling (KTD2's tick() had no production caller):
        // drive it every 1s, hopping onto the worker's serial queue so it
        // never races feed()/onFire(). Capture `worker`/`newCapture` locals,
        // not the static vars, so the timer never touches state off-queue.
        let ceiling = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        ceiling.schedule(deadline: .now() + 1.0, repeating: 1.0)
        ceiling.setEventHandler {
            worker.submitTask {
                newCapture.tick(nowMs: Int64(Date().timeIntervalSince1970 * 1000))
            }
        }

        lock.lock()
        pipeline = newPipeline
        spotter = newSpotter
        commandCapture = newCapture
        frameWorker = worker
        // ceilingTimer is read + cancelled under `lock` in detach(), so publish
        // it here under the SAME lock (cancel any stale prior first) — never
        // outside it, or a concurrent detach could miss it and leak the timer.
        ceilingTimer?.cancel()
        ceilingTimer = ceiling
        attached = true
        lock.unlock()
        ceiling.resume()

        // KTD2: this closure is neo_wake's O(1) hand-off off the BLE
        // callback thread — it does nothing but enqueue onto `worker`.
        BleEventSinks.shared.addAudioListener(key: listenerKey) { data in
            worker.submitFrame(data)
        }
        // KTD13 (U8-harden): "sessions ready" — drain neo_ble's bounded
        // pre-attach replay buffer straight into the listener just
        // registered above, in sequence order, once. Must come AFTER
        // addAudioListener (there must be a live listener to replay into)
        // and this is the natural earliest point: pipeline/spotter/capture/
        // worker are all built and `attached` is already latched by here.
        BleEventSinks.shared.replayBufferedFrames(toListenerKey: listenerKey)
        // Command-mode UI parity plan (U4): let neo_ble PULL our native
        // command-state truth on its own connect-ready reconcile, so a
        // reconnect self-heals the firmware LED without neo_ble depending on
        // neo_wake. `NeoWakeAttach` is a static namespace (no instance), so
        // there's no `self` to weak-capture here. Cleared in `detach()`.
        NeoBleManager.shared.commandModeStateProvider = { currentCommandMode() }
        NeoBleManager.shared.micStoppedWhileConnectedHandler = { forceCommandCaptureClosedOnMicStop() }
        NSLog("[NeoWakeAttach] %@", "attach: wake listener registered codec=opus headerLenOverride=\(configuredHeaderLenOverride)")
    }

    /// Runs on `frameWorker`'s own serial queue, never on the BLE callback thread.
    private static func onFrame(pipeline: WakeCodecPipeline, capture: WakeCommandCapture, raw: Data) {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let bytes = [UInt8](raw)
        // Feed the pre-roll ring / open clip with the SAME header-stripped
        // payload the uploader expects verbatim (plan step 2) — both
        // consumers see the identical frame.
        let headerLen = configuredHeaderLenOverride != 0 ? configuredHeaderLenOverride : pipeline.resolvedHeaderLen
        if let headerLen, bytes.count > headerLen {
            capture.feed(Array(bytes[headerLen...]), nowMs: nowMs)
        }
        let steps: [WakeSpotterStep]
        do {
            steps = try pipeline.onFragment(bytes, timestampMs: nowMs)
        } catch {
            NeoLog.w("NeoWakeAttach", "onFrame: pipeline threw: \(error)", metadata: [:])
            return
        }
        for step in steps where step.fired {
            onFire(capture: capture, nowMs: nowMs, step: step)
        }
    }

    private static func onFire(capture: WakeCommandCapture, nowMs: Int64, step: WakeSpotterStep) {
        NSLog("[NeoWakeAttach] %@", "wake fired score=\(step.score ?? 0) step=\(step.stepIndex)")
        // Drives WakeCommandCapture open/close (fires onCaptureOpened/onClipReady above).
        capture.onFire(nowMs: nowMs)
        // Plan step 2: emit `fired` to Dart over the detections EventChannel
        // — a no-op if no Dart engine is listening (headless / backgrounded).
        NeoWakePlugin.emitFired(["score": step.score ?? 0, "step_index": step.stepIndex])
    }

    private static func detach() {
        lock.lock()
        defer { lock.unlock() }
        ceilingTimer?.cancel()
        ceilingTimer = nil
        BleEventSinks.shared.removeAudioListener(key: listenerKey)
        NeoBleManager.shared.commandModeStateProvider = nil
        NeoBleManager.shared.micStoppedWhileConnectedHandler = nil
        frameWorker?.shutdown()
        frameWorker = nil
        pipeline = nil
        spotter = nil
        commandCapture = nil
        attached = false
    }

    /// Test-only — clears in-memory state without touching UserDefaults or
    /// neo_ble.
    static func resetForTest() {
        lock.lock()
        defer { lock.unlock() }
        attached = false
        initInProgress = false
        pipeline = nil
        spotter = nil
        commandCapture = nil
        frameWorker = nil
        configuredHeaderLenOverride = 0
    }
}
