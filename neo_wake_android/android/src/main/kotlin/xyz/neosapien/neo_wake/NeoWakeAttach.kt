package xyz.neosapien.neo_wake

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The live wiring (U8): reads the KTD8 arm record, attaches to neo_ble's
 * native audio fan-out, and binds every seam U2/U3/U7/U9 already built —
 * WakeCodecPipeline (detect), WakeCommandCapture (pre-roll ring / clip /
 * ambient-suppression hooks), NeoBleAudioBridge (the cross-plugin hand-off,
 * see its own doc for why reflection over a Gradle dependency).
 *
 * Process-scoped and idempotent by design (KTD9): [bootstrap]/[attach] may
 * be called from the manifest [NeoWakeStartup] ContentProvider (every
 * process start, including a headless FGS restart) AND from a live Dart
 * `arm()` call — every path funnels through the SAME `@Synchronized` guard,
 * so a second/third call while already attached is a no-op rather than a
 * duplicate listener/session. (Fix 7: NOT also from neo_ble's own service —
 * neo_ble must never depend on neo_wake, so no such callback can exist; an
 * earlier version of this doc claimed otherwise.)
 */
object NeoWakeAttach {
    private const val TAG = "NeoWakeAttach"
    private const val LISTENER_KEY = "wake"

    /** Same marker [NeoBleAudioBridge.warn] uses (Fix 6) — a failed attach
     * greps alongside every other wake-lifecycle line, not a separate tag. */
    private const val WAKE_OBS_TAG = "[WAKE_OBS]"

    /** neo_ble's OWN SharedPreferences file/key for its persisted signed-in
     * uid (`NeoUploadConfig.kt`'s `PREFS_NAME`/`KEY_USER_ID`) — read by
     * CONVENTION, not by importing neo_ble's class (see
     * [NeoBleAudioBridge]'s doc for why this whole plugin stays
     * dependency-free on Android). This is the fail-closed owner check's
     * only source of "who is signed in right now", at both arm-time and
     * headless bootstrap-time. */
    private const val NEO_BLE_UPLOAD_CONFIG_PREFS = "neo_upload_config"
    private const val NEO_BLE_UPLOAD_CONFIG_USER_ID_KEY = "userId"

    /** A little past neo_wake's own capture ceiling — the self-healing
     * wall-clock backstop for a command window neo_wake itself never closes
     * (crash/jetsam before `onCaptureClosed` fires). Mirrors neo_ble's own
     * `NeoAudioUploaderConfig.AMBIENT_SUPPRESSION_MAX_MS` intent without
     * importing it. */
    private const val AMBIENT_SUPPRESSION_MAX_MS = 65_000L

    /** Safety margin added on top of preroll+lag when sizing neo_ble's
     * ambient delay buffer (U9 Fix 2 coupling) — covers dispatch jitter
     * between a wake-fire and the ambient suppress call landing. */
    private const val AMBIENT_DELAY_MARGIN_MS = 250

    @Volatile private var attached = false
    @Volatile private var appContext: Context? = null
    private var pipeline: WakeCodecPipeline? = null
    private var spotter: WakeSpotter? = null
    private var commandCapture: WakeCommandCapture? = null
    private var frameWorker: NeoWakeFrameWorker? = null
    private var configuredHeaderLenOverride: Int = 0

    /** Drives [WakeCommandCapture.tick] once/sec while attached — the
     * self-healing wall-clock ceiling for a command window neo_wake itself
     * never closes (no 2nd wake fire, no mic-stop, no disconnect). Fires on
     * its own thread; the scheduled body hands off to [frameWorker] rather
     * than calling `tick()` here, since capture state is only ever safe to
     * mutate on the worker's single thread. */
    @Volatile private var ceilingTimer: ScheduledExecutorService? = null

    val isAttached: Boolean get() = attached

    /** Native command-capture truth (command-mode UI parity plan, U1) — used
     * both as the `command_state` EventChannel's on-subscribe snapshot and
     * (U3) as the pull-model provider neo_ble's connect-ready reconcile
     * reads via [NeoBleAudioBridge.setCommandModeStateProvider]. `false`
     * (never CAPTURING) whenever nothing is attached, e.g. disarmed. */
    fun currentCommandMode(): Boolean = commandCapture?.state == WakeCaptureState.CAPTURING

