import Foundation

// The pure ONNX streaming chain for "Neo Simsim" detection (KTD3/KTD4).
//
// 1:1 port of `lib/core/neo_agent/wake_spotter.dart` — see that file's header
// for the full rationale. Every stride here is arithmetic with one correct
// answer, not a tunable:
//   - 1280-sample (80 ms) advance, fed with the preceding 480 samples of
//     overlap, so the mel model sees 1760 samples and yields the 8 frames an
//     80 ms advance represents. Feed it 1280 alone and it yields 5 frames,
//     silently dropping 37% of the mel timeline.
//   - `(v / 10) + 2` on every mel value as it enters the mel buffer, at
//     exactly one place. Omit it and scores stay near zero with no error
//     anywhere.
//   - The classifier only ever sees a genuinely full 16-embedding ring. A
//     half-zeroed ring of otherwise real embeddings scores confidently
//     positive, not near zero.
//
// TWO RESET SCOPES, NOT ONE — see wake_spotter.dart's header for why.
// `onDetection()`/`onFrameDropped()` clear the embedding ring only; mel and
// raw overlap keep running. `reset()` clears all three, for a disconnect, an
// idle stream, or a disarm.
//
// Not reentrant: `process` assumes its caller pumps steps serially (mirrors
// the Dart class exactly, including that assumption).
//
// The three ONNX calls are injected hooks, same as the Dart original, so this
// file has zero ORT/plugin dependency and is exercised by plain XCTest with
// fake hooks standing in for the three sessions — the real sessions
// (`NeoWakeSessions`, U2) are wired in by the codec pipeline, not here.

/// Turns one step's 1760-sample audio window into this step's 8 raw mel
/// frames (256 floats, row-major `[frame][bin]`). Unscaled — `(v / 10) + 2`
/// is applied by `WakeSpotter`, not the hook.
public typealias MelHook = (_ audioWindow: [Float]) throws -> [Float]

/// Turns the mel buffer, flattened to `shape` (`[1, 76, 32, 1]`), into one
/// 96-float embedding.
public typealias EmbedHook = (_ melWindow: [Float], _ shape: [Int]) throws -> [Float]

/// Turns the embedding ring, flattened to `shape` (`[1, 16, 96]`), into one
/// score. The sigmoid is already baked into the graph.
public typealias ClassifyHook = (_ embeddingWindow: [Float], _ shape: [Int]) throws -> Double

/// One step's result, returned on every call to `WakeSpotter.process`, not
/// only on a detection — the caller owns logging and this stays a pure
/// transform.
public struct WakeSpotterStep {
    /// Counts calls to `process` since construction or the last `reset()`.
    public let stepIndex: Int

    /// Nil while warming: no score exists until the ring holds 16 real
    /// embeddings.
    public let score: Double?

    /// `score != nil && score! >= threshold`.
    public let fired: Bool
}

