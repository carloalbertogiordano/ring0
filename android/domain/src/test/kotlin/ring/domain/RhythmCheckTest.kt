package ring.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Chunk(val label: String, val rr: List<Int>)

/** 128-beat excerpts from the MIT-BIH Atrial Fibrillation Database (see the header of the CSV). */
private val AFDB: List<Chunk> = RhythmCheckTest::class.java.getResourceAsStream("/afdb_chunks.csv")!!.bufferedReader().readLines()
    .filter { !it.startsWith("#") && it.isNotBlank() }
    .map { line -> line.split(",").let { Chunk(it[0], it.drop(2).map(String::toInt)) } }

class RhythmCheckTest {
    @Test fun `features match the python reference on real rr intervals`() {
        val normal = AFDB.first { it.label == "N" }.rr.take(64).map { it.toDouble() }
        assertEquals(0.010604, RhythmCheck.nRmssd(normal), 1e-5)
        assertEquals(1.734601, RhythmCheck.sampleEntropy(normal), 1e-5)
        val af = AFDB.first { it.label == "AF" }.rr.take(64).map { it.toDouble() }
        assertEquals(0.273207, RhythmCheck.nRmssd(af), 1e-5)
        assertEquals(1.871802, RhythmCheck.sampleEntropy(af), 1e-5)
    }

    @Test fun `sinus rhythm excerpts are never called irregular`() {
        val normal = AFDB.filter { it.label == "N" }
        assertEquals(10, normal.size)
        normal.forEach { assertEquals(RhythmLevel.REGULAR, RhythmCheck.analyze(it.rr).level) }
    }

    @Test fun `most atrial fibrillation excerpts are called irregular`() {
        val af = AFDB.filter { it.label == "AF" }
        assertEquals(10, af.size)
        val flagged = af.count { RhythmCheck.analyze(it.rr).level == RhythmLevel.IRREGULAR }
        assertTrue(flagged >= 8, "flagged $flagged of ${af.size}")
    }

    @Test fun `a perfectly steady pulse is regular`() {
        val result = RhythmCheck.analyze(List(128) { 800 })
        assertEquals(RhythmLevel.REGULAR, result.level)
        assertEquals(0.0, result.nRmssd ?: -1.0, 1e-9)
    }

    @Test fun `too few beats are inconclusive`() {
        assertEquals(RhythmLevel.INCONCLUSIVE, RhythmCheck.analyze(List(60) { 800 }).level)
        assertEquals(RhythmLevel.INCONCLUSIVE, RhythmCheck.analyze(emptyList()).level)
    }

    @Test fun `too many impossible values are inconclusive`() {
        val noisy = List(128) { if (it % 4 == 0) 120 else 800 }
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
