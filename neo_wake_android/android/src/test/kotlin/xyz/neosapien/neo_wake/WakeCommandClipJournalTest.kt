package xyz.neosapien.neo_wake

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * Unit coverage for U8-harden / KTD11's clip-durability journal:
 * [WakeCommandClipJournalStore] round-trip (incl. a torn trailing record)
 * and [WakeCommandCapture.rehydrate]'s resume-vs-finalize-immediately split.
 *
 * Run with: `gradlew -p neo_wake_android/android testDebugUnitTest`.
 */
class WakeCommandClipJournalTest {

    private lateinit var tempDir: File
    private lateinit var ctx: ContextWrapper

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("neo_wake_clip_journal_test").toFile()
        ctx = object : ContextWrapper(null) {
            override fun getFilesDir(): File = tempDir
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun frame(tag: Int, size: Int = 4): ByteArray = ByteArray(size) { (tag + it).toByte() }

    // ---- WakeCommandClipJournalStore round-trip ----------------------------

    @Test
    fun openThenAppendThenRead_roundTripsHeaderAndAllFrames() {
        val store = WakeCommandClipJournalStore(ctx)
        val header = WakeCommandClipJournalHeader(
            captureId = "cap-1", openedAtMs = 1000L, deadlineMs = 61000L, prerollFrameCount = 2,
        )
        store.openCapture(header, listOf(frame(1), frame(2)))
        store.appendFrame(frame(3))
        store.appendFrame(frame(4))

        val record = store.read()
        assertEquals(header, record?.header)
        assertEquals(4, record?.frames?.size)
        assertArrayEquals(frame(1), record?.frames?.get(0))
        assertArrayEquals(frame(2), record?.frames?.get(1))
        assertArrayEquals(frame(3), record?.frames?.get(2))
        assertArrayEquals(frame(4), record?.frames?.get(3))
    }

    @Test
    fun read_withNoJournal_returnsNull() {
        val store = WakeCommandClipJournalStore(ctx)
        assertNull(store.read())
    }

    @Test
    fun clear_removesBothFiles_readReturnsNull() {
        val store = WakeCommandClipJournalStore(ctx)
        store.openCapture(
            WakeCommandClipJournalHeader("cap-1", 0L, 60000L, 0), listOf(frame(1)),
        )
        store.clear()
        assertNull(store.read())
        assertTrue(File(tempDir, "neo_wake_clip_header.journal").exists().not())
        assertTrue(File(tempDir, "neo_wake_clip_frames.journal").exists().not())
    }

    @Test
    fun openCapture_replacesAPriorJournal_notAppendsToIt() {
        val store = WakeCommandClipJournalStore(ctx)
        store.openCapture(WakeCommandClipJournalHeader("cap-1", 0L, 60000L, 0), listOf(frame(1)))
        store.appendFrame(frame(2))

        store.openCapture(WakeCommandClipJournalHeader("cap-2", 100L, 60100L, 1), listOf(frame(9)))

        val record = store.read()
        assertEquals("cap-2", record?.header?.captureId)
        assertEquals(1, record?.frames?.size)
        assertArrayEquals(frame(9), record?.frames?.get(0))
    }

    @Test
    fun tornTrailingFrameRecord_isDroppedButEarlierFramesSurvive() {
        val store = WakeCommandClipJournalStore(ctx)
        store.openCapture(WakeCommandClipJournalHeader("cap-1", 0L, 60000L, 0), listOf(frame(1), frame(2)))
        // Simulate a kill mid-write: append a length prefix claiming 100
        // bytes but write none of the payload.
        val framesFile = File(tempDir, "neo_wake_clip_frames.journal")
        RandomAccessFile(framesFile, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(byteArrayOf(0, 0, 0, 100))
        }

        val record = store.read()
        assertEquals(2, record?.frames?.size)
        assertArrayEquals(frame(1), record?.frames?.get(0))
        assertArrayEquals(frame(2), record?.frames?.get(1))
    }

    @Test
    fun corruptHeader_readReturnsNull() {
        val headerFile = File(tempDir, "neo_wake_clip_header.journal")
        headerFile.writeBytes("not-enough-lines".toByteArray())
        val store = WakeCommandClipJournalStore(ctx)
        assertNull(store.read())
    }

    // ---- WakeCommandCapture.rehydrate --------------------------------------

    private fun config() = WakeCommandCaptureConfig(
        prerollWindowMs = 50, tailTrimMs = 10, minCommandMs = 10, frameMs = 10, maxClipMs = 1000,
    )

    @Test
    fun rehydrate_beforeDeadline_resumesCapturing_andFurtherFeedsAppendToJournal() {
        val store = WakeCommandClipJournalStore(ctx)
        // Simulate a prior process that opened a capture with 2 preroll
        // frames + 1 command frame already captured, then got killed.
        store.openCapture(
            WakeCommandClipJournalHeader("cap-1", openedAtMs = 0L, deadlineMs = 1000L, prerollFrameCount = 2),
            listOf(frame(1), frame(2)),
        )
        store.appendFrame(frame(3))

        var closedIds = mutableListOf<String>()
        var readyClips = mutableListOf<WakeCommandClip>()
        val cap = WakeCommandCapture(config(), journal = store)
        cap.onCaptureClosed = { closedIds.add(it) }
        cap.onClipReady = { readyClips.add(it) }

        val result = cap.rehydrate(nowMs = 500L) // < deadline (1000)
        assertNull(result) // resumed, not finalized
        assertEquals(WakeCaptureState.CAPTURING, cap.state)
        assertTrue(closedIds.isEmpty()) // no premature close
        assertTrue(readyClips.isEmpty())

        // The resumed capture keeps accepting frames — journal keeps growing.
        cap.feed(frame(4), nowMs = 510L)
        val record = store.read()
        // Journal already held [frame1, frame2, frame3] (2 preroll + the one
        // appended before the simulated kill); feed() appends frame4 -> 4.
        assertEquals(4, record?.frames?.size)

        // A live second fire now closes normally, using the resumed state.
        val clip = cap.onFire(nowMs = 900L)
        assertEquals(1, closedIds.size)
        assertEquals("cap-1", closedIds[0])
        // Journal cleared on close (resetCapture -> journal.clear()).
        assertNull(store.read())
        assertEquals(clip, readyClips.singleOrNull())
    }

    @Test
    fun rehydrate_pastDeadline_finalizesImmediately_firesCallbacks_andClearsJournal() {
        val store = WakeCommandClipJournalStore(ctx)
        store.openCapture(
            WakeCommandClipJournalHeader("cap-2", openedAtMs = 0L, deadlineMs = 1000L, prerollFrameCount = 1),
            listOf(frame(1)),
        )
        store.appendFrame(frame(2))
        store.appendFrame(frame(3))

        var closedIds = mutableListOf<String>()
        var readyClips = mutableListOf<WakeCommandClip>()
        val cap = WakeCommandCapture(config(), journal = store)
        cap.onCaptureClosed = { closedIds.add(it) }
        cap.onClipReady = { readyClips.add(it) }

        val result = cap.rehydrate(nowMs = 5000L) // >= deadline (1000)
        assertEquals(WakeCaptureState.IDLE, cap.state)
        assertEquals(listOf("cap-2"), closedIds)
        assertEquals(1, readyClips.size)
        assertEquals(result, readyClips[0])
        assertEquals("rehydrate_deadline_expired", result?.reason)
        // preroll=1, frames=3 total -> commandFrames=2 (>= minCommandMs's 1
        // frame) -> usable. audioBytes carries ALL frames (preroll + command
        // — no tail-trim on a rehydrate finalize), same as a live close.
        assertEquals(3, unflatten(result!!.audioBytes).size)
        assertNull(store.read()) // journal cleared
    }

    @Test
    fun rehydrate_pastDeadline_tooShortToBeACommand_stillClosesButNoClip() {
        val store = WakeCommandClipJournalStore(ctx)
        // 3 preroll frames, 0 command frames captured before the kill.
        store.openCapture(
            WakeCommandClipJournalHeader("cap-3", openedAtMs = 0L, deadlineMs = 1000L, prerollFrameCount = 3),
            listOf(frame(1), frame(2), frame(3)),
        )

        var closedIds = mutableListOf<String>()
        var readyClips = mutableListOf<WakeCommandClip>()
        val cap = WakeCommandCapture(config(), journal = store)
        cap.onCaptureClosed = { closedIds.add(it) }
        cap.onClipReady = { readyClips.add(it) }

        val result = cap.rehydrate(nowMs = 5000L)
        assertNull(result)
        assertEquals(listOf("cap-3"), closedIds) // still fires — ambient must resume regardless
        assertTrue(readyClips.isEmpty())
        assertNull(store.read())
    }

    @Test
    fun rehydrate_withNoJournalEntry_isANoOp() {
        val store = WakeCommandClipJournalStore(ctx)
        val cap = WakeCommandCapture(config(), journal = store)
        assertNull(cap.rehydrate(nowMs = 100L))
        assertEquals(WakeCaptureState.IDLE, cap.state)
    }

    @Test
    fun rehydrate_withNullJournal_isANoOp() {
        val cap = WakeCommandCapture(config(), journal = null)
        assertNull(cap.rehydrate(nowMs = 100L))
        assertEquals(WakeCaptureState.IDLE, cap.state)
    }

    private fun unflatten(bytes: ByteArray): List<ByteArray> {
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        val out = mutableListOf<ByteArray>()
        while (buf.remaining() > 0) {
            val len = buf.int
            val f = ByteArray(len)
            buf.get(f)
            out.add(f)
        }
        return out
    }
}
