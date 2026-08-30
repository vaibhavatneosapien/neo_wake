import 'package:flutter/services.dart';

/// Facade for the native ONNX wake-word ("Neo") detector.
///
/// Detection runs entirely in native Swift/Kotlin off the Dart/UI isolate,
/// fed audio frames by neo_ble at its kill-surviving native seam. This Dart
/// side is a thin control surface: it does NOT gate detection (the native
/// engine self-arms from a persisted record so it survives a headless
/// relaunch — see plan KTD8/KTD9). Arming here only updates that record.
///
/// Scaffold: only the channel wiring and [platformVersion] round-trip exist.
/// The real control surface (arm/disarm config, detection payload schema)
/// lands in U6; the native engine in U2–U4.
class NeoWake {
  NeoWake._();

  static final NeoWake instance = NeoWake._();

  static const MethodChannel _methods = MethodChannel('neo_wake');
  static const EventChannel _detections = EventChannel('neo_wake/detections');

  /// Verifies the platform channel is wired to the native plugin.
  Future<String?> platformVersion() =>
      _methods.invokeMethod<String>('platformVersion');

  /// Fires once per wake-word detection. Payload schema defined in U6.
  Stream<dynamic> get detections => _detections.receiveBroadcastStream();
}
