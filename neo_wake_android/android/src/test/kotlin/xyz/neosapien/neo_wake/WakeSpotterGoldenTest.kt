package xyz.neosapien.neo_wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays `src/test/resources/wakeword/chorus6_stream_golden.json` (generated
 * by `script/chorus6_reference.py` in the app repo from the bundle's own
 * held-out positives, padded with 1.5 s / 2.5 s of silence and scored by the
 * real chorus6 graphs on a laptop) through the real [WakeSpotter] gate with
 * a fake body hook that hands back each hop's recorded wake score.
 *
 * A green run proves the gate (two-hop confirm, hysteresis re-arm, ring
 * reset on fire) reproduces the reference rig's fire sequence hop-for-hop.
 * It does NOT prove the ONNX numerics on device — that is
 * `WakeSpotterDeviceGoldenTest` (androidTest), which streams the same wavs
 * through the real sessions.
 */
private class GoldenStream(val hopScores: List<Double>, val expectedFireHop: Int, val fires: List<Int>)

private class GoldenFixture(
    val threshold: Double,
    val release: Double,
    val streams: Map<String, GoldenStream>,
    val wholeWindow: Map<String, Double>,
)

/** Minimal recursive-descent JSON parser -- just enough for this fixture's
 * schema (objects, arrays, strings, numbers incl. exponents, booleans, null).
 * Numbers always come back as [Double] (JSON has no int/double distinction). */
private class JsonParser(private val s: String) {
    private var i = 0

    fun parse(): Any? {
        skipWs()
        val v = parseValue()
        skipWs()
        return v
    }

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun parseValue(): Any? {
        skipWs()
        return when (s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        i++ // {
        skipWs()
        if (s[i] == '}') { i++; return map }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            i++ // :
            map[key] = parseValue()
            skipWs()
            val c = s[i]
            i++
            if (c == '}') break
        }
        return map
    }

    private fun parseArray(): List<Any?> {
        val list = mutableListOf<Any?>()
        i++ // [
        skipWs()
        if (s[i] == ']') { i++; return list }
        while (true) {
            list.add(parseValue())
            skipWs()
            val c = s[i]
            i++
            if (c == ']') break
        }
        return list
    }

    private fun parseString(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (s[i]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'u' -> {
                        sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(s[i])
                }
            } else {
                sb.append(s[i])
            }
            i++
        }
        i++ // closing quote
        return sb.toString()
    }

    private fun parseNumber(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(start, i).toDouble()
    }
}

@Suppress("UNCHECKED_CAST")
private fun loadFixture(): GoldenFixture {
    val stream = object {}.javaClass.getResourceAsStream("/wakeword/chorus6_stream_golden.json")
        ?: error("chorus6_stream_golden.json missing from test resources")
    val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val json = JsonParser(text).parse() as Map<String, Any?>
    val streams = (json["streams"] as Map<String, Map<String, Any?>>).mapValues { (_, v) ->
        GoldenStream(
            hopScores = v["hop_scores"] as List<Double>,
            expectedFireHop = (v["expected_fire_hop"] as Double).toInt(),
            fires = (v["fires"] as List<Double>).map { it.toInt() },
        )
    }
    val wholeWindow = (json["whole_window"] as Map<String, Map<String, Any?>>).mapValues { (_, v) -> v["wake_score"] as Double }
    return GoldenFixture(
        threshold = json["threshold"] as Double,
        release = json["release"] as Double,
        streams = streams,
        wholeWindow = wholeWindow,
    )
}

class WakeSpotterGoldenTest {
    @Test
    fun `fixture sanity covers both phrase orders and the five reference inputs`() {
        val fixture = loadFixture()
        assertEquals(setOf("positive_wake_up_neo", "positive_neo_wake_up"), fixture.streams.keys)
        assertEquals(
            setOf("positive_wake_up_neo", "positive_neo_wake_up", "burst_-30dBFS", "burst_-45dBFS", "silence"),
            fixture.wholeWindow.keys,
        )
        assertEquals(0.45, fixture.threshold, 1e-9)
        assertEquals(WakeSpotter.RELEASE_FRACTION * fixture.threshold, fixture.release, 1e-6)
        // Positives score high whole-window, silence and noise stay near background.
        assertTrue(fixture.wholeWindow.getValue("positive_wake_up_neo") > 0.9)
        assertTrue(fixture.wholeWindow.getValue("positive_neo_wake_up") > 0.65)
        assertTrue(fixture.wholeWindow.getValue("silence") < 0.05)
        assertTrue(fixture.wholeWindow.getValue("burst_-30dBFS") < 0.05)
        assertTrue(fixture.wholeWindow.getValue("burst_-45dBFS") < 0.05)
    }

    @Test
    fun `replaying each positive's hop trace through the real gate fires exactly once at the recorded hop`() {
        val fixture = loadFixture()
        for ((name, stream) in fixture.streams) {
            var hop = 0
            val spotter = WakeSpotter(
                threshold = fixture.threshold,
                frontend = { FloatArray(WakeSpotter.LOGMEL_FLOATS) },
                body = {
                    val s = stream.hopScores[hop++].toFloat()
                    // Split the recorded sum across the two wake classes so the
                    // gate is proven on the sum, not on one class.
                    floatArrayOf(1f - s, s * 0.6f, s * 0.4f)
                },
            )
            val fired = mutableListOf<Int>()
            for (i in stream.hopScores.indices) {
                val step = spotter.process(ShortArray(WakeSpotter.ADVANCE_SAMPLES))
                assertEquals(i, step.stepIndex)
                assertEquals("$name hop $i", stream.hopScores[i], step.score!!, 1e-5)
                if (step.fired) fired.add(i)
            }
            assertEquals("$name: exactly one fire at the recorded hop", listOf(stream.expectedFireHop), fired)
            assertEquals("$name: the rig recorded the same fire list", stream.fires, fired)
            // The recorded hop is the one ending 3120 ms into the padded stream
            // (1.5 s lead + ~1.5 s phrase + one 80 ms hop of confirm).
            assertEquals("$name", 38, stream.expectedFireHop)
        }
    }
}
