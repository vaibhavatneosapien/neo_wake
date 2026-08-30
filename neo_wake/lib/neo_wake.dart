import 'package:flutter/services.dart';

/// Facade for the native ONNX wake-word ("Neo") detector.
///
/// Detection runs entirely in native Swift/Kotlin off the Dart/UI isolate,
/// fed audio frames by neo_ble at its kill-surviving native seam. This Dart
/// side is a thin CONTROL surface (U6): arm/disarm, config push, and the
/// fired-event stream. It does NOT gate detection — the native engine
/// self-arms from a persisted record so it survives a headless relaunch (plan
/// KTD8/KTD9); [arm] here only pushes the config for THIS run onto that
/// record. The real native record + attach lands in U8.
class NeoWake {
  NeoWake._();

  static final NeoWake instance = NeoWake._();

  static const MethodChannel _methods = MethodChannel('neo_wake');
  static const EventChannel _detections = EventChannel('neo_wake/detections');

  /// Verifies the platform channel is wired to the native plugin.
  Future<String?> platformVersion() =>
      _methods.invokeMethod<String>('platformVersion');

  /// Arms the native detector with [threshold]/[lagMs]/[modelVersion].
  ///
  /// Pushed over the `arm` method call. A platform/build with no native `arm`
  /// handler wired yet throws [MissingPluginException]; a config the native
  /// side rejects throws [PlatformException] — both propagate to the caller
  /// rather than being swallowed here, so the Dart-side caller (U6:
  /// `WakeWordService`) is what decides how to surface an arm failure.
  Future<void> arm({
    required double threshold,
    required int lagMs,
    required String modelVersion,
  }) =>
      _methods.invokeMethod<void>('arm', <String, Object?>{
        'threshold': threshold,
        'lagMs': lagMs,
        'modelVersion': modelVersion,
      });

  /// Disarms the native detector.
  Future<void> disarm() => _methods.invokeMethod<void>('disarm');

  /// Fires once per wake-word detection.
  ///
  /// Payload schema (U6) — a `Map` decoded off the platform channel:
  ///   * `score` (double) — the classifier's score at fire, 0..1.
  ///   * `preroll_frames` (int, optional) — the pre-roll ring's depth (10 ms
  ///     fragments) at the triggering frame's own arrival, mirroring the
  ///     `prerollFramesAtArrival` marker the old Dart pump used to freeze
  ///     (R17). Absent until U8 wires the real native emit; the Dart-side
  ///     caller falls back to its own live ring length when it is missing.
  ///
  /// The native emit itself lands in U8 — until then this stream is expected
  /// to stay silent at runtime, which is fine: arming, disarming, and wiring
  /// this stream into the capture path are what U6 is responsible for.
  Stream<dynamic> get detections => _detections.receiveBroadcastStream();
}
