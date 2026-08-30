import XCTest
@testable import neo_wake_ios

// NOTE: no native dependency (pure Swift/Foundation) — run via a standalone
// SwiftPM harness mirroring these production sources, same technique as
// WakePcmFramerTests/WakeSpotterTests. Not wired to a runnable Xcode target
// here (no example app yet).
//
// Ported from `WakeCommandCaptureTest.kt`'s scenarios, which mirror the
// capture-machinery group in `test/core/neo_agent/wake_word_service_test.dart`.

private func frame(_ tag: Int, size: Int = 4) -> [UInt8] {
    (0..<size).map { UInt8(truncatingIfNeeded: tag + $0) }
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

final class WakeCommandCaptureTests: XCTestCase {

    func testClipCarriesThePrerollRingContent() {
        let cap = WakeCommandCapture(config: WakeCommandCaptureConfig(
            prerollWindowMs: 50, lagMs: 0, tailTrimMs: 10, maxClipMs: 60_000, minCommandMs: 50, frameMs: 10
        ))
        for i in 0..<5 { cap.feed(frame(i), nowMs: Int64(i * 10)) }

        XCTAssertNil(cap.onFire(nowMs: 50)) // open — no clip yet
        XCTAssertEqual(cap.state, .capturing)
        for i in 5..<15 { cap.feed(frame(i), nowMs: Int64(50 + (i - 5) * 10)) }

        guard let clip = cap.onFire(nowMs: 200) else {
            return XCTFail("expected a clip")
        }
        XCTAssertEqual(clip.source, WakeCommandSource.wakePhrase)
        XCTAssertEqual(cap.state, .idle)

        let frames = unflatten(clip.audioBytes)
        // 5 preroll + 10 command - 1 trimmed (tailTrimMs=10 -> 1 frame) = 14
        XCTAssertEqual(frames.count, 14)
        for i in 0..<5 { XCTAssertEqual(frames[i], frame(i)) }
    }

    func testSecondFireClosesAndTailTrimDropsTheClosingPhrase() {
        let cap = WakeCommandCapture(config: WakeCommandCaptureConfig(
            prerollWindowMs: 10, lagMs: 0, tailTrimMs: 30, maxClipMs: 60_000, minCommandMs: 10, frameMs: 10
        ))
        cap.onFire(nowMs: 0)
        for i in 0..<10 { cap.feed(frame(100 + i), nowMs: Int64(i * 10)) }
        guard let clip = cap.onFire(nowMs: 100) else {
            return XCTFail("expected a clip")
        }
        let frames = unflatten(clip.audioBytes)
        XCTAssertEqual(frames.count, 7) // 10 - 3 trimmed (30ms/10ms)
        XCTAssertEqual(frames.last, frame(106))
    }

    func testNoSecondFire_wallClockCeilingClosesTheWindow() {
        let cap = WakeCommandCapture(config: WakeCommandCaptureConfig(
            prerollWindowMs: 1000, lagMs: 0, tailTrimMs: 1500, maxClipMs: 1000, minCommandMs: 10, frameMs: 10
        ))
        cap.onFire(nowMs: 0)
        for i in 0..<50 { cap.feed(frame(i), nowMs: Int64(i * 10)) }

        XCTAssertNil(cap.tick(nowMs: 500)) // not yet
        XCTAssertEqual(cap.state, .capturing)

        guard let clip = cap.tick(nowMs: 1000) else {
            return XCTFail("expected the ceiling to close the window")
        }
        XCTAssertEqual(clip.reason, "ceiling")
        XCTAssertEqual(cap.state, .idle)
    }

    func testDisconnectMidCapture_finalizesWithoutTrim() {
        let cap = WakeCommandCapture(config: WakeCommandCaptureConfig(minCommandMs: 10))
        cap.onFire(nowMs: 0)
        for i in 0..<20 { cap.feed(frame(i), nowMs: Int64(i * 10)) }

        guard let clip = cap.onDisconnect(nowMs: 200) else {
            return XCTFail("expected a clip")
        }
        XCTAssertEqual(clip.reason, "pendant_disconnected")
        XCTAssertEqual(unflatten(clip.audioBytes).count, 20) // nothing trimmed
        XCTAssertEqual(cap.state, .idle)
    }

    func testTooShortAfterTrim_isDiscarded_returnsNil() {
        let cap = WakeCommandCapture(config: WakeCommandCaptureConfig(
            prerollWindowMs: 1000, lagMs: 0, tailTrimMs: 1500, maxClipMs: 60_000, minCommandMs: 200, frameMs: 10
        ))
        cap.onFire(nowMs: 0)
        for i in 0..<10 { cap.feed(frame(i), nowMs: Int64(i * 10)) } // 100ms
        XCTAssertNil(cap.onFire(nowMs: 100)) // 1500ms tail trim eats it all
        XCTAssertEqual(cap.state, .idle)
    }

    func testOnClipReadyCallbackFires() {
        let cap = WakeCommandCapture(config: WakeCommandCaptureConfig(tailTrimMs: 10, minCommandMs: 10))
        var received: WakeCommandClip?
        cap.onClipReady = { received = $0 }
        cap.onFire(nowMs: 0)
        for i in 0..<10 { cap.feed(frame(i), nowMs: Int64(i * 10)) }
        let closed = cap.onFire(nowMs: 100)
        XCTAssertNotNil(closed)
        XCTAssertEqual(received?.commandId, closed?.commandId)
    }

    func testWakeEndMsFromPreroll_matchesDartFormula() {
        XCTAssertEqual(wakeEndMsFromPreroll(prerollFrames: 100, lagMs: 0, frameMs: 10), 1000)
        XCTAssertEqual(wakeEndMsFromPreroll(prerollFrames: 100, lagMs: 300, frameMs: 10), 700)
        XCTAssertEqual(wakeEndMsFromPreroll(prerollFrames: 10, lagMs: 999, frameMs: 10), 0) // floors at 0
    }

    func testTailTrimCoversLagInvariant() {
        XCTAssertTrue(tailTrimCoversLag(tailTrimMs: 1500, lagMs: 0))
        XCTAssertFalse(tailTrimCoversLag(tailTrimMs: 1500, lagMs: 600)) // 600+1000 > 1500
    }

    func testFlattenOpusRoundTripsFrameBoundaries() {
        let frames = [frame(1, size: 3), frame(2, size: 0), frame(3, size: 7)]
        let flat = flattenOpus(frames)
        let back = unflatten(flat)
        XCTAssertEqual(frames, back)
    }

    // MARK: - U9 / KTD11 ambient/command isolation seam

    func testOnCaptureOpenedFiresOnceOnTheWakeFireThatOpensNotOnTheCloseFire() {
        let cap = WakeCommandCapture(config: WakeCommandCaptureConfig(minCommandMs: 10, frameMs: 10))
        var opened: [String] = []
        cap.onCaptureOpened = { opened.append($0) }

        cap.onFire(nowMs: 0) // opens
        XCTAssertEqual(opened.count, 1)

        for i in 0..<5 { cap.feed(frame(i), nowMs: Int64(i * 10)) }
        cap.onFire(nowMs: 50) // closes — must NOT fire onCaptureOpened again

        XCTAssertEqual(opened.count, 1)
    }

    func testOnCaptureClosedFiresWithTheSameCaptureIdOnTheClosingFire() {
        let cap = WakeCommandCapture(config: WakeCommandCaptureConfig(tailTrimMs: 10, minCommandMs: 10, frameMs: 10))
        var opened: [String] = []
        var closed: [String] = []
        cap.onCaptureOpened = { opened.append($0) }
        cap.onCaptureClosed = { closed.append($0) }

        cap.onFire(nowMs: 0)
        for i in 0..<5 { cap.feed(frame(i), nowMs: Int64(i * 10)) }
        let clip = cap.onFire(nowMs: 50)

        XCTAssertNotNil(clip)
        XCTAssertEqual(opened.count, 1)
        XCTAssertEqual(closed.count, 1)
        XCTAssertEqual(opened.first, closed.first, "the ambient resume signal must correlate to the same capture")
    }

    func testOnCaptureClosedFiresEvenWhenTheClipIsDiscardedAsTooShort() {
        let cap = WakeCommandCapture(config: WakeCommandCaptureConfig(
            prerollWindowMs: 1000, lagMs: 0, tailTrimMs: 1500, maxClipMs: 60_000, minCommandMs: 200, frameMs: 10
        ))
        var closedCount = 0
        var clipReadyCount = 0
        cap.onCaptureClosed = { _ in closedCount += 1 }
        cap.onClipReady = { _ in clipReadyCount += 1 }

        cap.onFire(nowMs: 0)
        for i in 0..<10 { cap.feed(frame(i), nowMs: Int64(i * 10)) } // 100ms
        let clip = cap.onFire(nowMs: 100) // discarded — tail trim eats it all

        XCTAssertNil(clip)
        XCTAssertEqual(closedCount, 1, "the ambient feed must resume even though no clip was produced")
        XCTAssertEqual(clipReadyCount, 0)
    }

    func testOnCaptureClosedFiresOnTheWallClockCeiling() {
        let cap = WakeCommandCapture(config: WakeCommandCaptureConfig(
            prerollWindowMs: 1000, lagMs: 0, tailTrimMs: 1500, maxClipMs: 1000, minCommandMs: 10, frameMs: 10
        ))
        var openedId: String?
        var closedId: String?
        cap.onCaptureOpened = { openedId = $0 }
        cap.onCaptureClosed = { closedId = $0 }

        cap.onFire(nowMs: 0)
        for i in 0..<50 { cap.feed(frame(i), nowMs: Int64(i * 10)) }
        XCTAssertNil(cap.tick(nowMs: 500))
        XCTAssertNil(closedId, "no premature resume before the ceiling")

        cap.tick(nowMs: 1000)
        XCTAssertEqual(openedId, closedId)
    }

    func testOnCaptureClosedFiresOnDisconnectMidCaptureButNotWhenIdle() {
        let cap = WakeCommandCapture(config: WakeCommandCaptureConfig(minCommandMs: 10, frameMs: 10))
        var closedCount = 0
        cap.onCaptureClosed = { _ in closedCount += 1 }

        // Idle disconnect — nothing was open, no resume signal to send.
        cap.onDisconnect(nowMs: 0)
        XCTAssertEqual(closedCount, 0)

        cap.onFire(nowMs: 100)
        for i in 0..<5 { cap.feed(frame(i), nowMs: Int64(100 + i * 10)) }
        cap.onDisconnect(nowMs: 200)
        XCTAssertEqual(closedCount, 1)
    }

    func testCaptureHooksDefaultToNilDormantByDesign() {
        let cap = WakeCommandCapture(config: WakeCommandCaptureConfig(tailTrimMs: 10, minCommandMs: 10, frameMs: 10))
        XCTAssertNil(cap.onCaptureOpened)
        XCTAssertNil(cap.onCaptureClosed)

        // Must not crash with both hooks unset (the live U8 binding is
        // optional until wired).
        cap.onFire(nowMs: 0)
        for i in 0..<5 { cap.feed(frame(i), nowMs: Int64(i * 10)) }
        let clip = cap.onFire(nowMs: 50)
        XCTAssertNotNil(clip)
    }
}
