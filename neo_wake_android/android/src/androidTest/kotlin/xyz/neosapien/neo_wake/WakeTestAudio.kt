package xyz.neosapien.neo_wake

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads a 16 kHz mono 16-bit PCM wav from the test apk's assets as int16 samples. */
object WakeTestAudio {
    fun loadWavPcm(context: Context, assetPath: String): ShortArray {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        // Canonical 44-byte header; the bundle's wavs are written by Python's
        // wave module, which emits exactly that.
        require(bytes.size > 44 && String(bytes, 0, 4) == "RIFF") { "$assetPath is not a RIFF wav" }
        val buf = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(buf.remaining() / 2)
        for (i in out.indices) out[i] = buf.short
        return out
    }

    /** Same, as the `[-1, 1]` floats the frontend graph takes, padded/truncated to one window. */
    fun loadWav(context: Context, assetPath: String): FloatArray {
        val pcm = loadWavPcm(context, assetPath)
        val out = FloatArray(WakeSpotter.WINDOW_SAMPLES)
        for (i in 0 until minOf(pcm.size, out.size)) out[i] = pcm[i] * WakeSpotter.INT16_SCALE
        return out
    }
}
