import Foundation

// The streaming chorus6 detector for "wake up neo" / "neo wake up".
//
// 1:1 mirror of `WakeSpotter.kt` — see that file's header for the full
// rationale. Every constant here is arithmetic from `chorus6.config.json`,
// not a tunable — except `threshold`, the one runtime value pushed on `arm`:
//   - a 2.0 s ring of 32000 float samples, int16 scaled by 1/32768.
//   - 1280-sample (80 ms) advances; every hop the whole ring goes through the
//     frontend graph (raw audio -> 200x40 log-Mel, AGC v2 inside) and the body
//     graph (log-Mel -> 3 softmax probabilities).
//   - score = probs[1] + probs[2]; the argmax between them is never used.
//   - fire = two consecutive hops at or over `threshold` while armed; disarm
//     on fire; re-arm when a later score drops below `releaseFraction` x
//     threshold. Hysteresis, never a wall-clock timer.
//   - `resetRing()` on every fire (the bundle's "wipe history" with no index
//     bookkeeping); the next hop scores background and re-arms on its own.
//
// Reset scopes: a fire zeroes the ring and disarms; `onFrameDropped()` zeroes
// the ring but leaves `armed` alone; `reset()` is the full disconnect/disarm
// reset.
//
// Not reentrant: `process`, `onFrameDropped` and `reset` all run on the
// frame worker's serial queue — `NeoWakeFrameWorker` delivers the overflow
// marker in-band on that queue, never from the BLE callback.
//
// The two ONNX calls are injected hooks, so this file has zero ORT/plugin
// dependency and is exercised by plain XCTest with fakes standing in for the
// sessions — the real hooks (`NeoWakeOrtHooks`) are wired in by
// `NeoWakeAttach`, not here.

/// Turns the 32000-float audio window into 8000 log-Mel floats (row-major `[frame][bin]`).
public typealias FrontendHook = (_ audioWindow: [Float]) throws -> [Float]

/// Turns the 8000 log-Mel floats into the 3 class probabilities (softmax already applied).
public typealias BodyHook = (_ logmel: [Float]) throws -> [Float]

/// One step's result, returned on every call to `WakeSpotter.process`, not
/// only on a detection — the caller owns logging and this stays a pure
/// transform.
public struct WakeSpotterStep {
    /// Counts calls to `process` since construction or the last `reset()`.
    public let stepIndex: Int

    /// `probs[1] + probs[2]` for this hop. Optional only for API stability with
    /// callers that log `score ?? 0`; the chorus6 chain scores every hop.
    public let score: Double?

    /// True on the hop that fires (see the file header for the gate).
    public let fired: Bool

    /// Wall-clock cost of the frontend hook on this hop, for the cost gate.
    public let frontendMs: Double

    /// Wall-clock cost of the body hook on this hop, for the cost gate.
    public let bodyMs: Double

    public init(stepIndex: Int, score: Double?, fired: Bool, frontendMs: Double = 0, bodyMs: Double = 0) {
        self.stepIndex = stepIndex
        self.score = score
        self.fired = fired
        self.frontendMs = frontendMs
        self.bodyMs = bodyMs
    }
}

/// Thrown when `process` is fed a frame of the wrong length, or a hook hands
/// back the wrong number of classes.
public struct WakeSpotterError: Error, CustomStringConvertible {
    public let description: String
}

/// The streaming detector. Construct one per arm; a pendant reconnect means a
/// new arm.
public final class WakeSpotter {
    // Geometry, exposed so the codec pipeline reads it rather than
    // duplicating it — the framer's frame length is built from
    // `advanceSamples`.
    public static let advanceSamples = 1280 // 80 ms @ 16 kHz
    public static let windowSamples = 32000 // 2.0 s @ 16 kHz
    public static let logmelFrames = 200
    public static let logmelBins = 40
    public static let logmelFloats = logmelFrames * logmelBins
    public static let classCount = 3
    public static let releaseFraction = 0.55
    public static let consecutiveHops = 2
    public static let int16Scale: Float = 1.0 / 32768.0

