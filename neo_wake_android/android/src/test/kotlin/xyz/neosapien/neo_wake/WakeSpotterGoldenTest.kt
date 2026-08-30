package xyz.neosapien.neo_wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Replays `src/test/resources/wakeword/golden_vectors.json` (copied verbatim
 * from the app repo's `test/fixtures/wakeword/golden_vectors.json`, generated
 * by `python script/wakeword_reference.py --synth 64000 --threshold 0.3
 * --out test/fixtures/wakeword/golden_vectors.json`) through the real
 * [WakeSpotter], pinning the chain's stride/buffer/ring COUNTS against the
 * Python reference rig's own bookkeeping (KTD7, evidence for R7).
 *
 * 1:1 port of `test/core/neo_agent/wake_spotter_golden_test.dart` -- read that
 * file's header before trusting a green run here for more than it proves.
 * Short version: the fixture is a deterministic synthetic signal, not speech.
 * A green run here proves the mel buffer fills to exactly 76 frames, the
 * embedding ring fills to exactly 16, and [WakeSpotter.process] passes the
 * classify hook's return straight through as its score with no extra
 * transform -- on the SAME steps the Python rig recorded. It does NOT and
 * CANNOT prove the chain fires on real speech, or the fire-lag figure R13
 * asks for. Both need a real "Neo SimSim" recording -- see the `@Ignore`d
 * tests at the bottom of this file.
 *
 * The mel and embed hooks below are STUBS, not golden numbers: raw per-step
 * mel values aren't in golden_vectors.json (only mel_frames_added, a constant
 * 8, is -- asserted below), and embed is substituted wholesale with the
 * fixture's recorded embeddings regardless of what the mel hook returns. Real
 * per-step ONNX numerics are U6's other half, not this file's job.
 *
 * Plain JVM test (no Android framework, no ORT), same as [WakeSpotterTest] --
 * runs under a bare `gradle test`. JSON is parsed with the tiny recursive-
 * descent parser below rather than a new dependency: org.json is stubbed out
 * under Robolectric-less JVM unit tests (`returnDefaultValues = true` in
 * build.gradle), and this fixture's schema is small and fixed.
 */
private class GoldenStep(
    val melFramesAdded: Int,
    val embeddingPresent: Boolean,
    val embedding: List<Double>?,
    val score: Double?,
)

