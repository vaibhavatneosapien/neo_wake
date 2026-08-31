import XCTest
@testable import neo_wake_ios

// U8-harden / KTD11. NOTE: no native dependency (pure Swift/Foundation) —
// run via a standalone SwiftPM harness mirroring these production sources
// (`WakeCommandClipJournal.swift` + `WakeCommandCapture.swift` copied
// verbatim), same technique as `WakeCommandCaptureTests`/`WakePcmFramerTests`.
// 11/11 green (see this change's report). Not wired to a runnable Xcode
// target here (no example app yet).
//
// Covers: the journal's own round-trip (incl. a torn trailing frame record
// from a simulated kill-mid-write, and journal replacement on a fresh
// `openCapture`), and `WakeCommandCapture.rehydrate`'s two branches — resume
// mid-window vs. finalize-immediately past the wall-clock deadline — which
// is the actual kill-survival guarantee this unit adds.
final class WakeCommandClipJournalTests: XCTestCase {
    private var tempDir: URL!

    override func setUp() {
        super.setUp()
        tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("neo_wake_clip_journal_test_\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: tempDir)
        tempDir = nil
        super.tearDown()
    }

    private func frame(_ tag: UInt8, size: Int = 4) -> [UInt8] {
        (0..<size).map { tag &+ UInt8($0) }
    }

    private func unflatten(_ bytes: [UInt8]) -> [[UInt8]] {
        var out: [[UInt8]] = []
        var i = 0
        while i < bytes.count {
            let len = Int(bytes[i]) << 24 | Int(bytes[i + 1]) << 16 | Int(bytes[i + 2]) << 8 | Int(bytes[i + 3])
            i += 4
            out.append(Array(bytes[i..<(i + len)]))
            i += len
        }
        return out
    }

    // MARK: - WakeCommandClipJournalStore round-trip

    func testOpenThenAppendThenRead_roundTripsHeaderAndAllFrames() {
        let store = WakeCommandClipJournalStore(dir: tempDir)
        let header = WakeCommandClipJournalHeader(captureId: "cap-1", openedAtMs: 1000, deadlineMs: 61000, prerollFrameCount: 2)
        store.openCapture(header: header, prerollFrames: [frame(1), frame(2)])
        store.appendFrame(frame(3))
        store.appendFrame(frame(4))

        let record = store.read()
        XCTAssertEqual(record?.header, header)
        XCTAssertEqual(record?.frames, [frame(1), frame(2), frame(3), frame(4)])
    }

    func testRead_withNoJournal_returnsNil() {
        let store = WakeCommandClipJournalStore(dir: tempDir)
        XCTAssertNil(store.read())
    }

    func testClear_removesBothFiles_readReturnsNil() {
        let store = WakeCommandClipJournalStore(dir: tempDir)
        store.openCapture(
            header: WakeCommandClipJournalHeader(captureId: "cap-1", openedAtMs: 0, deadlineMs: 60000, prerollFrameCount: 0),
            prerollFrames: [frame(1)]
        )
        store.clear()
        XCTAssertNil(store.read())
        XCTAssertFalse(FileManager.default.fileExists(atPath: tempDir.appendingPathComponent("neo_wake_clip_header.journal").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: tempDir.appendingPathComponent("neo_wake_clip_frames.journal").path))
    }

    func testOpenCapture_replacesAPriorJournal_notAppendsToIt() {
        let store = WakeCommandClipJournalStore(dir: tempDir)
        store.openCapture(
            header: WakeCommandClipJournalHeader(captureId: "cap-1", openedAtMs: 0, deadlineMs: 60000, prerollFrameCount: 0),
            prerollFrames: [frame(1)]
        )
        store.appendFrame(frame(2))

        store.openCapture(
            header: WakeCommandClipJournalHeader(captureId: "cap-2", openedAtMs: 100, deadlineMs: 60100, prerollFrameCount: 1),
            prerollFrames: [frame(9)]
        )

        let record = store.read()
        XCTAssertEqual(record?.header.captureId, "cap-2")
        XCTAssertEqual(record?.frames, [frame(9)])
    }

    func testTornTrailingFrameRecord_isDroppedButEarlierFramesSurvive() {
        let store = WakeCommandClipJournalStore(dir: tempDir)
        store.openCapture(
            header: WakeCommandClipJournalHeader(captureId: "cap-1", openedAtMs: 0, deadlineMs: 60000, prerollFrameCount: 0),
            prerollFrames: [frame(1), frame(2)]
        )
        // Simulate a kill mid-write: a length prefix claiming 100 bytes with
        // none of the payload actually written.
        let framesURL = tempDir.appendingPathComponent("neo_wake_clip_frames.journal")
        let handle = try! FileHandle(forWritingTo: framesURL)
        handle.seekToEndOfFile()
        handle.write(Data([0, 0, 0, 100]))
        try? handle.close()

        let record = store.read()
        XCTAssertEqual(record?.frames, [frame(1), frame(2)])
    }

    func testCorruptHeader_readReturnsNil() {
        let headerURL = tempDir.appendingPathComponent("neo_wake_clip_header.journal")
        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        try! "not-enough-lines".data(using: .utf8)!.write(to: headerURL)
        let store = WakeCommandClipJournalStore(dir: tempDir)
        XCTAssertNil(store.read())
    }

    // MARK: - WakeCommandCapture.rehydrate

    private func config() -> WakeCommandCaptureConfig {
        var c = WakeCommandCaptureConfig()
        c.prerollWindowMs = 50
        c.tailTrimMs = 10
        c.minCommandMs = 10
        c.frameMs = 10
        c.maxClipMs = 1000
        return c
    }

    func testRehydrate_beforeDeadline_resumesCapturing_andFurtherFeedsAppendToJournal() {
        let store = WakeCommandClipJournalStore(dir: tempDir)
        store.openCapture(
            header: WakeCommandClipJournalHeader(captureId: "cap-1", openedAtMs: 0, deadlineMs: 1000, prerollFrameCount: 2),
            prerollFrames: [frame(1), frame(2)]
        )
        store.appendFrame(frame(3))

        var closedIds: [String] = []
        var readyClips: [WakeCommandClip] = []
        let cap = WakeCommandCapture(config: config(), journal: store)
        cap.onCaptureClosed = { closedIds.append($0) }
        cap.onClipReady = { readyClips.append($0) }

        let result = cap.rehydrate(nowMs: 500) // < deadline
        XCTAssertNil(result)
        XCTAssertEqual(cap.state, .capturing)
        XCTAssertTrue(closedIds.isEmpty)
        XCTAssertTrue(readyClips.isEmpty)

        cap.feed(frame(4), nowMs: 510)
        let record = store.read()
        XCTAssertEqual(record?.frames.count, 4) // [1,2,3] already journaled + feed(4)

        let clip = cap.onFire(nowMs: 900) // live second fire closes normally
        XCTAssertEqual(closedIds, ["cap-1"])
        XCTAssertNil(store.read(), "journal cleared on normal close")
        XCTAssertEqual(clip?.commandId, readyClips.first?.commandId)
    }

    func testRehydrate_pastDeadline_finalizesImmediately_firesCallbacks_andClearsJournal() {
        let store = WakeCommandClipJournalStore(dir: tempDir)
        store.openCapture(
            header: WakeCommandClipJournalHeader(captureId: "cap-2", openedAtMs: 0, deadlineMs: 1000, prerollFrameCount: 1),
            prerollFrames: [frame(1)]
        )
        store.appendFrame(frame(2))
        store.appendFrame(frame(3))

        var closedIds: [String] = []
        var readyClips: [WakeCommandClip] = []
        let cap = WakeCommandCapture(config: config(), journal: store)
        cap.onCaptureClosed = { closedIds.append($0) }
        cap.onClipReady = { readyClips.append($0) }

        let result = cap.rehydrate(nowMs: 5000) // >= deadline
        XCTAssertEqual(cap.state, .idle)
        XCTAssertEqual(closedIds, ["cap-2"])
        XCTAssertEqual(readyClips.count, 1)
        XCTAssertEqual(result?.commandId, readyClips.first?.commandId)
        XCTAssertEqual(result?.reason, "rehydrate_deadline_expired")
        XCTAssertEqual(unflatten(result!.audioBytes).count, 3, "no tail-trim on a rehydrate finalize")
        XCTAssertNil(store.read())
    }

    func testRehydrate_pastDeadline_tooShortToBeACommand_stillClosesButNoClip() {
        let store = WakeCommandClipJournalStore(dir: tempDir)
        store.openCapture(
            header: WakeCommandClipJournalHeader(captureId: "cap-3", openedAtMs: 0, deadlineMs: 1000, prerollFrameCount: 3),
            prerollFrames: [frame(1), frame(2), frame(3)]
        )

        var closedIds: [String] = []
        var readyClips: [WakeCommandClip] = []
        let cap = WakeCommandCapture(config: config(), journal: store)
        cap.onCaptureClosed = { closedIds.append($0) }
        cap.onClipReady = { readyClips.append($0) }

        let result = cap.rehydrate(nowMs: 5000)
        XCTAssertNil(result)
        XCTAssertEqual(closedIds, ["cap-3"], "still fires — ambient must resume regardless")
        XCTAssertTrue(readyClips.isEmpty)
        XCTAssertNil(store.read())
    }

    func testRehydrate_withNoJournalEntry_isANoOp() {
        let store = WakeCommandClipJournalStore(dir: tempDir)
        let cap = WakeCommandCapture(config: config(), journal: store)
        XCTAssertNil(cap.rehydrate(nowMs: 100))
        XCTAssertEqual(cap.state, .idle)
    }

    func testRehydrate_withNilJournal_isANoOp() {
        let cap = WakeCommandCapture(config: config(), journal: nil)
        XCTAssertNil(cap.rehydrate(nowMs: 100))
        XCTAssertEqual(cap.state, .idle)
    }
}
