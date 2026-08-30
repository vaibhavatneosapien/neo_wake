package xyz.neosapien.neo_wake

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/**
 * Android entry point for the neo_wake plugin.
 *
 * `arm`/`disarm` now persist the KTD8 record and attach/detach via
 * [NeoWakeAttach] — the real, kill-surviving path, not the old log-only
 * stand-in. This class stays a thin control surface (U6): the event sink it
 * holds is exposed statically via [emitFired] so [NeoWakeAttach] — which
 * runs with no Flutter engine at all on a headless bootstrap — can push a
 * `fired` event whenever a Dart engine DOES happen to be listening, without
 * this plugin instance being the thing that gates detection.
 *
 * NOTE: `onAttachedToEngine` only runs when a Flutter engine is present. The
 * kill-surviving arm path does NOT hang off it — see [NeoWakeStartup] /
 * plan KTD9. This instance only binds channels, per KTD9's own rule.
 */
class NeoWakePlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var methods: MethodChannel
    private lateinit var detections: EventChannel
    private lateinit var appContext: Context

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        methods = MethodChannel(binding.binaryMessenger, "neo_wake")
        methods.setMethodCallHandler(this)
        detections = EventChannel(binding.binaryMessenger, "neo_wake/detections")
        detections.setStreamHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "platformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")
            "arm" -> {
                val args = call.arguments as? Map<*, *>
                val threshold = (args?.get("threshold") as? Number)?.toDouble()
                val lagMs = (args?.get("lagMs") as? Number)?.toInt()
                val modelVersion = args?.get("modelVersion") as? String
                if (threshold == null || lagMs == null || modelVersion == null) {
                    result.error("invalid_args", "arm requires threshold/lagMs/modelVersion", null)
                    return
                }
                Log.i("NeoWakePlugin", "arm: threshold=$threshold lagMs=$lagMs modelVersion=$modelVersion")
                NeoWakeAttach.arm(appContext, threshold, lagMs, modelVersion)
                result.success(null)
            }
            "disarm" -> {
                Log.i("NeoWakePlugin", "disarm")
                NeoWakeAttach.disarm(appContext)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
        events = sink
    }

    override fun onCancel(arguments: Any?) {
        events = null
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods.setMethodCallHandler(null)
        detections.setStreamHandler(null)
        events = null
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())

        /** Whichever [NeoWakePlugin] instance's EventChannel is currently
         * listened to, if any — `null` on a headless run with no Flutter
         * engine, which is expected and not an error. */
        @Volatile private var events: EventChannel.EventSink? = null

        /** Pushed by [NeoWakeAttach] on every native fire (U8 plan step 2) —
         * a no-op when nothing is listening. Always posts to the main
         * thread: [NeoWakeAttach.onFire] runs on neo_wake's own frame
         * worker, and `EventSink`s are not thread-safe. */
        fun emitFired(payload: Map<String, Any?>) {
            val sink = events ?: return
            main.post { sink.success(payload) }
        }
    }
}
