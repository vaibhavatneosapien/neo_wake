import Foundation

/// Persisted, owned, versioned arm record (U8 / KTD8) — iOS mirror of
/// `NeoWakeArmRecord.kt`. Written natively (UserDefaults here; SharedPreferences
/// on Android) so the engine-independent bootstrap (`didFinishLaunching`, no
/// Flutter engine) can self-arm with no Dart involvement.
///
/// **Fail-closed is the whole point.** `resolveArmed` is the ONLY path
/// anything in this plugin may use to decide "should I attach and start
/// detecting" — never `armed` read directly off a raw decode. A missing,
/// corrupt, schema-mismatched, or owner-unattributed record must default to
/// NOT armed.
public struct NeoWakeArmRecord: Equatable {
    public let armed: Bool
    public let ownerUid: String?
    public let modelVersion: String
    public let threshold: Double
    public let lagMs: Int
    public let schemaVersion: Int

    /// Bump on any incompatible field change.
    public static let schemaVersion = 1

    public init(
        armed: Bool,
        ownerUid: String?,
        modelVersion: String,
        threshold: Double,
        lagMs: Int,
        schemaVersion: Int = NeoWakeArmRecord.schemaVersion
    ) {
        self.armed = armed
        self.ownerUid = ownerUid
        self.modelVersion = modelVersion
        self.threshold = threshold
        self.lagMs = lagMs
        self.schemaVersion = schemaVersion
    }

    public func encode() -> Data {
        let lines = [
            armed ? "true" : "false",
            ownerUid ?? "",
            modelVersion,
            String(threshold),
            String(lagMs),
            String(schemaVersion),
        ]
        return lines.joined(separator: "\n").data(using: .utf8) ?? Data()
    }

    /// Decode a persisted record, or `nil` if it's missing/short/corrupt.
    /// Fail-safe on corrupt (mirrors `NeoAmbientSuppressionRecord.decode` in
    /// neo_ble): a record that can't prove it's a real, complete write must
    /// be dropped like a missing one, never half-decoded with fabricated
    /// defaults.
    public static func decode(_ data: Data?) -> NeoWakeArmRecord? {
        guard let data, let text = String(data: data, encoding: .utf8) else { return nil }
        let lines = text.components(separatedBy: "\n")
        guard lines.count >= 6 else { return nil }
        guard let armed = boolFrom(lines[0]) else { return nil }
        guard let threshold = Double(lines[3]) else { return nil }
        guard let lagMs = Int(lines[4]) else { return nil }
        guard let schemaVersion = Int(lines[5]) else { return nil }
        return NeoWakeArmRecord(
            armed: armed,
            ownerUid: lines[1].isEmpty ? nil : lines[1],
            modelVersion: lines[2],
            threshold: threshold,
            lagMs: lagMs,
            schemaVersion: schemaVersion
        )
    }

    private static func boolFrom(_ s: String) -> Bool? {
        if s == "true" { return true }
        if s == "false" { return false }
        return nil
    }
}

/// Fail-closed resolution (KTD8). Returns the record ONLY when it is safe to
/// self-arm from: `armed == true`, current schema, and a non-empty owner UID
/// that matches `currentUid`. Every other case resolves to `nil`, i.e. "do
/// not attach".
///
/// `currentUid` is deliberately optional and un-trusted-by-default: a caller
/// that could not determine who is signed in (headless, cross-plugin read
/// failed) MUST pass `nil`, which this function always fails closed against.
public func resolveArmed(_ record: NeoWakeArmRecord?, currentUid: String?) -> NeoWakeArmRecord? {
    guard let record else { return nil }
    guard record.armed else { return nil }
    guard record.schemaVersion == NeoWakeArmRecord.schemaVersion else { return nil }
    guard let owner = record.ownerUid, !owner.isEmpty else { return nil }
    guard let currentUid, !currentUid.isEmpty else { return nil }
    guard owner == currentUid else { return nil }
    return record
}

/// UserDefaults-backed store for one `NeoWakeArmRecord` (KTD8).
public enum NeoWakeArmStore {
    private static let key = "xyz.neosapien.neo_wake.arm_record"

    /// Persist `record`, replacing whatever was stored before.
    public static func persist(_ record: NeoWakeArmRecord) {
        UserDefaults.standard.set(record.encode(), forKey: key)
    }

    /// Read the stored record, or `nil` if none exists / it's corrupt.
    public static func read() -> NeoWakeArmRecord? {
        NeoWakeArmRecord.decode(UserDefaults.standard.data(forKey: key))
    }

    /// Clear the stored record (disarm). Idempotent.
    public static func clear() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}
