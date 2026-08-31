import Foundation

// U8-harden / KTD11 clip-durability gap. `WakeCommandCapture.feed` buffers
// an in-progress command clip in RAM only (`clip`) — a mid-command jetsam
// between `openClip` and `closeClip` loses every frame captured so far. U9
// already journals the AMBIENT-suppression STATE to disk
// (`NeoAmbientSuppressionJournal` in neo_ble), so notes correctly stay gated
// across a kill, but the clip's own AUDIO was never durable — this file
// closes that gap, mirroring `NeoAmbientSuppressionJournal`'s atomic-write
// pattern (own file, this plugin's own directory — no neo_ble coupling).
//
// Two files, both under `dir` (Application Support — same root neo_ble uses
// for its own single-file journals, different filenames so nothing
// collides):
//
// - A small HEADER, replaced atomically (`Data.write(options: .atomic)`) on
//   every `openCapture` call: `captureId` (correlates with the U9
//   suppression journal's own `captureId` — SAME string value, written
//   independently by `WakeCommandCapture.openClip` calling
//   `onCaptureOpened`, which neo_ble's live binding hands to
//   `NeoAudioUploader.setAmbientSuppressed` — nothing here needs to read
//   neo_ble's journal for that correlation to hold, it just needs to use the
//   same id), `openedAtMs`, `deadlineMs` (`openedAtMs + maxClipMs` — the
//   SAME wall-clock ceiling `WakeCommandCapture.tick` already enforces
//   live), and `prerollFrameCount` (how many of the frames below are
//   preroll vs. spoken command audio, needed to reproduce `closeClip`'s
//   `wakeEndMs`/`durationMs`/`isLongEnoughToBeACommand` math on rehydrate).
// - An APPEND-ONLY frames file, `[u32 BE len][payload]…` — the SAME framing
//   `flattenOpus` produces — written with one `FileHandle` append per frame,
//   never rewritten in full (a full-clip atomic rewrite on every ~10-20ms
//   frame would be O(n) per frame over a up-to-60s clip; append is O(1)).
//   Self-describing enough that a torn LAST record (a kill mid-`write`,
//   which is NOT atomic at this granularity) is detected and dropped on
//   read — everything before it is still valid.
//
// Bounded by construction: the wall-clock ceiling (`maxClipMs`, 60s) already
// caps how long a capture — and therefore this journal — can stay open
// before `tick()` (or a rehydrate past-deadline finalize) closes it; no
// separate byte/frame cap is needed on top of that.
public struct WakeCommandClipJournalHeader: Equatable {
    public let captureId: String
    public let openedAtMs: Int64
    public let deadlineMs: Int64
    public let prerollFrameCount: Int

    public init(captureId: String, openedAtMs: Int64, deadlineMs: Int64, prerollFrameCount: Int) {
        self.captureId = captureId
        self.openedAtMs = openedAtMs
        self.deadlineMs = deadlineMs
        self.prerollFrameCount = prerollFrameCount
    }

    func encode() -> Data {
        let lines = [captureId, String(openedAtMs), String(deadlineMs), String(prerollFrameCount)]
        return lines.joined(separator: "\n").data(using: .utf8) ?? Data()
    }

    static func decode(_ data: Data) -> WakeCommandClipJournalHeader? {
        guard let text = String(data: data, encoding: .utf8) else { return nil }
        let lines = text.components(separatedBy: "\n")
        guard lines.count >= 4 else { return nil }
        guard let openedAtMs = Int64(lines[1]) else { return nil }
        guard let deadlineMs = Int64(lines[2]) else { return nil }
        guard let prerollFrameCount = Int(lines[3]) else { return nil }
        return WakeCommandClipJournalHeader(
            captureId: lines[0], openedAtMs: openedAtMs, deadlineMs: deadlineMs,
            prerollFrameCount: prerollFrameCount
        )
    }
}

/// A journal read back at rehydrate time: the header plus every frame
/// recovered from the (possibly torn-at-the-tail) frames file, in the SAME
/// order they were captured — `frames[0..<prerollFrameCount]` is the
/// preroll, the rest is spoken command audio, exactly matching
/// `WakeCommandCapture.clip`'s own layout.
public struct WakeCommandClipJournalRecord {
    public let header: WakeCommandClipJournalHeader
    public let frames: [[UInt8]]
}

