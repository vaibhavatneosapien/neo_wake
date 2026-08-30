import Foundation

// Ported from `wake_word_service.dart`'s `PcmFramer` — repacks the
// pendant's 160-sample fragments into the chain's fixed advance
// (`WakeSpotter.advanceSamples`, 1280 @ 16 kHz / 80 ms), carrying the
// remainder across calls. 160 divides 1280 exactly (8 fragments = 1
// advance), so the carry is never mid-frame across an arm boundary.
public final class WakePcmFramer {
    public init(frameLength: Int) {
        self.frameLength = frameLength
        self.carry = [Int16](repeating: 0, count: frameLength)
    }

    public let frameLength: Int
    private var carry: [Int16]
    private var len = 0

    /// Samples held back, waiting for the next call.
    public var pending: Int { len }

    /// Feeds `samples`, invoking `onFrame` once per complete frame.
    ///
    /// `onFrame` receives its OWN copy, never the internal carry buffer —
    /// mirrors the Dart original's guard against a caller holding a view
    /// into a buffer this class is about to overwrite.
    public func add(_ samples: [Int16], onFrame: ([Int16]) -> Void) {
        var offset = 0
        while offset < samples.count {
            let take = min(frameLength - len, samples.count - offset)
            for i in 0..<take {
                carry[len + i] = samples[offset + i]
            }
            len += take
            offset += take
            if len == frameLength {
                onFrame(carry)
                len = 0
            }
        }
    }

    public func reset() {
        len = 0
    }
}
