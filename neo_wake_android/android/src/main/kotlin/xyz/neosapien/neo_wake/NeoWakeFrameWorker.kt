package xyz.neosapien.neo_wake

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
 * behind — [onOverflow] fires synchronously (still on the CALLER's thread,
 * which is fine: it is not decode/ONNX work, just marking a discontinuity)
 * and the frame is dropped, never blocked-for.
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

    /** O(1) hand-off — copies [payload] (the caller's array may be reused by
     * neo_ble after this returns) and enqueues. Never blocks. */
    fun submitFrame(payload: ByteArray) {
        val copy = payload.copyOf()
        try {
            executor.execute {
                process(copy)
                processedCount++
            }
        } catch (e: RejectedExecutionException) {
            overflowCount++
            onOverflow()
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
