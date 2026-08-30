import Foundation

/// neo_wake's OWN single-thread, bounded worker for the "wake" BLE audio
/// listener (KTD2) — iOS mirror of `NeoWakeFrameWorker.kt`.
/// `BleEventSinks.emitAudio` calls every registered listener synchronously,
/// back-to-back, on the BLE callback thread — a listener MUST be O(1) and
/// non-blocking, doing no Opus/ONNX inline. `submitFrame` is that O(1)
/// hand-off: copy the bytes onto this worker's own bounded queue and return
/// immediately; `WakeCodecPipeline`/`WakeCommandCapture` only ever run ON the
/// worker's serial queue, never on the caller's.
///
/// Bounded via a counting `DispatchSemaphore` used as a non-blocking permit
/// pool (GCD's own serial-queue internal buffer has no fixed capacity to
/// bound against) — a `wait(timeout: .now())` that times out means the
/// worker fell behind, and [onOverflow] fires synchronously on the CALLER's
/// thread (cheap: just marks a discontinuity, no decode/ONNX work) instead
/// of the frame ever queuing or blocking the BLE callback.
public final class NeoWakeFrameWorker {
    private let queue: DispatchQueue
    private let semaphore: DispatchSemaphore
    private let onOverflow: () -> Void
    private let process: (Data) -> Void

    public private(set) var overflowCount = 0
    public private(set) var processedCount = 0
    private let statsLock = NSLock()
    /// Fix 8. Guarded by [statsLock] alongside the counts it gates.
    private var isShutdown = false

    public init(
        capacity: Int = 64,
        label: String = "xyz.neosapien.neo_wake.frame-worker",
        onOverflow: @escaping () -> Void,
        process: @escaping (Data) -> Void
    ) {
        self.queue = DispatchQueue(label: label, qos: .utility)
        self.semaphore = DispatchSemaphore(value: capacity)
        self.onOverflow = onOverflow
        self.process = process
    }

    /// O(1) hand-off — copies `payload` and enqueues. Never blocks the caller.
    public func submitFrame(_ payload: Data) {
        statsLock.lock()
        let shutdown = isShutdown
        statsLock.unlock()
        guard !shutdown else {
            // Mirrors Android's `RejectedExecutionException` path (Fix 8):
            // a submit that arrives after shutdown is treated as an
            // overflow, not silently queued.
            statsLock.lock(); overflowCount += 1; statsLock.unlock()
            onOverflow()
            return
        }
        guard semaphore.wait(timeout: .now()) == .success else {
            statsLock.lock(); overflowCount += 1; statsLock.unlock()
            onOverflow()
            return
        }
        let copy = Data(payload)
        queue.async { [weak self] in
            guard let self else { return }
            // Fix 8: re-checked on the WORKER's own thread, right before
            // doing any work — this is what actually stops a frame that was
            // already queued at the moment `shutdown()` ran (the audio-
            // after-disarm seam) from reaching `process` (onFrame ->
            // possibly onFire -> enqueueCommand). A frame already past this
            // guard and mid-`process` when `shutdown()` runs concurrently is
            // allowed to finish — same "does not stop in-flight work"
            // semantics as Android's `ThreadPoolExecutor.shutdownNow()`.
            self.statsLock.lock()
            let shutdown = self.isShutdown
            self.statsLock.unlock()
            guard !shutdown else {
                self.semaphore.signal()
                return
            }
            self.process(copy)
            self.statsLock.lock(); self.processedCount += 1; self.statsLock.unlock()
            self.semaphore.signal()
        }
    }

    /// Stops accepting new frames AND drops pending queued work (Fix 8,
    /// mirrors Android `NeoWakeFrameWorker.kt`'s `shutdownNow()`): anything
    /// already dequeued and running when this is called still finishes —
    /// same as `shutdownNow()` not interrupting an in-flight task — but
    /// nothing queued behind it, and nothing submitted after, reaches
    /// [process].
    public func shutdown() {
        statsLock.lock()
        isShutdown = true
        statsLock.unlock()
    }
}