private class GoldenFixture(
    val threshold: Double,
    val inputSource: String,
    val steps: List<GoldenStep>,
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

private fun loadFixture(): GoldenFixture {
    val stream = object {}.javaClass.getResourceAsStream("/wakeword/golden_vectors.json")
        ?: error("golden_vectors.json missing from test resources")
    val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Suppress("UNCHECKED_CAST")
    val json = JsonParser(text).parse() as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val input = json["input"] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val rawSteps = json["steps"] as List<Map<String, Any?>>

    val steps = rawSteps.map { step ->
        @Suppress("UNCHECKED_CAST")
        val embedding = step["embedding"] as? List<Double>
        GoldenStep(
            melFramesAdded = (step["mel_frames_added"] as Double).toInt(),
            embeddingPresent = step["embedding_present"] as Boolean,
            embedding = embedding,
            score = step["score"] as? Double,
        )
    }

    return GoldenFixture(
        threshold = json["threshold"] as Double,
        inputSource = input["source"] as String,
        steps = steps,
    )
}

class WakeSpotterGoldenTest {
    @Test
    fun `fixture sanity synthetic input long enough to pass the 16-embedding warm-up`() {
        val fixture = loadFixture()
        assertEquals("synth", fixture.inputSource)
        // Warm-up needs 25 steps (76-frame mel fill + 16-embedding ring); demand
        // real margin past it so a shrunk fixture would fail loudly here first.
        assertTrue(fixture.steps.size >= WakeSpotter.EMBEDDING_RING_DEPTH + 10)
    }

    @Test
    fun `replaying the fixture through the real chain reproduces its frame and embedding counts exactly and its score trace within 1e-4`() {
        val fixture = loadFixture()
        val steps = fixture.steps

        val embedStepIndices = steps.indices.filter { steps[it].embeddingPresent }
        val scoreStepIndices = steps.indices.filter { steps[it].score != null }

        var embedCallIndex = 0
        var classifyCallIndex = 0

        val spotter = WakeSpotter(
            threshold = fixture.threshold,
            mel = { FloatArray(WakeSpotter.MEL_FRAMES_PER_STEP * WakeSpotter.MEL_BIN_COUNT) },
            embed = { _, _ ->
                // Indexing past embedStepIndices throws (failing the test) --
                // that's the check for WakeSpotter embedding on MORE steps than
                // the fixture recorded; the count assert below catches fewer.
                val stepIndex = embedStepIndices[embedCallIndex]
                embedCallIndex++
                steps[stepIndex].embedding!!.map { it.toFloat() }.toFloatArray()
            },
            classify = { _, _ ->
                val stepIndex = scoreStepIndices[classifyCallIndex]
                classifyCallIndex++
                steps[stepIndex].score!!
            },
        )

        for (i in steps.indices) {
            val step = steps[i]
            // Content is irrelevant: mel is a stub and embed is substituted
            // wholesale, so no assertion here depends on these bytes.
            val frame = ShortArray(WakeSpotter.ADVANCE_SAMPLES)
            val result = spotter.process(frame)

            assertEquals(i, result.stepIndex)
            // Pins R3's 8-frames-per-80ms-step against the Python rig's own count.
            assertEquals(WakeSpotter.MEL_FRAMES_PER_STEP, step.melFramesAdded)

            assertEquals(
                "step $i: embedding_present=${step.embeddingPresent} should match a " +
                    "full ${WakeSpotter.MEL_BUFFER_FRAMES}-frame mel buffer",
                step.embeddingPresent,
                spotter.melBufferLength == WakeSpotter.MEL_BUFFER_FRAMES,
            )

            val expectedScore = step.score
            if (expectedScore == null) {
                assertNull("step $i", result.score)
            } else {
                assertNotNull("step $i", result.score)
                assertEquals("step $i", expectedScore, result.score!!, 1e-4)
            }
        }

        // Exact counts (KTD7): fewer calls than the fixture recorded would pass
        // every per-step check above yet still be a warm-up regression.
        assertEquals(embedStepIndices.size, embedCallIndex)
        assertEquals(scoreStepIndices.size, classifyCallIndex)
    }

    // R13 asks for the fire-lag figure to be RE-MEASURED, not remembered, on
    // every model swap -- these are the two tests that would do that. Written
    // here, `@Ignore`d, rather than left off the plan entirely, so the gap
    // shows in `gradle test` output instead of only in a doc.
    //
    // Unblocking needs, in order (see wake_spotter_golden_test.dart's group
    // doc comment for the full sequence):
    //   1. A real recording of "Neo SimSim" as raw 16 kHz mono signed-16-bit
    //      PCM, saved to test/fixtures/wakeword/neo_simsim_16k.pcm in the app
    //      repo (KD9 forbids this ever being a captured-on-device artifact).
    //   2. Its phrase_end_sample labelled by ear/waveform.
    //   3. Regenerate golden_vectors.json from that PCM via
    //      script/wakeword_reference.py, which fills in fire_step_index and
    //      fire_lag_ms.
    //   4. Re-copy the fixture here, un-`@Ignore` these two tests, assert
    //      against those two fields.
    //
    // Do NOT fill this in with a synthesised utterance or an invented lag
    // number -- either would ship a geometry fault dressed as a passing test.
    @Test
    @Ignore("blocked on test/fixtures/wakeword/neo_simsim_16k.pcm; see this file's doc comment")
    fun `the fixture is detected at the configured threshold`() {
        throw AssertionError("unreachable: this test is ignored until neo_simsim_16k.pcm exists")
    }

    @Test
    @Ignore("blocked on test/fixtures/wakeword/neo_simsim_16k.pcm; see this file's doc comment")
    fun `the score crosses the threshold exactly the configured lag after phrase_end_sample`() {
        throw AssertionError("unreachable: this test is ignored until neo_simsim_16k.pcm exists")
    }
}
