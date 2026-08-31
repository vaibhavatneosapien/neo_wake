import Foundation

/// U3's whole boundary: given a stream of raw seam frames (BLE fragment —
/// `[seq u16][reserved][+cmd flag on Neo2]` header, then Opus or PCM8
/// payload), produce the same score/fire timeline `WakeSpotter` produces for
/// the equivalent Dart chain. Ports `wake_word_service.dart`'s
/// `_onFragment`/`_runHeaderProbe`/`_feedEngine` trio into one class — the
/// pure header-probe (`WakeHeaderProbe`) and framer (`WakePcmFramer`) pieces
/// stay their own small types for the same testability reason the Dart file
/// keeps them `@visibleForTesting` top-level, not nested.
///
/// No neo_ble wiring here (U5), no command-clip capture (U7), no Dart facade
/// (U6) — this takes bytes in and hands `WakeSpotterStep`s out.
public final class WakeCodecPipeline {
    public init(
        spotter: WakeSpotter,
        codec: NeoWakeAudioCodec,
        sampleRate: Int = 16000,
        // Upper bound handed to the Opus decoder as its output-buffer
        // capacity, NOT the actual fragment size (mirrors
        // `wake_word_service.dart`'s `_kSamplesPerFrame` exactly, including
        // its own comment: "it only sizes the output buffer, and a generous
        // bound costs nothing while a tight one truncates"). A real BLE
        // fragment is 10 ms / 160 samples; `OpusDecoding.decode` returns
        // however many samples the decoder actually produced, trimmed to
        // that length, not padded/truncated to this bound — see
        // `NeoOpusDecoder.decode`'s doc comment.
        samplesPerFrame: Int = 320,
        headerLenOverride: Int = 0, // 0 = probe at runtime
        probeFramesNeeded: Int = 100,
        decoderFactory: @escaping () -> OpusDecoding
    ) {
        self.spotter = spotter
        self.codec = codec
        self.sampleRate = sampleRate
        self.samplesPerFrame = samplesPerFrame
        self.headerLen = headerLenOverride
        self.decoderFactory = decoderFactory
        self.framer = WakePcmFramer(frameLength: WakeSpotter.advanceSamples)
        if headerLenOverride == 0 {
            self.probe = WakeHeaderProbe(framesNeeded: probeFramesNeeded)
        }
    }

    private let spotter: WakeSpotter
    private let codec: NeoWakeAudioCodec
    private let sampleRate: Int
    private let samplesPerFrame: Int
    private let decoderFactory: () -> OpusDecoding
    private let framer: WakePcmFramer

    private var headerLen: Int
    private var probe: WakeHeaderProbe?
    private var probeDecoders: [Int: OpusDecoding] = [:]
    private lazy var decoder: OpusDecoding = decoderFactory()

    /// Resolved header length once probing finishes (3 or 4), nil while
    /// still probing or on an override.
    public private(set) var resolvedHeaderLen: Int?

    /// Counters mirroring the Dart service's health-tick fields — pure state,
    /// no telemetry emission here (that is U5/wiring's job).
    public private(set) var framesIn = 0
    public private(set) var tooShortForHeader = 0
    public private(set) var decodeFailed = 0

    /// Feeds one raw BLE audio fragment through header resolution, decode,
    /// re-framing to the chain's 1280-sample advance, and the spotter.
    /// Returns zero or more `WakeSpotterStep`s — usually zero (still
    /// buffering fragments toward a full advance) or one.
    @discardableResult
    public func onFragment(_ raw: [UInt8], timestampMs: Int64 = 0) throws -> [WakeSpotterStep] {
        framesIn += 1

        let header = headerLen == 0 ? 4 : headerLen
        guard raw.count > header else {
            tooShortForHeader += 1
            breakStream()
            return []
        }

        if headerLen == 0 {
            // ponytail: pcm8 has no header-length probe -- the probe is
            // opus-only (it always attempts an Opus decode, see
            // runHeaderProbe's doc comment). U5 wires the negotiated codec +
            // header length in from neo_ble's `cachedCodec`; until then pcm8
            // only functions with an explicit `headerLenOverride`, never a
            // guessed one.
            guard codec != .pcm8 else {
                decodeFailed += 1
                breakStream()
                return []
            }
            runHeaderProbe(raw)
            return []
        }

        let payload = Array(raw[headerLen...])
        let samples: [Int16]?
        switch codec {
        case .pcm8:
            samples = decodePcm8(payload)
        case .opus, .unknown:
            samples = decoder.decode(payload, frameSize: samplesPerFrame)
        }

        guard let pcm = samples else {
            decodeFailed += 1
            breakStream()
            return []
        }

        return try feedEngine(pcm)
    }

    /// A drop is a discontinuity for the chain behind it, not a free slot —
    /// see `WakeSpotter`'s file header (R18/KTD13). Every path that skips a
    /// fragment routes through here.
    private func breakStream() {
        spotter.onFrameDropped()
    }

    // Post-fire cooldown (mirrors the Dart service's _kLockoutMs). Without it
    // the embedding ring keeps scoring the same "Neo SimSim" every 80 ms step
    // for ~1.3 s, so one spoken wake word fires dozens of times. On a real fire
    // we clear the ring (onDetection) AND suppress further fires for the lockout
    // window, so a single utterance = a single fire and the open/close toggle
    // stays sane.
    private var lockoutUntilMs: Double = 0
    private static let lockoutMs: Double = 1500

    private func feedEngine(_ samples: [Int16]) throws -> [WakeSpotterStep] {
        var results: [WakeSpotterStep] = []
        var thrown: Error?
        framer.add(samples) { frame in
            guard thrown == nil else { return }
            do {
                let step = try spotter.process(frame)
                if step.fired {
                    let now = Date().timeIntervalSince1970 * 1000
                    if now < lockoutUntilMs {
                        // Within the cooldown — a re-fire on the still-decaying
                        // ring. Surface the step but not as a fire.
                        results.append(WakeSpotterStep(stepIndex: step.stepIndex, score: step.score, fired: false))
                    } else {
                        results.append(step)
                        spotter.onDetection() // clear the ring so the same word can't re-fire
                        lockoutUntilMs = now + WakeCodecPipeline.lockoutMs
                    }
                } else {
                    results.append(step)
                }
            } catch {
                thrown = error
            }
        }
        if let thrown { throw thrown }
        return results
    }

    /// While probing, decode the SAME fragment at both candidate offsets and
    /// let the measurement pick — mirrors `wake_word_service.dart`'s
    /// `_runHeaderProbe`, which always attempts an Opus decode: the Dart
    /// reference has no pcm8 branch at all. Opus-only — `onFragment` routes
    /// pcm8 away from this function before it is ever called (see its
    /// `ponytail:` comment); guessing a header length for pcm8 would
    /// byte-misalign every sample instead of just failing to fire.
    private func runHeaderProbe(_ frame: [UInt8]) {
        assert(codec != .pcm8, "pcm8 must never enter the opus-only header probe")
        guard let probe else { return }
        for len in [3, 4] {
            guard frame.count > len else { continue }
            let dec = probeDecoders[len] ?? decoderFactory()
            probeDecoders[len] = dec
            let payload = Array(frame[len...])
            let samples = dec.decode(payload, frameSize: samplesPerFrame)
            probe.record(headerLen: len, decoded: samples != nil, rms: samples.map(rmsOf) ?? 0.0)
        }

        guard probe.done else { return }

        probeDecoders.removeAll()
        headerLen = probe.verdict(fallback: 3)
        resolvedHeaderLen = headerLen
        self.probe = nil
    }
}
