package xyz.neosapien.neo_wake

import android.content.Context
import android.util.Log

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

    val isAttached: Boolean get() = attached

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
        // TODO(U8-harden): KTD13 — a bounded, sequence-aware replay buffer
        // installed in neo_ble BEFORE its BLE central/GATT callback comes up
        // would let a relaunch-triggering utterance (spoken before this
        // attach() below completes and the FIRST live frame arrives) still
        // reach the spotter, replayed once sessions are ready. Not wired —
        // this bootstrap only ever sees frames from the moment it attaches.
        // TODO(U8-harden): reboot restored-connected vs restored-disconnected
        // policy (R8) — this bootstrap treats every process start the same;
        // it does not distinguish a post-first-unlock reboot reconnect from
        // an ordinary headless resurrect, which the plan leaves open.
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
        val newCapture = WakeCommandCapture(captureConfig)
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
        }
        newCapture.onCaptureClosed = { _ ->
            NeoBleAudioBridge.setAmbientSuppressed(appCtx, false, "", 0L)
        }
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
        NeoBleAudioBridge.removeAudioListener(LISTENER_KEY)
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
        configuredHeaderLenOverride = 0
        sessionsInit = NeoWakeSessions::ensureInitialized
    }
}
