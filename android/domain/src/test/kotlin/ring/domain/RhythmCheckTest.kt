package ring.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Chunk(val label: String, val rr: List<Int>)

/** 128-beat excerpts from the MIT-BIH Atrial Fibrillation Database (see the header of the CSV). */
private val AFDB: List<Chunk> = RhythmCheckTest::class.java.getResourceAsStream("/afdb_chunks.csv")!!.bufferedReader().readLines()
    .filter { !it.startsWith("#") && it.isNotBlank() }
    .map { line -> line.split(",").let { Chunk(it[0], it.drop(2).map(String::toInt)) } }

/** The ring's raw stream gives contiguous beat series of 40-70 beats between dropouts: model them as two 64-beat segments. */
private fun Chunk.segments(): List<List<Double>> = rr.chunked(64).map { seg -> seg.map { it.toDouble() } }

class RhythmCheckTest {
    @Test fun `features match the python reference on real rr intervals`() {
        val normal = AFDB.first { it.label == "N" }.rr.take(32).map { it.toDouble() }
        assertEquals(0.012332, RhythmCheck.nRmssd(normal), 1e-5)
        assertEquals(2.302585, RhythmCheck.sampleEntropy(normal), 1e-5)
        val af = AFDB.first { it.label == "AF" }.rr.take(32).map { it.toDouble() }
        assertEquals(0.312221, RhythmCheck.nRmssd(af), 1e-5)
        assertEquals(3.0, RhythmCheck.sampleEntropy(af), 1e-5)
    }

    @Test fun `sinus rhythm excerpts are never called irregular`() {
        val normal = AFDB.filter { it.label == "N" }
        assertEquals(10, normal.size)
        normal.forEach { assertEquals(RhythmLevel.REGULAR, RhythmCheck.analyze(it.segments()).level) }
    }

    /** Three excerpts have beats faster than 200 bpm, which a pulse sensor cannot time: those intervals are dropped, leaving fewer windows. */
    @Test fun `most atrial fibrillation excerpts are called irregular`() {
        val af = AFDB.filter { it.label == "AF" }
        assertEquals(10, af.size)
        val flagged = af.count { RhythmCheck.analyze(it.segments()).level == RhythmLevel.IRREGULAR }
        assertTrue(flagged >= 8, "flagged $flagged of ${af.size}")
    }

    @Test fun `twenty milliseconds of timing noise keeps the separation`() {
        val noise = java.util.Random(7)

        fun Chunk.noisy() = segments().map { seg -> seg.map { it + noise.nextGaussian() * 20 } }
        val regular = AFDB.filter { it.label == "N" }.count { RhythmCheck.analyze(it.noisy()).level == RhythmLevel.REGULAR }
        val irregular = AFDB.filter { it.label == "AF" }.count { RhythmCheck.analyze(it.noisy()).level == RhythmLevel.IRREGULAR }
        assertTrue(regular >= 8, "regular $regular of 10")
        assertTrue(irregular >= 7, "irregular $irregular of 10")
    }

    @Test fun `a perfectly steady pulse is regular`() {
        val result = RhythmCheck.analyze(listOf(List(64) { 800.0 }, List(64) { 800.0 }))
        assertEquals(RhythmLevel.REGULAR, result.level)
        assertEquals(10, result.windows)
        assertEquals(0, result.irregularWindows)
        assertEquals(128, result.beats)
    }

    @Test fun `too few beats are inconclusive`() {
        assertEquals(RhythmLevel.INCONCLUSIVE, RhythmCheck.analyze(listOf(List(64) { 800.0 })).level)
        assertEquals(RhythmLevel.INCONCLUSIVE, RhythmCheck.analyze(emptyList()).level)
    }

    @Test fun `segments shorter than a window do not count`() {
        val pieces = List(8) { List(20) { 800.0 } }
        assertEquals(RhythmLevel.INCONCLUSIVE, RhythmCheck.analyze(pieces).level)
    }

    @Test fun `a recording with the arm moving is inconclusive however regular the beats look`() {
        val segments = listOf(List(64) { 800.0 }, List(64) { 800.0 })

        fun beats(motion: Double) = PpgBeats(emptyList(), segments.flatten(), 150.0, 0.04, segments, motion, 0.9)
        assertEquals(RhythmLevel.REGULAR, RhythmCheck.analyze(beats(0.05)).level)
        assertEquals(RhythmLevel.INCONCLUSIVE, RhythmCheck.analyze(beats(0.3)).level)
    }

    @Test fun `impossible intervals make the check inconclusive`() {
        val noisy = List(2) { List(64) { i -> if (i % 4 == 0) 120.0 else 800.0 } }
        assertEquals(RhythmLevel.INCONCLUSIVE, RhythmCheck.analyze(noisy).level)
    }
}

class RhythmHistoryTest {
    private val now = 10_000_000L
    private val day = 86_400L

    @Test fun `no irregular result means no advice`() {
        assertEquals(RhythmAdvice.NONE, RhythmHistory.advice(emptyList(), now))
    }

    @Test fun `one irregular result in the week asks to check again`() {
        assertEquals(RhythmAdvice.RECHECK, RhythmHistory.advice(listOf(now - day), now))
    }

    @Test fun `repeated irregular results at different times suggest seeing a doctor`() {
        assertEquals(RhythmAdvice.SEE_DOCTOR, RhythmHistory.advice(listOf(now - 2 * day, now - 3600), now))
    }

    @Test fun `two results minutes apart count as one and old ones are forgotten`() {
        assertEquals(RhythmAdvice.RECHECK, RhythmHistory.advice(listOf(now - 600, now - 60), now))
        assertEquals(RhythmAdvice.NONE, RhythmHistory.advice(listOf(now - 9 * day), now))
    }
}
