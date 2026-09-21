import XCTest
@testable import neo_wake_ios

// Host-runnable (pure Swift) mirror of NeoWakeAttachTest.kt's
// recordChanged_isTrueOnlyWhenModelThresholdOrLagDiffer. The Swift logic
// lives in `armRecordSessionChanged` (NeoWakeArmRecord.swift) precisely so it
// can be tested without the neo_ble_ios / ORT imports NeoWakeAttach.swift
// carries; `NeoWakeAttach.recordChanged` delegates to it.
final class NeoWakeArmRecordTests: XCTestCase {
    private func record(model: String = "cover", threshold: Double = 0.3, lagMs: Int = 0, owner: String? = "uid-1") -> NeoWakeArmRecord {
        NeoWakeArmRecord(armed: true, ownerUid: owner, modelVersion: model, threshold: threshold, lagMs: lagMs)
    }

    func testSessionChanged_isTrueOnlyWhenModelThresholdOrLagDiffer() {
        let live = record()
        XCTAssertFalse(armRecordSessionChanged(live: nil, incoming: live), "nothing live -> plain attach, not a rebuild")
        XCTAssertFalse(armRecordSessionChanged(live: live, incoming: record(owner: "someone-else")), "owner is a gate field, not a session parameter")
        XCTAssertTrue(armRecordSessionChanged(live: live, incoming: record(model: "chorus6")))
        XCTAssertTrue(armRecordSessionChanged(live: live, incoming: record(threshold: 0.45)))
        XCTAssertTrue(armRecordSessionChanged(live: live, incoming: record(lagMs: 80)))
    }

    func testSessionChanged_staleCoverRecordVsChorus6_isARebuild() {
        let stale = record(model: "cover", threshold: 0.3, lagMs: 0)
        let fresh = record(model: "chorus6", threshold: 0.45, lagMs: 80)
        XCTAssertTrue(armRecordSessionChanged(live: stale, incoming: fresh))
        XCTAssertFalse(armRecordSessionChanged(live: fresh, incoming: fresh))
    }
}