/// Disk-backed journal for ONE in-progress command clip (only one capture
/// can ever be open at a time — mirrors `NeoAmbientSuppressionJournal`
/// being a single-record journal for the same reason).
public final class WakeCommandClipJournalStore {
    private let headerURL: URL
    private let framesURL: URL
    /// Held open across `appendFrame` calls while a capture is in progress
    /// so each append is a cheap `write(contentsOf:)`, not an open/seek/close
    /// round-trip per ~10-20ms frame.
    private var framesHandle: FileHandle?

    public init(dir: URL) {
        headerURL = dir.appendingPathComponent("neo_wake_clip_header.journal")
        framesURL = dir.appendingPathComponent("neo_wake_clip_frames.journal")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    /// Frame-length-prefix encoding — same `[u32 BE len][payload]` shape
    /// `flattenOpus` uses, so a stored frame needs no re-parsing beyond
    /// stripping the prefix back off.
    private func encodeFrame(_ payload: [UInt8]) -> Data {
        var out = Data()
        let len = UInt32(payload.count).bigEndian
        withUnsafeBytes(of: len) { out.append(contentsOf: $0) }
        out.append(contentsOf: payload)
        return out
    }

    /// Start (or replace) the journal for a newly-opened capture. Writes the
    /// header atomically, then bulk-writes the drained preroll frames (fresh
    /// frames file — truncates whatever a PRIOR, presumably-already-finalized
    /// capture left behind).
    public func openCapture(header: WakeCommandClipJournalHeader, prerollFrames: [[UInt8]]) {
        closeHandle()
        do {
            try header.encode().write(to: headerURL, options: .atomic)
            FileManager.default.createFile(atPath: framesURL.path, contents: nil)
            let handle = try FileHandle(forWritingTo: framesURL)
            for frame in prerollFrames {
                handle.write(encodeFrame(frame))
            }
            framesHandle = handle
        } catch {
            NSLog("[WakeCommandClipJournalStore] openCapture failed: %@", "\(error)")
        }
    }

    /// Append one incrementally-captured (post-preroll) frame. Best-effort —
    /// a write failure is logged, not thrown; losing the journal must never
    /// take down live capture.
    public func appendFrame(_ payload: [UInt8]) {
        guard let handle = framesHandle else { return }
        handle.write(encodeFrame(payload))
    }

    /// Clear the journal (normal close, or a rehydrate that finalized
    /// immediately). Idempotent.
    public func clear() {
        closeHandle()
        try? FileManager.default.removeItem(at: headerURL)
        try? FileManager.default.removeItem(at: framesURL)
    }

    private func closeHandle() {
        try? framesHandle?.close()
        framesHandle = nil
    }

    /// Read back a journaled capture, or `nil` if none exists / the header
    /// is missing/corrupt. Tolerates a torn trailing frame record (a kill
    /// mid-`write`) by dropping only that incomplete tail — everything
    /// before it is returned.
    public func read() -> WakeCommandClipJournalRecord? {
        guard let headerData = try? Data(contentsOf: headerURL),
              let header = WakeCommandClipJournalHeader.decode(headerData) else {
            return nil
        }
        guard let framesData = try? Data(contentsOf: framesURL) else {
            return WakeCommandClipJournalRecord(header: header, frames: [])
        }
        var frames: [[UInt8]] = []
        var offset = framesData.startIndex
        while offset < framesData.endIndex {
            guard framesData.distance(from: offset, to: framesData.endIndex) >= 4 else { break }
            let lenBytes = framesData.subdata(in: offset..<framesData.index(offset, offsetBy: 4))
            let len = lenBytes.withUnsafeBytes { $0.load(as: UInt32.self) }.bigEndian
            let payloadStart = framesData.index(offset, offsetBy: 4)
            guard framesData.distance(from: payloadStart, to: framesData.endIndex) >= Int(len) else {
                // Torn tail record — kill happened mid-write. Drop it, keep
                // everything already parsed.
                break
            }
            let payloadEnd = framesData.index(payloadStart, offsetBy: Int(len))
            frames.append([UInt8](framesData[payloadStart..<payloadEnd]))
            offset = payloadEnd
        }
        return WakeCommandClipJournalRecord(header: header, frames: frames)
    }
}
