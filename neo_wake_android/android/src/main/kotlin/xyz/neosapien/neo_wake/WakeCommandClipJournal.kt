package xyz.neosapien.neo_wake

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// U8-harden / KTD11 clip-durability gap. `WakeCommandCapture.feed` buffers an
// in-progress command clip in RAM only (`clip`) — a mid-command jetsam
// between `openClip` and `closeClip` loses every frame captured so far. U9
// already journals the AMBIENT-suppression STATE to disk
// (`NeoAmbientSuppressionJournal` in neo_ble), so notes correctly stay gated
// across a kill, but the clip's own AUDIO was never durable — this file
// closes that gap, mirroring `NeoAmbientSuppressionJournal`'s tmp-then-
// `renameTo` atomic-write pattern (own file, this plugin's own directory —
// no neo_ble coupling).
//
// Two files under `context.filesDir`, distinct names from every neo_ble
// journal so nothing collides:
//
// - A small HEADER, replaced atomically (tmp + `renameTo`) on every
//   `openCapture` call: `captureId` (correlates with the U9 suppression
//   journal's own `captureId` — SAME string value, written independently by
//   `WakeCommandCapture.openClip` calling `onCaptureOpened`, which
//   `NeoWakeAttach`'s live binding hands to
//   `NeoBleAudioBridge.setAmbientSuppressed`; nothing here needs to read
//   neo_ble's journal for that correlation to hold, it just needs to use the
//   same id), `openedAtMs`, `deadlineMs` (`openedAtMs + maxClipMs` — the
//   SAME wall-clock ceiling `WakeCommandCapture.tick` already enforces
//   live), and `prerollFrameCount`.
// - An APPEND-ONLY frames file, `[u32 BE len][payload]…` — the SAME framing
//   `flattenOpus` produces — one append per frame, never rewritten in full.
//   Self-describing enough that a torn LAST record (a kill mid-write) is
//   detected and dropped on read.
//
// Bounded by construction — see the iOS twin's header doc for why no
// separate byte/frame cap is needed on top of `maxClipMs`.

data class WakeCommandClipJournalHeader(
    val captureId: String,
    val openedAtMs: Long,
    val deadlineMs: Long,
    val prerollFrameCount: Int,
) {
    fun encode(): ByteArray = listOf(
        captureId, openedAtMs.toString(), deadlineMs.toString(), prerollFrameCount.toString(),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    companion object {
        fun decode(bytes: ByteArray): WakeCommandClipJournalHeader? {
            val lines = String(bytes, Charsets.UTF_8).split("\n")
            if (lines.size < 4) return null
            val openedAtMs = lines[1].toLongOrNull() ?: return null
            val deadlineMs = lines[2].toLongOrNull() ?: return null
            val prerollFrameCount = lines[3].toIntOrNull() ?: return null
            return WakeCommandClipJournalHeader(lines[0], openedAtMs, deadlineMs, prerollFrameCount)
        }
    }
}

/** A journal read back at rehydrate time — see the iOS twin's doc. */
data class WakeCommandClipJournalRecord(
    val header: WakeCommandClipJournalHeader,
    val frames: List<ByteArray>,
)

/** Disk-backed journal for ONE in-progress command clip. */
class WakeCommandClipJournalStore(private val context: Context) {
    private val headerFile = File(context.filesDir, "neo_wake_clip_header.journal")
    private val headerTmp = File(context.filesDir, "neo_wake_clip_header.journal.tmp")
    private val framesFile = File(context.filesDir, "neo_wake_clip_frames.journal")

    // Held open across `appendFrame` calls so each append is a cheap
    // positioned write, not an open/seek/close round-trip per ~10-20ms
    // frame.
    private var framesRaf: RandomAccessFile? = null

    private fun encodeFrame(payload: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    /** Start (or replace) the journal for a newly-opened capture. */
    fun openCapture(header: WakeCommandClipJournalHeader, prerollFrames: List<ByteArray>) {
        closeHandle()
        try {
            headerTmp.writeBytes(header.encode())
            if (!headerTmp.renameTo(headerFile)) {
                headerFile.writeBytes(header.encode())
                headerTmp.delete()
            }
            framesFile.delete()
            val raf = RandomAccessFile(framesFile, "rw")
            for (frame in prerollFrames) raf.write(encodeFrame(frame))
            framesRaf = raf
        } catch (t: Throwable) {
            Log.w(TAG, "openCapture failed", t)
        }
    }

    /** Append one incrementally-captured (post-preroll) frame. Best-effort. */
    fun appendFrame(payload: ByteArray) {
        val raf = framesRaf ?: return
        try {
            raf.write(encodeFrame(payload))
        } catch (t: Throwable) {
            Log.w(TAG, "appendFrame failed", t)
        }
    }

    /** Clear the journal (normal close, or a rehydrate that finalized immediately). Idempotent. */
    fun clear() {
        closeHandle()
        headerFile.delete()
        headerTmp.delete()
        framesFile.delete()
    }

    private fun closeHandle() {
        try {
            framesRaf?.close()
        } catch (_: Throwable) {
        }
        framesRaf = null
    }

    /**
     * Read back a journaled capture, or `null` if none exists / the header
     * is missing/corrupt. Tolerates a torn trailing frame record (a kill
     * mid-write) by dropping only that incomplete tail.
     */
    fun read(): WakeCommandClipJournalRecord? {
        if (!headerFile.exists()) return null
        val header = try {
            WakeCommandClipJournalHeader.decode(headerFile.readBytes())
        } catch (t: Throwable) {
            null
        } ?: return null

        val framesBytes = try {
            if (framesFile.exists()) framesFile.readBytes() else ByteArray(0)
        } catch (t: Throwable) {
            ByteArray(0)
        }
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < framesBytes.size) {
            if (framesBytes.size - offset < 4) break
            val len = ByteBuffer.wrap(framesBytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            val payloadStart = offset + 4
            if (framesBytes.size - payloadStart < len) break // torn tail record
            frames.add(framesBytes.copyOfRange(payloadStart, payloadStart + len))
            offset = payloadStart + len
        }
        return WakeCommandClipJournalRecord(header, frames)
    }

    private companion object {
        const val TAG = "WakeCommandClipJournal"
    }
}
