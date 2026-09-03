package xyz.neosapien.neo_wake

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Cross-plugin attach seam to neo_ble, Android side (U8 / KTD9).
 *
 * **Why reflection, not a Gradle module dependency.** KTD9 calls for "a
 * defined cross-plugin observer API and dependency direction" from neo_wake
 * to neo_ble, and the iOS side gets that literally (`neo_wake_ios.podspec`
 * now depends on `neo_ble_ios`, see `NeoWakeAttach.swift`). On Android the
 * equivalent — `implementation project(':neo_ble_android')` — only resolves
 * inside the HOST APP's build, where Flutter's generated
 * `include_flutter.groovy` wires every federated plugin as a sibling
 * subproject of one root. This module's own verification contract is a
 * STANDALONE `gradlew -p neo_wake_android/android test` with no such root —
 * declaring that dependency would either break standalone compilation
 * outright (Kotlin compiles a whole source set as one unit; an unresolved
 * import fails every test in the module, not just the file that used it) or
 * require injecting a fragile, path-guessing composite-build `settings.gradle`
 * that risks colliding with the real app's own Flutter Gradle wiring the one
 * time it matters. Reflection keeps the module dependency-free and
 * `gradle test` green, while still reaching the real, already-shipped
 * `BleEventSinks`/`NeoAudioUploader` API at RUNTIME — both plugins are always
 * co-installed in the host app, so the classes are guaranteed present there.
 *
 * Every call is wrapped and fails soft: a missing class/method (API drift,
 * or neo_ble genuinely absent) logs and no-ops rather than crashing the host
 * app's BLE service. This is the plugin's one deliberately-untestable-here
 * integration seam — everything it calls into lives in a sibling repo this
 * bounded task cannot Gradle-link for a real run; see the U8 task report for
 * why.
 *
 * // ponytail: reflection (not a Gradle dep) is the accepted ceiling here —
 * a signature drift in neo_ble fails soft instead of at compile time. [warn]
 * now routes every failure through the same `[WAKE_OBS]` tag the Dart side's
 * `wakeObs` stamps at ERROR, so drift is diagnosable from the normal wake
 * logs (Fix 6) — the mitigation for that ceiling, not a removal of it.
 * Upgrade path if this ever bites for real: a `compileOnly` dependency on
 * neo_ble_android in this module's build.gradle — compile-time signature
 * checking with no *runtime* Gradle coupling (the host app still supplies
 * the real class), unlike the full `implementation project(...)` dependency
 * ruled out above.
 */
internal object NeoBleAudioBridge {
    private const val TAG = "NeoBleAudioBridge"

    /** Same marker the Dart side's `wakeObs` stamps on every wake-lifecycle
     * line (`lib/core/utils/helpers/logger.dart`) — native failures use the
     * identical tag so a drifted reflection lookup shows up in the same grep
     * as everything else wake-related, not a Kotlin-only tag no one greps
     * for (Fix 6). */
    private const val WAKE_OBS_TAG = "[WAKE_OBS]"

    private const val EVENT_SINKS_CLASS = "xyz.neosapien.neo_ble.ble.BleEventSinks"
    private const val UPLOADER_CLASS = "xyz.neosapien.neo_ble.upload.NeoAudioUploader"
    private const val BLE_SERVICE_CLASS = "xyz.neosapien.neo_ble.ble.NeoBleService"

    /** LOUD on purpose (Fix 6): every bridge failure is API drift or neo_ble
     * genuinely absent — both are things a soft no-op would otherwise hide
     * until the first live frame silently never fires. [reason] is a closed,
     * grep-able code (not free text), matching the wakeObs convention. */
    private fun warn(op: String, t: Throwable, reason: String = "wake_ble_bridge_missing") {
        Log.e(
            TAG,
            "$WAKE_OBS_TAG $reason op=$op: ${t.javaClass.simpleName}: ${t.message}",
        )
    }

    /**
     * Bootstrap-time canary (Fix 6): resolves the load-bearing neo_ble
     * class/method signatures reflectively, WITHOUT invoking anything, so a
     * drift surfaces at process start — not only the first time [attach]
     * (or a live frame) actually needs them. Call once from
     * [NeoWakeAttach.bootstrap], on every process start, regardless of arm
     * state.
     */
    fun verifyBridgeAvailable(): Boolean = try {
        val sinks = Class.forName(EVENT_SINKS_CLASS)
        sinks.getMethod("addAudioListener", String::class.java, Function1::class.java)
        sinks.getMethod("removeAudioListener", String::class.java)
        true
    } catch (t: Throwable) {
        warn("canary", unwrap(t), reason = "wake_ble_bridge_missing")
        false
    }