    /// Fires at scores `>= threshold` (two hops in a row). Required, with no default.
    public let threshold: Double

    /// Re-arm level: `releaseFraction * threshold`.
    public let release: Double

    private let frontend: FrontendHook
    private let body: BodyHook

    // Zero at construction and after every reset — zeros ARE the "no audio
    // yet" state the frontend's AGC maps to background.
    private var ring: [Float]

    private var armed = true
    private var run = 0
    private var step = 0

    public init(threshold: Double, frontend: @escaping FrontendHook, body: @escaping BodyHook) {
        self.threshold = threshold
        self.release = Self.releaseFraction * threshold
        self.frontend = frontend
        self.body = body
        self.ring = [Float](repeating: 0, count: Self.windowSamples)
    }

    /// True unless a fire has happened and no hop has since dropped below `release`.
    public var isArmed: Bool { armed }

    /// Consecutive hops at or over `threshold` so far.
    public var consecutiveOverThreshold: Int { run }

    /// Zeroes the ring and the consecutive-hop counter. Leaves `armed` as-is.
    private func resetRing() {
        for i in 0..<ring.count { ring[i] = 0 }
        run = 0
    }

    /// A discarded fragment (decode failure, short header, worker overflow):
    /// the window would otherwise splice non-adjacent audio, so it restarts
    /// from silence. `armed` is left alone — a drop is not a fire.
    public func onFrameDropped() {
        resetRing()
    }

    /// Full reset for a disconnect, an idle stream, or a disarm.
    public func reset() {
        resetRing()
        armed = true
        step = 0
    }

    /// Feeds one 1280-sample (80 ms) advance through the chain.
    @discardableResult
    public func process(_ frame: [Int16]) throws -> WakeSpotterStep {
        guard frame.count == Self.advanceSamples else {
            throw WakeSpotterError(
                description: "WakeSpotter.process expects \(Self.advanceSamples)-sample "
                    + "advance, got \(frame.count)"
            )
        }

        // 1. Shift the ring left by one advance and append the new samples,
        // scaled into [-1, 1].
        // In place, like the Kotlin twin's System.arraycopy: a self-slice
        // `replaceSubrange` would make the buffer non-uniquely referenced and
        // copy-on-write all 32000 floats every hop.
        let tail = Self.windowSamples - Self.advanceSamples
        ring.withUnsafeMutableBufferPointer { buf in
            let base = buf.baseAddress!
            memmove(base, base + Self.advanceSamples, tail * MemoryLayout<Float>.stride)
            for i in 0..<Self.advanceSamples {
                base[tail + i] = Float(frame[i]) * Self.int16Scale
            }
        }

        // 2. Frontend then body on a copy (the hooks may hand the buffer to
        // ORT, which must never alias the live ring).
        let t0 = DispatchTime.now().uptimeNanoseconds
        let logmel = try frontend(ring)
        let t1 = DispatchTime.now().uptimeNanoseconds
        let probs = try body(logmel)
        let t2 = DispatchTime.now().uptimeNanoseconds
        guard probs.count == Self.classCount else {
            throw WakeSpotterError(description: "body hook returned \(probs.count) classes, expected \(Self.classCount)")
        }
        let score = Double(probs[1]) + Double(probs[2])

        // 3. Gate — order matters: re-arm check first so a hop that sits
        // below release re-arms before it is counted.
        if score < release { armed = true }
        run = score >= threshold ? run + 1 : 0
        var fired = false
        if run >= Self.consecutiveHops && armed {
            fired = true
            armed = false
            resetRing()
        }

        let result = WakeSpotterStep(
            stepIndex: step, score: score, fired: fired,
            frontendMs: Double(t1 - t0) / 1e6, bodyMs: Double(t2 - t1) / 1e6
        )
        step += 1
        return result
    }
}
