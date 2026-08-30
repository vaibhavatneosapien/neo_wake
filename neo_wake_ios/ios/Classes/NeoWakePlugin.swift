import Flutter
import UIKit

/// iOS entry point for the neo_wake plugin.
///
/// `arm`/`disarm` now persist the KTD8 record and attach/detach via
/// `NeoWakeAttach` — the real, kill-surviving path, not the old log-only
/// stand-in. This class stays a thin control surface (U6): the event sink it
/// holds is exposed statically via `emitFired` so `NeoWakeAttach` — which
/// runs with no Flutter engine at all on a headless bootstrap — can push a
/// `fired` event whenever a Dart engine DOES happen to be listening, without
/// this plugin instance being the thing that gates detection.
///
/// NOTE: `register(with:)` does NOT run on a headless restoration relaunch, so
/// the kill-surviving arm path bootstraps from `didFinishLaunching`
/// (`NeoWakeAttach.bootstrap()`, called from the app's AppDelegate), not this
/// registrant — see plan KTD9. This instance only binds channels.
public class NeoWakePlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
    /// Whichever instance's EventChannel is currently listened to, if any —
    /// `nil` on a headless run with no Flutter engine, which is expected.
    private static var events: FlutterEventSink?
    private static let eventsLock = NSLock()

    /// Pushed by `NeoWakeAttach` on every native fire (U8 plan step 2) — a
    /// no-op when nothing is listening. Always dispatches to the main
    /// thread: `NeoWakeAttach.onFire` runs on neo_wake's own frame worker,
    /// and `FlutterEventSink`s are not thread-safe.
    public static func emitFired(_ payload: [String: Any]) {
        eventsLock.lock()
        let sink = events
        eventsLock.unlock()
        guard let sink else { return }
        DispatchQueue.main.async { sink(payload) }
    }

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
        case "arm":
            guard let args = call.arguments as? [String: Any],
                  let threshold = args["threshold"] as? Double,
                  let lagMs = args["lagMs"] as? Int,
                  let modelVersion = args["modelVersion"] as? String else {
                result(FlutterError(code: "invalid_args", message: "arm requires threshold/lagMs/modelVersion", details: nil))
                return
            }
            NSLog("NeoWakePlugin: arm threshold=\(threshold) lagMs=\(lagMs) modelVersion=\(modelVersion)")
            NeoWakeAttach.arm(threshold: threshold, lagMs: lagMs, modelVersion: modelVersion)
            result(nil)
        case "disarm":
            NSLog("NeoWakePlugin: disarm")
            NeoWakeAttach.disarm()
            result(nil)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    public func onListen(withArguments arguments: Any?,
                         eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        Self.eventsLock.lock()
        Self.events = events
        Self.eventsLock.unlock()
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        Self.eventsLock.lock()
        Self.events = nil
        Self.eventsLock.unlock()
        return nil
    }
}
