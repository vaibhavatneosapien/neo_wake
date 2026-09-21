package xyz.neosapien.neo_wake

import org.junit.Assert.assertEquals
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
    fun overflow_whenQueueIsFull_dropsFrameAndLatchesTheGap_insteadOfBlocking() {
        val overflowCalls = AtomicInteger(0)
        val releaseGate = CountDownLatch(1)
        val firstFrameStarted = CountDownLatch(1)
        // Capacity 1: the first submitted frame occupies the single worker
        // thread (blocked on releaseGate); the second fills the bounded
        // queue; the third MUST overflow rather than block the submitting
        // thread.
        val worker = NeoWakeFrameWorker(
            capacity = 1,
            onOverflow = { overflowCalls.incrementAndGet() },
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

        assertEquals(2, worker.overflowCount)
        assertEquals("the gap is latched, never signalled on the caller's thread", 0, overflowCalls.get())
        releaseGate.countDown()
        worker.shutdown()
    }

    @Test
    fun overflowMarker_runsOnTheWorker_afterTheBacklog_andBeforeTheFirstFrameAfterTheGap() {
        val events = mutableListOf<String>()
        val eventsLock = Any()
        val releaseGate = CountDownLatch(1)
        val firstFrameStarted = CountDownLatch(1)
        val fifthProcessed = CountDownLatch(1)
        val workerThreads = mutableSetOf<Thread>()
        val worker = NeoWakeFrameWorker(
            capacity = 1,
            onOverflow = {
                synchronized(eventsLock) { events.add("gap"); workerThreads.add(Thread.currentThread()) }
            },
            process = { bytes ->
                if (bytes[0] == 1.toByte()) {
                    firstFrameStarted.countDown()
                    releaseGate.await(2, TimeUnit.SECONDS)
                }
                synchronized(eventsLock) { events.add("f${bytes[0]}"); workerThreads.add(Thread.currentThread()) }
                if (bytes[0] == 5.toByte()) fifthProcessed.countDown()
            },
        )
        worker.submitFrame(byteArrayOf(1)) // running (blocked)
        assertTrue(firstFrameStarted.await(2, TimeUnit.SECONDS))
        worker.submitFrame(byteArrayOf(2)) // queued
        worker.submitFrame(byteArrayOf(3)) // dropped -> latch
        worker.submitFrame(byteArrayOf(4)) // dropped
        releaseGate.countDown()
        Thread.sleep(50) // let f1/f2 drain
        worker.submitFrame(byteArrayOf(5)) // first frame after the gap
        assertTrue(fifthProcessed.await(2, TimeUnit.SECONDS))

        val seen = synchronized(eventsLock) { events.toList() }
        assertEquals(listOf("f1", "f2", "gap", "f5"), seen)
        assertEquals("marker and frames all on the one worker thread", 1, workerThreads.size)
        assertTrue(workerThreads.first() !== Thread.currentThread())
        worker.shutdown()
    }
}
