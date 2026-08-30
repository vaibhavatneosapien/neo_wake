package xyz.neosapien.neo_wake

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pure JVM unit tests for [NeoWakeFrameWorker] (U8, KTD2) — the O(1)
 * hand-off + bounded-queue overflow behaviour the "wake" BleEventSinks
 * listener depends on to never block/inline-decode on the BLE callback
 * thread.
 */
class NeoWakeFrameWorkerTest {

    @Test
    fun submitFrame_isProcessedOnAWorkerThread_notTheCaller() {
        val callerThread = Thread.currentThread()
        val seenThread = arrayOfNulls<Thread>(1)
        val latch = CountDownLatch(1)
        val worker = NeoWakeFrameWorker(
            onOverflow = {},
            process = { seenThread[0] = Thread.currentThread(); latch.countDown() },
        )
        worker.submitFrame(byteArrayOf(1, 2, 3))
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(seenThread[0] !== callerThread)
        worker.shutdown()
    }

    @Test
    fun submitFrame_copiesPayload_mutatingCallerArrayAfterSubmitDoesNotAffectProcessed() {
        val seen = arrayOfNulls<ByteArray>(1)
        val latch = CountDownLatch(1)
        val worker = NeoWakeFrameWorker(
            onOverflow = {},
            process = { bytes -> seen[0] = bytes; latch.countDown() },
        )
        val payload = byteArrayOf(9, 9, 9)
        worker.submitFrame(payload)
        payload[0] = 0 // mutate AFTER submit — must not affect the processed copy
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(seen[0]!!.contentEquals(byteArrayOf(9, 9, 9)))
        worker.shutdown()
    }

    @Test
    fun overflow_whenQueueIsFull_dropsFrameAndSignalsDiscontinuity_insteadOfBlocking() {
        val overflowCount = AtomicInteger(0)
        val releaseGate = CountDownLatch(1)
        val firstFrameStarted = CountDownLatch(1)
        // Capacity 1: the first submitted frame occupies the single worker
        // thread (blocked on releaseGate); the second fills the bounded
        // queue; the third MUST overflow rather than block the submitting
        // thread.
        val worker = NeoWakeFrameWorker(
            capacity = 1,
            onOverflow = { overflowCount.incrementAndGet() },
            process = {
                firstFrameStarted.countDown()
                releaseGate.await(2, TimeUnit.SECONDS)
            },
        )
        worker.submitFrame(byteArrayOf(1))
        assertTrue(firstFrameStarted.await(2, TimeUnit.SECONDS))
        worker.submitFrame(byteArrayOf(2)) // fills the 1-slot queue
        worker.submitFrame(byteArrayOf(3)) // must overflow, not block THIS call
        worker.submitFrame(byteArrayOf(4)) // must also overflow

        assertTrue(overflowCount.get() >= 2)
        releaseGate.countDown()
        worker.shutdown()
    }
}