    // neo_ble invokes this when the pendant mic stops while still connected
    // (sleep / double-tap stop). Force-close any live command capture — its
    // onCaptureClosed fires setCommandMode(false)+emitCommandState(false).
    // No-op if idle (onDisconnect guards CAPTURING).
    fun forceCommandCaptureClosedOnMicStop() {
        val capture = commandCapture
        capture?.onDisconnect(System.currentTimeMillis())
    }

    /** Reads neo_ble's persisted uid, or `null` on any failure — a failed
     * read is treated identically to "nobody signed in", never "trust the
     * record anyway". */
    private fun currentUidFromNeoBle(context: Context): String? = try {
        context.applicationContext
            .getSharedPreferences(NEO_BLE_UPLOAD_CONFIG_PREFS, Context.MODE_PRIVATE)
            .getString(NEO_BLE_UPLOAD_CONFIG_USER_ID_KEY, null)
            ?.takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        null
    }

    /**
     * Engine-independent bootstrap entry (KTD9) — called from
     * [NeoWakeStartup]'s ContentProvider `onCreate`, which is the actual
     * entry point on EVERY process start (including a headless FGS
     * restart), not a neo_ble callback: neo_ble must never depend on
     * neo_wake (acyclic), so no `NeoBleService.onCreate` hook into this
     * plugin can exist (Fix 7 — a prior version of this comment claimed
     * otherwise). Idempotent regardless: a second call while already
     * attached is a no-op (the guard below). Reads the KTD8 record; attaches
     * iff it fail-closed-resolves as armed for the CURRENT signed-in user.
     * No Flutter engine required or used.
     */
    @Synchronized
    fun bootstrap(context: Context) {
        // Fix 6 canary: runs on EVERY process start, armed or not, so a
        // reflection drift against neo_ble surfaces at first launch instead
        // of only once someone happens to arm and a frame needs the bridge.
        NeoBleAudioBridge.verifyBridgeAvailable()
        if (attached) return
        val record = NeoWakeArmStore.read(context).resolveArmed(currentUidFromNeoBle(context))
        if (record == null) {
            Log.i(TAG, "bootstrap: no fail-closed-resolved armed record — staying detached")
            return
        }
        // KTD13 (U8-harden, assessed — NOT wired, deliberately). Android
        // needs no replay buffer: this bootstrap runs from
        // [NeoWakeStartup]'s manifest `ContentProvider.onCreate`, which the
        // OS guarantees executes — synchronously, blocking process attach —
        // before `Application.onCreate()`, which itself runs before ANY
        // other component in the process, including the foreground service
        // that owns the BLE connection (`NeoBleService`) and the
        // `BootReceiver` that can headlessly start it after a reboot. By the
        // time this `attach()` call below can even begin registering the
        // "wake" listener, nothing in the process has had a chance to open a
        // BLE connection yet, let alone receive an audio-characteristic
        // notification — there is no gap between "process starts" and
        // "listener registered" for a frame to arrive into and be lost. See
        // the iOS twin (`NeoWakeReplayBuffer` in neo_ble) for the platform
        // that DOES need one, and why.
        //
        // R8 reboot policy (U8-harden, assessed — NOT changed). Unlike iOS,
        // Android's headless-reconnect gate
        // (`NeoConnectPolicy.shouldReviveHeadless`, driving `BootReceiver`)
        // already accepts a different-boot-session headless reconnect
        // unconditionally (gated only on: a remembered device, the user
        // hasn't paused reconnect, and the service isn't already running) —
        // there is no "was this a force-quit before the reboot" ambiguity to
        // resolve on this platform, because Android's revival contract never
        // encoded that distinction in the first place (a killed process
        // restarting via `BOOT_COMPLETED` looks identical to any other
        // automatic revival). So this bootstrap needs no reboot special-case
        // either: it already treats every process start identically, and
        // Android's R8 reboot survival already works end-to-end with no
        // change from this unit.
        attach(context, record)
    }

    /**
     * Live arm (U6 Dart facade -> [NeoWakePlugin.arm]). Persists the KTD8
     * record — stamping `ownerUid` from the SAME cross-plugin read
     * [bootstrap] re-checks against later — THEN attaches. Per KTD8, arming
     * from Dart only ever updates the record and (as a direct consequence)
     * starts the session; it is never itself what gates detection.
     */
    @Synchronized
    fun arm(context: Context, threshold: Double, lagMs: Int, modelVersion: String) {
        val uid = currentUidFromNeoBle(context)
        val record = NeoWakeArmRecord(
            armed = true,
            ownerUid = uid,
            modelVersion = modelVersion,
            threshold = threshold,
            lagMs = lagMs,
        )
        NeoWakeArmStore.persist(context, record)
        if (uid.isNullOrEmpty()) {
            // Fail closed HERE too, not only at the next headless bootstrap:
            // an unattributed record must never drive live detection either
            // — the record, not the caller's good intentions, is what gates.
            Log.w(
                TAG,
                "arm: no signed-in uid resolvable from neo_ble's persisted config " +
                    "— record stamped ownerless, staying detached until a uid is available",
            )
            detach()
            return
        }
        attach(context, record)
    }