    /** [BleEventSinks.addAudioListener] — registers (or replaces) the "wake"
     * native listener. Returns true on success. */
    fun addAudioListener(key: String, listener: (ByteArray) -> Unit): Boolean {
        return try {
            val cls = Class.forName(EVENT_SINKS_CLASS)
            val instance = cls.getField("INSTANCE").get(null) // Kotlin `object` singleton field
            val method = cls.getMethod("addAudioListener", String::class.java, Function1::class.java)
            method.invoke(instance, key, listener)
            true
        } catch (t: Throwable) {
            warn("addAudioListener", unwrap(t))
            false
        }
    }

    /** [BleEventSinks.removeAudioListener]. */
    fun removeAudioListener(key: String) {
        try {
            val cls = Class.forName(EVENT_SINKS_CLASS)
            val instance = cls.getField("INSTANCE").get(null)
            val method = cls.getMethod("removeAudioListener", String::class.java)
            method.invoke(instance, key)
        } catch (t: Throwable) {
            warn("removeAudioListener", unwrap(t))
        }
    }

    /** [xyz.neosapien.neo_ble.ble.NeoBleService.Companion.cachedCodec] name
     * ("OPUS"/"PCM8"/"UNKNOWN"), best-effort — `null` on any failure
     * (including "no codec cached yet"), in which case the caller defaults
     * to Opus (matches neo_ble's own on-read-failure default). */
    fun cachedCodecName(): String? {
        return try {
            val cls = Class.forName(BLE_SERVICE_CLASS)
            val companion = cls.getField("Companion").get(null)
            val method = companion.javaClass.getMethod("cachedCodec")
            val codec = method.invoke(companion) ?: return null
            (codec as Enum<*>).name
        } catch (t: Throwable) {
            warn("cachedCodec", unwrap(t))
            null
        }
    }

    /** [xyz.neosapien.neo_ble.ble.NeoBleService.Companion.setCommandMode] —
     * lights (`true`) or clears (`false`) both earbud command-mode LEDs.
     * Fire-and-forget: neo_ble's own companion wrapper already dispatches
     * onto its service executor and no-ops when not connected, so this is
     * just the reflective hop across the plugin boundary — failures are
     * swallowed (best-effort, must never affect the capture pipeline). */
    fun setCommandMode(enabled: Boolean) {
        try {
            val cls = Class.forName(BLE_SERVICE_CLASS)
            val companion = cls.getField("Companion").get(null)
            val method = companion.javaClass.getMethod("setCommandMode", Boolean::class.javaPrimitiveType)
            method.invoke(companion, enabled)
        } catch (t: Throwable) {
            warn("setCommandMode", unwrap(t))
        }
    }

    /** [BleEventSinks.registerCommandModeStateProvider] /
     * [BleEventSinks.clearCommandModeStateProvider] — registers (or clears,
     * on `null`) neo_wake's own command-capture truth as neo_ble's
     * connect-ready LED reconcile source (command-mode UI parity plan, U3).
     * Best-effort like every other bridge call: a failure just means the
     * reconcile falls back to its own `false` default (never self-heals ON,
     * but never wrongly claims ON either). */
    fun setCommandModeStateProvider(provider: (() -> Boolean)?) {
        try {
            val cls = Class.forName(EVENT_SINKS_CLASS)
            val instance = cls.getField("INSTANCE").get(null)
            if (provider == null) {
                val method = cls.getMethod("clearCommandModeStateProvider")
                method.invoke(instance)
            } else {
                val method = cls.getMethod("registerCommandModeStateProvider", Function0::class.java)
                method.invoke(instance, provider)
            }
        } catch (t: Throwable) {
            warn("setCommandModeStateProvider", unwrap(t))
        }
    }

    /** [BleEventSinks.registerMicStoppedHandler] /
     * [BleEventSinks.clearMicStoppedHandler] — registers (or clears, on
     * `null`) neo_wake's own force-close-on-mic-stop handler (command-mode
     * force-off plan). neo_ble invokes this when the pendant's mic stops
     * while the connection stays live (sleep or double-tap stop). Best-effort
     * like every other bridge call: a failure just means a stuck CAPTURING
     * command mode is left to its existing closers (wake re-fire / 60s
     * ceiling / full disconnect). */
    fun setMicStoppedHandler(handler: (() -> Unit)?) {
        try {
            val cls = Class.forName(EVENT_SINKS_CLASS)
            val instance = cls.getField("INSTANCE").get(null)
            if (handler == null) {
                val method = cls.getMethod("clearMicStoppedHandler")
                method.invoke(instance)
            } else {
                val method = cls.getMethod("registerMicStoppedHandler", Function0::class.java)
                method.invoke(instance, handler)
            }
        } catch (t: Throwable) {
            warn("setMicStoppedHandler", unwrap(t))
        }
    }

