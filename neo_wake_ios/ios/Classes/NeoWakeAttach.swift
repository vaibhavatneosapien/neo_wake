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

    public static var isAttached: Bool { lock.lock(); defer { lock.unlock() }; return attached }

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
        // TODO(U8-harden): KTD13 — a bounded, sequence-aware replay buffer
        // installed in neo_ble BEFORE `NeoBleManager`'s restoration-aware
        // `CBCentralManager` comes up would let the relaunch-triggering
        // utterance (often "Neo" itself — see AE6) reach the spotter once
        // this attach() below finishes model load/decoder init/warm-up,
        // instead of being missed entirely. Not wired.
        // TODO(U8-harden): reboot restored-connected vs restored-disconnected
        // policy (R8) — this bootstrap treats every didFinishLaunching call
        // identically; it does not special-case a post-first-unlock reboot.
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
        let newCapture = WakeCommandCapture(config: captureConfig)
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
        }
        newCapture.onCaptureClosed = { _ in
            NeoAudioUploader.shared.resumeAmbient()
        }
        // U9 Fix 2 coupling: size neo_ble's ambient pre-roll delay from the
        // SAME config the capture's own pre-roll ring is sized from.
        NeoAudioUploader.shared.setAmbientDelayMs(captureConfig.prerollWindowMs + captureConfig.lagMs + ambientDelayMarginMs)

        let worker = NeoWakeFrameWorker(
            onOverflow: { newSpotter.onFrameDropped() },
            process: { bytes in onFrame(pipeline: newPipeline, capture: newCapture, raw: bytes) }
        )

        lock.lock()
        pipeline = newPipeline
        spotter = newSpotter
        commandCapture = newCapture
        frameWorker = worker
        attached = true
        lock.unlock()

        // KTD2: this closure is neo_wake's O(1) hand-off off the BLE
        // callback thread — it does nothing but enqueue onto `worker`.
        BleEventSinks.shared.addAudioListener(key: listenerKey) { data in
            worker.submitFrame(data)
        }
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
        BleEventSinks.shared.removeAudioListener(key: listenerKey)
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
