package ring.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val SLOT_S = 300L

class SleepScoringTest {
    private fun summary(total: Int, inBed: Int, waso: Int, deepPct: Double, remPct: Double) = SleepSummary(
        timeInBedMin = inBed,
        totalSleepMin = total,
        efficiency = total.toDouble() / inBed,
        latencyMin = 10,
        wasoMin = waso,
        lightPct = 100 - deepPct - remPct,
        deepPct = deepPct,
        remPct = remPct,
    )

    @Test fun `a good night scores high`() {
        assertEquals(95, SleepScoring.score(summary(total = 450, inBed = 480, waso = 10, deepPct = 20.0, remPct = 25.0), targetMin = 480))
    }

    @Test fun `a short fragmented night scores low`() {
        assertEquals(30, SleepScoring.score(summary(total = 240, inBed = 400, waso = 90, deepPct = 5.0, remPct = 5.0), targetMin = 480))
    }

    @Test fun `without reported awake time only duration and deep plus rem count`() {
        // 300 of 480 minutes (0.625) and 40% deep + REM: (0.5 x 0.625 + 0.2) / 0.7
        val s = summary(total = 300, inBed = 301, waso = 0, deepPct = 30.0, remPct = 10.0)
        assertEquals(73, SleepScoring.score(s, targetMin = 480, awakeReported = false))
    }

    @Test fun `a perfect night is 100 and the score never leaves 0 to 100`() {
        assertEquals(100, SleepScoring.score(summary(total = 540, inBed = 550, waso = 0, deepPct = 25.0, remPct = 25.0), targetMin = 480))
        assertEquals(0, SleepScoring.score(summary(total = 0, inBed = 480, waso = 200, deepPct = 0.0, remPct = 0.0), targetMin = 480))
    }
}

class NapDetectorTest {
    private fun quiet(from: Int, count: Int, hr: Double = 58.0) = (from until from + count).map { TimedValue(it * SLOT_S, hr) }

    @Test fun `a run of calm low heart rate samples is a nap`() {
        val naps = NapDetector.detect(quiet(100, 5), restingHr = 55.0, sleepWindows = emptyList())
        assertEquals(listOf(Nap(100 * SLOT_S, 105 * SLOT_S)), naps)
    }

    @Test fun `too short or too restless is not a nap`() {
        assertEquals(emptyList(), NapDetector.detect(quiet(100, 3), 55.0, emptyList()))
        val restless = quiet(100, 2, 56.0) + quiet(102, 2, 66.0)
        assertEquals(emptyList(), NapDetector.detect(restless, 55.0, emptyList()))
    }

    @Test fun `a high or missing heart rate breaks the run`() {
        val samples = quiet(100, 3) + listOf(TimedValue(103 * SLOT_S, 0.0)) + quiet(104, 3)
        assertEquals(emptyList(), NapDetector.detect(samples, 55.0, emptyList()))
    }

    @Test fun `the main night is not a nap`() {
        val window = LongRange(90 * SLOT_S, 120 * SLOT_S)
        assertEquals(emptyList(), NapDetector.detect(quiet(100, 8), 55.0, listOf(window)))
    }

    @Test fun `two separate naps are both reported`() {
        val samples = quiet(100, 5) + quiet(150, 6)
        assertEquals(2, NapDetector.detect(samples, 55.0, emptyList()).size)
    }
}

class BedtimeTest {
    @Test fun `bedtime is the usual wake time minus the sleep target and a wind down`() {
        assertEquals(22 * 60 + 45, Bedtime.suggest(List(5) { 7 * 60 }, targetMin = 480))
    }

    @Test fun `too few nights gives no suggestion`() {
        assertNull(Bedtime.suggest(listOf(420, 430), targetMin = 480))
    }
}

class SleepTotalsTest {
    @Test fun `a summary can be built from stage minutes alone`() {
        val s = SleepMetrics.fromTotals(startEpoch = 0, endEpoch = 8 * 3600L, lightMin = 240, deepMin = 90, remMin = 90, awakeMin = 30)
        assertEquals(480, s.timeInBedMin)
        assertEquals(420, s.totalSleepMin)
        assertEquals(0.875, s.efficiency, 1e-9)
        assertEquals(30, s.wasoMin)
        assertEquals(240 * 100.0 / 420, s.lightPct, 1e-9)
    }
}