    private fun uploaderInstance(context: Context): Any? {
        return try {
            // `NeoAudioUploader.get(Context)` is a Kotlin `companion object`
            // method with no @JvmStatic, so it lives on NeoAudioUploader$Companion
            // — NOT on NeoAudioUploader itself. Resolve it off the Companion, the
            // same pattern cachedCodec() above already uses. (A plain
            // cls.getMethod("get", …) throws NoSuchMethodException — the bug that
            // silently killed every command upload.)
            val cls = Class.forName(UPLOADER_CLASS)
            val companion = cls.getField("Companion").get(null)
            val getMethod = companion.javaClass.getMethod("get", Context::class.java)
            getMethod.invoke(companion, context.applicationContext)
        } catch (t: Throwable) {
            warn("NeoAudioUploader.get", unwrap(t))
            null
        }
    }

    /** [NeoAudioUploader.enqueueCommand] — the U7 hand-off from
     * `WakeCommandCapture.onClipReady`. */
    fun enqueueCommand(
        context: Context,
        commandId: String,
        source: String,
        wakeEndMs: Int,
        durationMs: Int,
        closedAtMs: Long,
        audioBytes: ByteArray,
    ) {
        val uploader = uploaderInstance(context) ?: return
        try {
            val method = uploader.javaClass.getMethod(
                "enqueueCommand",
                String::class.java, String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, ByteArray::class.java,
            )
            method.invoke(uploader, commandId, source, wakeEndMs, durationMs, closedAtMs, audioBytes)
        } catch (t: Throwable) {
            warn("enqueueCommand", unwrap(t))
        }
    }

    /** [NeoAudioUploader.checkWake] — the U4 fire-time wake-check hand-off from
     * `WakeCommandCapture.onWakeCheckSlice`. Fail-open: if the uploader can't
     * be reached or reflection fails, proceed (`onVerdict(false)`) — a bridge
     * failure must never eat a command. The uploader's `checkWake` is public,
     * non-suspend, callback-based precisely so this reflective call works. */
    fun checkWake(
        context: Context,
        commandId: String,
        wakeEndMs: Int,
        isOpus: Boolean,
        slice: ByteArray,
        onVerdict: (Boolean) -> Unit,
    ) {
        val uploader = uploaderInstance(context) ?: run { onVerdict(false); return }
        try {
            val method = uploader.javaClass.getMethod(
                "checkWake",
                String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                ByteArray::class.java, kotlin.jvm.functions.Function1::class.java,
            )
            method.invoke(uploader, commandId, wakeEndMs, isOpus, slice, onVerdict)
        } catch (t: Throwable) {
            warn("checkWake", unwrap(t))
            onVerdict(false)
        }
    }

    /** [NeoAudioUploader.setAmbientSuppressed] — the U9 hand-off from
     * `WakeCommandCapture.onCaptureOpened`/`onCaptureClosed`. */
    fun setAmbientSuppressed(context: Context, suppressed: Boolean, captureId: String, deadlineMs: Long) {
        val uploader = uploaderInstance(context) ?: return
        try {
            val method = uploader.javaClass.getMethod(
                "setAmbientSuppressed",
                Boolean::class.javaPrimitiveType, String::class.java, Long::class.javaPrimitiveType,
            )
            method.invoke(uploader, suppressed, captureId, deadlineMs)
        } catch (t: Throwable) {
            warn("setAmbientSuppressed", unwrap(t))
        }
    }

    /** [NeoAudioUploader.setAmbientDelayMs] — sized from `preroll + lagMs +
     * margin` on arm (U9 Fix 2 coupling). */
    fun setAmbientDelayMs(context: Context, ms: Long) {
        val uploader = uploaderInstance(context) ?: return
        try {
            val method = uploader.javaClass.getMethod("setAmbientDelayMs", Long::class.javaPrimitiveType)
            method.invoke(uploader, ms)
        } catch (t: Throwable) {
            warn("setAmbientDelayMs", unwrap(t))
        }
    }

    /** Reflection wraps every real failure in [InvocationTargetException] —
     * unwrap it so [warn]'s log names the actual cause. */
    private fun unwrap(t: Throwable): Throwable = (t as? InvocationTargetException)?.cause ?: t
}