/// Thrown when `process` is fed a frame of the wrong length.
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
    public static let overlapSamples = 480
    public static let melInputSamples = advanceSamples + overlapSamples // 1760
    public static let melFramesPerStep = 8
    public static let melBinCount = 32
    public static let melBufferFrames = 76
    public static let embeddingDim = 96
    public static let embeddingRingDepth = 16

    /// Fires at scores `>= threshold`. Required, with no default.
    public let threshold: Double

    private let mel: MelHook
    private let embed: EmbedHook
    private let classify: ClassifyHook

    // The 480 samples immediately behind the next advance. Zero at
    // construction and after `reset()` — that zero-fill IS the first-step
    // padding, not a special case handled separately in `process`.
    private var rawOverlap: [Float]

    // Starts EMPTY, unlike a pre-seeded window: the first embedding must not
    // run until 76 REAL mel frames exist (~800 ms).
    private var melBuffer: [[Float]] = []

    // Also starts empty, depth 16, never pre-seeded with zeros: the
    // classifier must never see a placeholder entry.
    private var embeddingRing: [[Float]] = []

    private var step = 0

    public init(
        threshold: Double,
        mel: @escaping MelHook,
        embed: @escaping EmbedHook,
        classify: @escaping ClassifyHook
    ) {
        self.threshold = threshold
        self.mel = mel
        self.embed = embed
        self.classify = classify
        self.rawOverlap = [Float](repeating: 0, count: Self.overlapSamples)
    }

    public var melBufferLength: Int { melBuffer.count }
    public var embeddingRingLength: Int { embeddingRing.count }

    /// Clears the embedding ring only. See the file header for why mel and
    /// raw audio are deliberately left running.
    public func onDetection() {
        embeddingRing.removeAll()
    }

    /// Clears the embedding ring only, same scope as `onDetection` — a drop
    /// is a discontinuity for the ring, not a reason to blind the whole
    /// chain. Takes no audio: a dropped frame's audio is by definition never
    /// fed to `process`.
    public func onFrameDropped() {
        embeddingRing.removeAll()
    }

    /// Clears mel and raw audio too, for a disconnect, a stream going idle,
    /// or a disarm — points where there is no audio continuity to preserve.
    public func reset() {
        rawOverlap = [Float](repeating: 0, count: Self.overlapSamples)
        melBuffer.removeAll()
        embeddingRing.removeAll()
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

        // 1. Assemble this step's fixed-shape 1760-sample window: last
        // step's 480-sample tail ahead of this step's 1280-sample advance.
        var audioWindow = [Float](repeating: 0, count: Self.melInputSamples)
        for i in 0..<Self.overlapSamples {
            audioWindow[i] = rawOverlap[i]
        }
        for i in 0..<Self.advanceSamples {
            audioWindow[Self.overlapSamples + i] = Float(frame[i])
        }

        // Carry this step's own tail forward for the NEXT step, before
        // anything below can throw and leave the overlap stale.
        var nextOverlap = [Float](repeating: 0, count: Self.overlapSamples)
        for i in 0..<Self.overlapSamples {
            nextOverlap[i] = Float(frame[Self.advanceSamples - Self.overlapSamples + i])
        }
        rawOverlap = nextOverlap

        // 2. Mel: 1760 samples in, 8 frames out, scaled at exactly one place
        // as they enter the buffer.
        let rawMel = try mel(audioWindow)
        for f in 0..<Self.melFramesPerStep {
            var scaled = [Float](repeating: 0, count: Self.melBinCount)
            for b in 0..<Self.melBinCount {
                scaled[b] = rawMel[f * Self.melBinCount + b] / 10 + 2
            }
            melBuffer.append(scaled)
        }
        while melBuffer.count > Self.melBufferFrames {
            melBuffer.removeFirst()
        }

        var score: Double?

        // 3. Embed, only once the buffer holds 76 real frames — never a
        // partial window.
        if melBuffer.count == Self.melBufferFrames {
            var melFlat = [Float]()
            melFlat.reserveCapacity(Self.melBufferFrames * Self.melBinCount)
            for frameVals in melBuffer {
                melFlat.append(contentsOf: frameVals)
            }
            let embedding = try embed(melFlat, [1, Self.melBufferFrames, Self.melBinCount, 1])
            embeddingRing.append(embedding)
            while embeddingRing.count > Self.embeddingRingDepth {
                embeddingRing.removeFirst()
            }

            // 4. Classify, only once the ring holds 16 real embeddings — the
            // gate that makes the zero-padding above safe.
            if embeddingRing.count == Self.embeddingRingDepth {
                var embFlat = [Float]()
                embFlat.reserveCapacity(Self.embeddingRingDepth * Self.embeddingDim)
                for e in embeddingRing {
                    embFlat.append(contentsOf: e)
                }
                score = try classify(embFlat, [1, Self.embeddingRingDepth, Self.embeddingDim])
            }
        }

        let result = WakeSpotterStep(
            stepIndex: step,
            score: score,
            fired: score != nil && score! >= threshold
        )
        step += 1
        return result
    }
}
