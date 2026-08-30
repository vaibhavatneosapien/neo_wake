package xyz.neosapien.neo_wake

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/**
 * Android entry point for the neo_wake plugin.
 *
 * Scaffold: binds the method + event channels and answers `platformVersion`.
 * The ONNX session layer (U2), native codec + mel/embed/classify state
 * machine (U3), neo_ble multi-listener attach (U5), and the engine-independent
 * bootstrap that self-arms on a headless restart (U8) attach here.
 *
 * NOTE: `onAttachedToEngine` only runs when a Flutter engine is present. The
 * kill-surviving arm path must NOT hang off it — see plan KTD9.
 */
class NeoWakePlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler {
    private lateinit var methods: MethodChannel
    private lateinit var detections: EventChannel
    private var events: EventChannel.EventSink? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods = MethodChannel(binding.binaryMessenger, "neo_wake")
        methods.setMethodCallHandler(this)
        detections = EventChannel(binding.binaryMessenger, "neo_wake/detections")
        detections.setStreamHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "platformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")
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
}
