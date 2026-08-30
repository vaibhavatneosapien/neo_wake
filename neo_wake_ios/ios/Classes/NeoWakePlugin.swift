import Flutter
import UIKit

/// iOS entry point for the neo_wake plugin.
///
/// Scaffold: binds the method + event channels and answers `platformVersion`.
/// The ONNX session layer (U2), native codec + mel/embed/classify state
/// machine (U3), neo_ble multi-listener attach (U5), and the engine-independent
/// bootstrap (didFinishLaunching, before the restoration-aware central manager)
/// that self-arms on a headless relaunch (U8) attach here.
///
/// NOTE: `register(with:)` does NOT run on a headless restoration relaunch, so
/// the kill-surviving arm path must bootstrap from didFinishLaunching, not this
/// registrant — see plan KTD9.
public class NeoWakePlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
    private var events: FlutterEventSink?

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = NeoWakePlugin()
        let methods = FlutterMethodChannel(name: "neo_wake",
                                           binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(instance, channel: methods)
        let detections = FlutterEventChannel(name: "neo_wake/detections",
                                             binaryMessenger: registrar.messenger())
        detections.setStreamHandler(instance)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "platformVersion":
            result("iOS " + UIDevice.current.systemVersion)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    public func onListen(withArguments arguments: Any?,
                         eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.events = events
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        self.events = nil
        return nil
    }
}
