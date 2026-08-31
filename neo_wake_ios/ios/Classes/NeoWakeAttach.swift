// FLIP BUILD: neo_ble attach stubbed out — neo_wake is disabled here and the flip neo_ble branch does not expose BleEventSinks/NeoBleManager/NeoAudioUploader's ambient API. Restore from git history when re-enabling wake.
import Foundation

/// Inert on this build: every public entry point below is a no-op stub.
/// See the top-of-file note — the real cross-plugin attach lived here and
/// is recoverable from git history (pre-flip revision of this file).
public enum NeoWakeAttach {
    public static var isAttached: Bool { false }

    public static func currentCommandMode() -> Bool { false }

    static func forceCommandCaptureClosedOnMicStop() {}

    public static func bootstrap() {}

    public static func arm(threshold: Double, lagMs: Int, modelVersion: String) {}

    public static func disarm() {}

    /// Test-only — clears in-memory state. No-op: this stub holds none.
    static func resetForTest() {}
}
