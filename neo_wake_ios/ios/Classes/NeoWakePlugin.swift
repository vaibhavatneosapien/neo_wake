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

    /// Command-state channel's own sink (command-mode UI parity plan, U2) —
    /// a separate static from `events`/`detections` because a single
    /// `FlutterStreamHandler` instance can't disambiguate which of its two
    /// registered channels an `onListen`/`onCancel` call is for, so this
    /// channel gets its own handler object (`CommandStateStreamHandler`
    /// below). `fileprivate` (not `private`): that handler is a sibling type
    /// in this same file, and Swift's `private` doesn't cross a type
    /// boundary even within one file.
    fileprivate static var commandEvents: FlutterEventSink?
    fileprivate static let commandEventsLock = NSLock()

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

    /// Pushed by `NeoWakeAttach` on every command-mode open/close/resume
    /// (command-mode UI parity plan, U2) — a no-op when nothing is
    /// listening. Mirrors `emitFired`'s main-thread dispatch.
    public static func emitCommandState(_ on: Bool) {
        commandEventsLock.lock()
        let sink = commandEvents
        commandEventsLock.unlock()
        guard let sink else { return }
        DispatchQueue.main.async { sink(on) }
    }

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = NeoWakePlugin()
        let methods = FlutterMethodChannel(name: "neo_wake",
                                           binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(instance, channel: methods)
        let detections = FlutterEventChannel(name: "neo_wake/detections",
                                             binaryMessenger: registrar.messenger())
        detections.setStreamHandler(instance)
        let commandState = FlutterEventChannel(name: "neo_wake/command_state",
                                               binaryMessenger: registrar.messenger())
        commandState.setStreamHandler(CommandStateStreamHandler())
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

/// Own `FlutterStreamHandler` for `neo_wake/command_state` (command-mode UI
/// parity plan, U2) — see `NeoWakePlugin.commandEvents`'s doc for why this
/// can't just be another case in `NeoWakePlugin.onListen`. On subscribe it
/// immediately posts the current native command-state as the first event, so
/// a fresh Dart isolate (launch/reconnect) never has to guess.
private class CommandStateStreamHandler: NSObject, FlutterStreamHandler {
    func onListen(withArguments arguments: Any?,
                 eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        NeoWakePlugin.commandEventsLock.lock()
        NeoWakePlugin.commandEvents = events
        NeoWakePlugin.commandEventsLock.unlock()
        events(NeoWakeAttach.currentCommandMode())
        return nil
    }

    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        NeoWakePlugin.commandEventsLock.lock()
        NeoWakePlugin.commandEvents = nil
        NeoWakePlugin.commandEventsLock.unlock()
        return nil
    }
}
