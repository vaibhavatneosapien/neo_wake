package xyz.neosapien.neo_wake

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * neo_wake's OWN single-thread, bounded worker for the "wake" BLE audio
 * listener (KTD2). `BleEventSinks.emitAudio` calls every registered listener
 * synchronously, back-to-back, on the BLE callback thread — a listener MUST
 * be O(1) and non-blocking, doing no Opus/ONNX inline. [submitFrame] is that
 * O(1) hand-off: copy the bytes onto this worker's own bounded queue and
 * return immediately; [WakeCodecPipeline]/[WakeCommandCapture] only ever run
 * ON the worker thread, never on the caller's.
 *
 * Bounded, not unbounded: an unbounded queue behind a stalled consumer would
 * let the BLE thread's fire-and-forget submissions pile up memory forever
 * with no signal anything is wrong. A full queue means the worker fell
 * behind: the frame is dropped, never blocked-for, and the discontinuity is
 * LATCHED, not signalled inline. [onOverflow] runs ON THE WORKER THREAD,
 * in-band, immediately before the first frame accepted after the gap — so
 * the ring reset it triggers lands exactly where the audio actually skips
 * (after the still-queued backlog), and never races `process()` from the
 * BLE thread. A synchronous callback here would have zeroed the live ring
 * up to 64 frames (1.28 s) too early, mid-`process()`.
 */
internal class NeoWakeFrameWorker(
    capacity: Int = 64,
    threadName: String = "neo_wake-frame-worker",
    private val onOverflow: () -> Unit,
    private val process: (ByteArray) -> Unit,
) {
    private val queue = ArrayBlockingQueue<Runnable>(capacity)
    private val executor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, queue,
        ThreadFactory { r -> Thread(r, threadName).apply { isDaemon = true } },
    )

    var overflowCount: Int = 0
        private set
    var processedCount: Int = 0
        private set

    /** Set on the BLE thread when a frame is rejected; consumed by the next
     * accepted submit so [onOverflow] runs in-band on the worker. */
    private val pendingGap = AtomicBoolean(false)

    /** O(1) hand-off — copies [payload] (the caller's array may be reused by
     * neo_ble after this returns) and enqueues. Never blocks. */
    fun submitFrame(payload: ByteArray) {
        val copy = payload.copyOf()
        // Read-and-clear BEFORE enqueueing so the marker rides with the
        // first frame after the gap, not with a later one.
        val gap = pendingGap.getAndSet(false)
        try {
            executor.execute {
                if (gap) onOverflow()
                process(copy)
                processedCount++
            }
        } catch (e: RejectedExecutionException) {
            overflowCount++
            pendingGap.set(true)
        }
    }

    /** Run an O(1) task on the worker's executor — same serialization as
     * submitted frames, so the ceiling timer can mutate capture state
     * race-free. No-op after shutdown, mirroring submitFrame. */
    fun submitTask(task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            // Shut down / bounded queue full — dropping is fine here: the
            // ceiling timer fires again in 1s, same as submitFrame dropping
            // a frame on overflow.
        }
    }

    /** Stops accepting new frames and drains in-flight work. Not called from
     * the worker thread itself. */
    fun shutdown() {
        executor.shutdownNow()
    }
}
