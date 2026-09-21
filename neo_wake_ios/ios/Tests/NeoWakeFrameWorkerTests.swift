import XCTest
@testable import neo_wake_ios

// NOTE: no native dependency (pure Swift/Foundation) — actually run and
// passed via a standalone SwiftPM harness mirroring this production source
// (same reason as WakePcmFramerTests / NeoWakeSessionsTests); not wired to a
// runnable Xcode target here (no example app yet).
//
// Covers Fix 8 (review, U8-core): `shutdown()` used to be a no-op, so a frame
// already queued when `NeoWakeAttach.detach()` ran could still reach
// `process` — the audio-after-disarm seam. Mirrors the Android twin
// (`NeoWakeFrameWorkerTest.kt`) at the behavioural level; the mechanism
// itself differs (a checked flag here vs. `ThreadPoolExecutor.shutdownNow()`
// there) because GCD's serial `DispatchQueue` has no queue-draining
// cancellation API of its own.
final class NeoWakeFrameWorkerTests: XCTestCase {
    func testShutdown_dropsAFrameAlreadyQueued_neverProcessesIt() {
        let firstStarted = DispatchSemaphore(value: 0)
        let releaseFirst = DispatchSemaphore(value: 0)
        var processedPayloads: [Data] = []
        let processedLock = NSLock()

        let worker = NeoWakeFrameWorker(
            capacity: 4,
            onOverflow: {},
            process: { data in
                if data == Data([1]) {
                    firstStarted.signal()
                    _ = releaseFirst.wait(timeout: .now() + 2)
                }
                processedLock.lock()
                processedPayloads.append(data)
                processedLock.unlock()
            }
        )

        worker.submitFrame(Data([1])) // dequeues immediately, blocks on releaseFirst
        XCTAssertEqual(firstStarted.wait(timeout: .now() + 2), .success)

        worker.submitFrame(Data([2])) // queued BEHIND the still-running first frame

        // Shut down while frame 2 is queued but not yet started, and frame 1
        // is mid-`process` — the exact seam Fix 8 closes.
        worker.shutdown()

        releaseFirst.signal() // let frame 1 finish
        Thread.sleep(forTimeInterval: 0.3) // give the queue a chance to (not) run frame 2

        processedLock.lock()
        let seen = processedPayloads
        processedLock.unlock()

        XCTAssertTrue(seen.contains(Data([1])),
            "the already-running frame must still finish — same as shutdownNow() not interrupting in-flight work")
        XCTAssertFalse(seen.contains(Data([2])),
            "a frame queued at shutdown time must never reach process (the audio-after-disarm seam)")
    }

    func testSubmitFrame_afterShutdown_isTreatedAsOverflow_notQueued() {
        var overflowCalls = 0
        var processedCount = 0
        let worker = NeoWakeFrameWorker(
            onOverflow: { overflowCalls += 1 },
            process: { _ in processedCount += 1 }
        )

        worker.shutdown()
        worker.submitFrame(Data([9]))
        Thread.sleep(forTimeInterval: 0.1)

        XCTAssertEqual(worker.overflowCount, 1)
        XCTAssertEqual(overflowCalls, 0, "the gap is latched, never signalled on the caller's thread")
        XCTAssertEqual(processedCount, 0)
    }

    func testOverflowMarker_runsOnTheWorker_afterTheBacklog_andBeforeTheFirstFrameAfterTheGap() {
        let firstStarted = DispatchSemaphore(value: 0)
        let releaseFirst = DispatchSemaphore(value: 0)
        let fifthDone = DispatchSemaphore(value: 0)
        var events: [String] = []
        var threads: Set<ObjectIdentifier> = []
        let lock = NSLock()
        // capacity 2 = one permit held by the running frame + one queued
        // frame, the same shape as the Android twin's 1-thread + 1-slot queue.
        let worker = NeoWakeFrameWorker(
            capacity: 2,
            onOverflow: {
                lock.lock(); events.append("gap"); threads.insert(ObjectIdentifier(Thread.current)); lock.unlock()
            },
            process: { data in
                if data == Data([1]) {
                    firstStarted.signal()
                    _ = releaseFirst.wait(timeout: .now() + 2)
                }
                lock.lock(); events.append("f\(data[0])"); threads.insert(ObjectIdentifier(Thread.current)); lock.unlock()
                if data == Data([5]) { fifthDone.signal() }
            }
        )
        worker.submitFrame(Data([1])) // running (blocked)
        XCTAssertEqual(firstStarted.wait(timeout: .now() + 2), .success)
        worker.submitFrame(Data([2])) // takes the one permit
        worker.submitFrame(Data([3])) // dropped -> latch
        worker.submitFrame(Data([4])) // dropped
        XCTAssertEqual(worker.overflowCount, 2)
        releaseFirst.signal()
        Thread.sleep(forTimeInterval: 0.1) // let f1/f2 drain
        worker.submitFrame(Data([5])) // first frame after the gap
        XCTAssertEqual(fifthDone.wait(timeout: .now() + 2), .success)

        lock.lock(); let seen = events; let seenThreads = threads; lock.unlock()
        XCTAssertEqual(seen, ["f1", "f2", "gap", "f5"])
        XCTAssertEqual(seenThreads.count, 1, "marker and frames all on the one worker queue")
        XCTAssertFalse(seenThreads.contains(ObjectIdentifier(Thread.current)))
        worker.shutdown()
    }

    func testSubmitFrame_beforeShutdown_stillProcessesNormally() {
        let done = DispatchSemaphore(value: 0)
        var seen: Data?
        let worker = NeoWakeFrameWorker(
            onOverflow: {},
            process: { data in
                seen = data
                done.signal()
            }
        )

        worker.submitFrame(Data([7]))
        XCTAssertEqual(done.wait(timeout: .now() + 2), .success)
        XCTAssertEqual(seen, Data([7]))
    }
}
