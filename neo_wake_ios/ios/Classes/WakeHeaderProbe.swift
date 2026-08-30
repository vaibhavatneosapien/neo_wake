import Foundation

// Ported from `wake_word_service.dart`'s `_HeaderProbe`/`rmsOf` (grep
// `HeaderProbe|rmsOf` there). Neo2 firmware adds a 4th header byte (the
// command flag) ahead of the codec payload; v0.0.20 of neo_ble strips 3.
// Feeding the wrong offset to the decoder yields noise and the spotter
// simply never fires — a silent failure, not a crash — so this measures
// which offset actually decodes instead of trusting a constant that is
// right on one firmware and silently wrong on the other.

/// Which header length actually decodes. Measures both offsets over the
/// first couple of seconds of audio and reports a verdict, instead of
/// trusting a constant.
public final class WakeHeaderProbe {
    public init(framesNeeded: Int = 100) {
        self.framesNeeded = framesNeeded
    }

    public let framesNeeded: Int
    private var decoded: [Int: Int] = [3: 0, 4: 0]
    private var energy: [Int: Double] = [3: 0.0, 4: 0.0]
    private var seen = 0

    public var done: Bool { seen >= framesNeeded }
    public var framesSeen: Int { seen }
    public var decodeCounts: [Int: Int] { decoded }

    public func record(headerLen: Int, decoded didDecode: Bool, rms: Double) {
        guard decoded[headerLen] != nil else { return }
        if didDecode {
            decoded[headerLen]! += 1
            energy[headerLen]! += rms
        }
        if headerLen == 4 { seen += 1 } // one tick per source frame
    }

    /// The offset that decoded more often (energy breaks a tie), or
    /// `fallback` when neither produced anything usable.
    public func verdict(fallback: Int) -> Int {
        let three = decoded[3]!
        let four = decoded[4]!
        if three == 0 && four == 0 { return fallback }
        if three == four { return energy[4]! > energy[3]! ? 4 : 3 }
        return four > three ? 4 : 3
    }
}

/// RMS of interleaved little-endian Int16 PCM, normalised to 0..1.
public func rmsOf(_ samples: [Int16]) -> Double {
    if samples.isEmpty { return 0.0 }
    var sumSq = 0.0
    for s in samples {
        let v = Double(s) / 32767.0
        sumSq += v * v
    }
    let rms = (sumSq / Double(samples.count)).squareRoot()
    return min(max(rms, 0.0), 1.0)
}