    /** Live disarm (U6 Dart facade -> [NeoWakePlugin.disarm]). Clears the
     * KTD8 record and detaches — per KTD8, an auth change purges buffers and
     * queued mismatched audio, which [detach] achieves by dropping the whole
     * pipeline/capture/worker rather than trying to selectively flush them. */
    @Synchronized
    fun disarm(context: Context) {
        NeoWakeArmStore.clear(context)
        detach()
    }

    /** Test-only seam (Fix 1 test support): a plain JVM unit test cannot
     * load ORT's native library (see `NeoWakeSessionConfigTest`'s own note),
     * which would otherwise make [attach] bail before ever reaching the
     * registration-outcome logic under test. Real callers never touch this. */
    internal var sessionsInit: (Context) -> Unit = NeoWakeSessions::ensureInitialized

    /** `internal`, not `private` — same test-only reason as [sessionsInit]:
     * calling [attach] directly (bypassing [arm]/[bootstrap]'s
     * SharedPreferences-backed uid resolution, which a plain JVM test can't
     * exercise either) is what lets a test drive the registration-outcome
     * logic below without a Robolectric/Android-instrumented harness. */
    @Synchronized
    internal fun attach(context: Context, record: NeoWakeArmRecord) {
        if (attached) {
            // Keyed-replace / idempotency (KTD9): a second attach for an
            // already-live session is a no-op, not a rebuild — never
            // duplicate sessions/listeners on a second engine spawn.
            Log.i(TAG, "attach: already attached — idempotent no-op")
            return
        }
        val appCtx = context.applicationContext
        try {
            sessionsInit(appCtx)
        } catch (t: Throwable) {
            Log.w(TAG, "attach: ORT session init failed — staying detached", t)
            return
        }

        val codec = when (NeoBleAudioBridge.cachedCodecName()) {
            "PCM8" -> NeoWakeAudioCodec.PCM8
            // ponytail: default-to-Opus mirrors neo_ble's own behaviour when
            // its codec read fails/hasn't landed yet (docs/03-ble-protocol.md
            // §5) — Opus is also the near-universal real-world case.
            else -> NeoWakeAudioCodec.OPUS
        }
        // pcm8 has no header-length probe (WakeCodecPipeline's probe is
        // opus-only) — 3 matches v0.0.20 neo_ble's fixed header length; a
        // Neo2 4-byte pcm8 frame is out of scope here (Neo2 ships Opus).
        configuredHeaderLenOverride = if (codec == NeoWakeAudioCodec.PCM8) 3 else 0

        val newSpotter = WakeSpotter(
            threshold = record.threshold,
            mel = NeoWakeOrtHooks.melHook(),
            embed = NeoWakeOrtHooks.embedHook(),
            classify = NeoWakeOrtHooks.classifyHook(),
        )
        val newPipeline = WakeCodecPipeline(
            spotter = newSpotter,
            codec = codec,
            headerLenOverride = configuredHeaderLenOverride,
            decoderFactory = {
                NeoOpusDecoder.create() ?: error("NeoOpusDecoder.create() returned null (libopus rejected 16kHz/mono)")
            },
        )
        val captureConfig = WakeCommandCaptureConfig(lagMs = record.lagMs)
        // U8-harden / KTD11: journal the in-progress clip's audio to disk
        // (context.filesDir — same root neo_ble's own journals use, distinct
        // filenames) so a mid-command jetsam doesn't lose it.
        val newCapture = WakeCommandCapture(captureConfig, journal = WakeCommandClipJournalStore(appCtx))
        newCapture.onClipReady = { clip ->
            NeoBleAudioBridge.enqueueCommand(
                appCtx, clip.commandId, clip.source, clip.wakeEndMs,
                clip.durationMs, clip.closedAtMs, clip.audioBytes,
            )
        }
        newCapture.onCaptureOpened = { captureId ->
            NeoBleAudioBridge.setAmbientSuppressed(
                appCtx, true, captureId, System.currentTimeMillis() + AMBIENT_SUPPRESSION_MAX_MS,
            )
            NeoBleAudioBridge.setCommandMode(true)
            NeoWakePlugin.emitCommandState(true)
        }
        newCapture.onCaptureClosed = { _ ->
            NeoBleAudioBridge.setAmbientSuppressed(appCtx, false, "", 0L)
            NeoBleAudioBridge.setCommandMode(false)
            NeoWakePlugin.emitCommandState(false)
        }
        // opus-review fix: a resumed clip re-enters CAPTURING without a
        // fresh onCaptureOpened (see WakeCommandCapture.rehydrate's doc), so
        // the pendant LEDs — cleared by firmware on the jetsam disconnect —
        // never get re-lit unless we re-issue setCommandMode(true) here. No
        // ambient re-suppress: that deadline rehydrates independently on
        // neo_ble's own side.
        newCapture.onCaptureResumed = { _ ->
            NeoBleAudioBridge.setCommandMode(true)
            NeoWakePlugin.emitCommandState(true)
        }
        // Command-mode UI parity plan (U3): register neo_wake's own
        // command-capture truth as neo_ble's connect-ready reconcile
        // provider, so a reconnect self-heals the LED without neo_ble ever
        // importing neo_wake (PULL model). Registered here, after every
        // capture handler above is wired, so a reconcile firing the instant
        // it's readable never races an unset callback.
        NeoBleAudioBridge.setCommandModeStateProvider { currentCommandMode() }
        // Command-mode force-off plan: neo_ble invokes this when the
        // pendant's mic stops while the connection stays live (sleep or a
        // double-tap stop) — a WakeCommandCapture can't see either event on
        // its own, so this is the third closer alongside wake re-fire / 60s
        // ceiling / full disconnect.
        NeoBleAudioBridge.setMicStoppedHandler { forceCommandCaptureClosedOnMicStop() }
        // U8-harden / KTD11: rehydrate a mid-command clip left journaled by
        // a prior process (jetsam between openClip/closeClip). MUST run
        // after the handlers above are wired and BEFORE the audio listener
        // registers below — see the iOS twin's identical ordering note for
        // why.
        newCapture.rehydrate(System.currentTimeMillis())
        // U9 Fix 2 coupling: size neo_ble's ambient pre-roll delay from the
        // SAME config the capture's own pre-roll ring is sized from, instead
        // of neo_ble hand-syncing a constant with WakeCommandCaptureConfig.
        NeoBleAudioBridge.setAmbientDelayMs(
            appCtx,
            (captureConfig.prerollWindowMs + captureConfig.lagMs + AMBIENT_DELAY_MARGIN_MS).toLong(),
        )

        val worker = NeoWakeFrameWorker(
            onOverflow = { newSpotter.onFrameDropped() },
            process = { bytes -> onFrame(newPipeline, newCapture, bytes) },
        )

        // U4: fire-time wake-check (app-contract §10b). Only wake-phrase
        // captures reach this hook (it fires from openClip), so the check and
        // the abort are inherently wake-only — the button/hold path lives in
        // Dart and never touches this. Post the head slice off-thread (the mic
        // is never blocked), and on an explicit "no" stop command mode
        // immediately by aborting on the worker's serial queue (the id-guard in
        // abort makes a late verdict on a re-opened capture a no-op). isOpus is
        // true to match the clip upload (enqueueCommand also sends is_opus=true
        // for the same bytes).
        newCapture.onWakeCheckSlice = { slice ->
            NeoBleAudioBridge.checkWake(appCtx, slice.commandId, slice.wakeEndMs, true, slice.audioBytes) { isNo ->
                if (isNo) worker.submitTask { newCapture.abort(slice.commandId) }
            }
        }

        // Fix 1: registration is checked BEFORE anything latches `attached =
        // true`. A soft-failed listener (reflection drift, or neo_ble
        // genuinely absent) used to still flip the latch — sessions built,
        // `attached=true`, but no audio ever wired in, and the `if
        // (attached) return` guard above then blocked every later retry
        // (arm() again, next process bootstrap) forever. Now a failed
        // registration is a failed attach: nothing is kept, `attached` stays
        // false, and the NEXT arm()/bootstrap() call for real retries it.
        //
        // KTD2: this closure is neo_wake's O(1) hand-off off the BLE
        // callback thread — it does nothing but enqueue onto `worker`.
        val registered = NeoBleAudioBridge.addAudioListener(LISTENER_KEY) { bytes -> worker.submitFrame(bytes) }
        if (!registered) {
            Log.e(TAG, "$WAKE_OBS_TAG wake_attach_failed reason=listener_registration_failed codec=$codec")
            worker.shutdown()
            newPipeline.close()
            return
        }

        appContext = appCtx
        pipeline = newPipeline
        spotter = newSpotter
        commandCapture = newCapture
        frameWorker = worker
        attached = true

        // KTD2 ceiling: 60s wall-clock backstop for a command capture with
        // no 2nd wake fire / mic-stop / disconnect to close it. `tick()` is
        // cheap (guards state==CAPTURING + the deadline internally) so 1s is
        // fine; the hand-off through worker.submitTask keeps this off the
        // frame-processing thread's own serialization rules. Captures
        // `worker`/`newCapture` (the locals, not the shared fields) — same
        // reason the audio listener above does.
        ceilingTimer?.shutdownNow()
        ceilingTimer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "neo-wake-ceiling").apply { isDaemon = true }
        }.also { sched ->
            sched.scheduleWithFixedDelay({
                try {
                    worker.submitTask { newCapture.tick(System.currentTimeMillis()) }
                } catch (t: Throwable) {
                    // A throw here would cancel all future runs of
                    // scheduleWithFixedDelay — never let it escape.
                    Log.w(TAG, "ceiling timer tick failed", t)
                }
            }, 1000L, 1000L, TimeUnit.MILLISECONDS)
        }

        Log.i(TAG, "attach: wake listener registered=$registered codec=$codec headerLenOverride=$configuredHeaderLenOverride")
    }

    /** Runs on [frameWorker]'s own thread, never on the BLE callback thread. */
    private fun onFrame(pipeline: WakeCodecPipeline, capture: WakeCommandCapture, raw: ByteArray) {
        val nowMs = System.currentTimeMillis()
        // Feed the pre-roll ring / open clip with the SAME header-stripped
        // payload the uploader expects verbatim (flattenOpus re-frames these
        // raw bytes, never re-encoding) — both consumers see the identical
        // frame, per plan step 2.
        val headerLen = if (configuredHeaderLenOverride != 0) configuredHeaderLenOverride else pipeline.resolvedHeaderLen
        if (headerLen != null && raw.size > headerLen) {
            capture.feed(raw.copyOfRange(headerLen, raw.size), nowMs)
        }
        val steps = try {
            pipeline.onFragment(raw, nowMs)
        } catch (t: Throwable) {
            Log.w(TAG, "onFrame: pipeline threw", t)
            return
        }
        for (step in steps) {
            if (step.fired) onFire(capture, nowMs, step)
        }
    }

    private fun onFire(capture: WakeCommandCapture, nowMs: Long, step: WakeSpotterStep) {
        Log.i(TAG, "wake fired score=${step.score} step=${step.stepIndex}")
        // Drives WakeCommandCapture open/close (fires onCaptureOpened/onClipReady above).
        capture.onFire(nowMs)
        // Plan step 2: emit `fired` to Dart over the detections EventChannel
        // so U6's facade sees it in foreground — silently a no-op if no Dart
        // engine is listening (headless / backgrounded-without-UI).
        NeoWakePlugin.emitFired(mapOf("score" to (step.score ?: 0.0), "step_index" to step.stepIndex))
    }

    @Synchronized
    private fun detach() {
        ceilingTimer?.shutdownNow()
        ceilingTimer = null
        NeoBleAudioBridge.removeAudioListener(LISTENER_KEY)
        NeoBleAudioBridge.setCommandModeStateProvider(null)
        NeoBleAudioBridge.setMicStoppedHandler(null)
        frameWorker?.shutdown()
        frameWorker = null
        pipeline?.close()
        pipeline = null
        spotter = null
        commandCapture = null
        attached = false
    }

    /** Test-only — clears in-memory state without touching SharedPreferences
     * or neo_ble (there is nothing to reach in a JVM unit test). */
    internal fun resetForTest() {
        attached = false
        appContext = null
        pipeline = null
        spotter = null
        commandCapture = null
        frameWorker = null
        ceilingTimer?.shutdownNow()
        ceilingTimer = null
        configuredHeaderLenOverride = 0
        sessionsInit = NeoWakeSessions::ensureInitialized
    }
}
