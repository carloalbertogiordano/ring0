package ring.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitnessTest {
    @Test fun `vo2max follows the heart rate ratio method with a wider band for a predicted max`() {
        val predicted = assertNotNull(Fitness.vo2max(hrMax = 190, hrRest = 50.0, hrMaxMeasured = false, ageYears = 30))
        assertEquals(58.14, predicted.value, 0.01)
        assertEquals(58.14 * (1 - 0.078), predicted.low, 0.01)
        assertEquals(58.14 * (1 + 0.078), predicted.high, 0.01)
        val measured = assertNotNull(Fitness.vo2max(190, 50.0, hrMaxMeasured = true, ageYears = 30))
        assertEquals(58.14 * (1 - 0.045), measured.low, 0.01)
    }

    @Test fun `older adults are flagged as less accurate`() {
        assertFalse(assertNotNull(Fitness.vo2max(180, 60.0, false, ageYears = 35)).limitedAccuracy)
        assertTrue(assertNotNull(Fitness.vo2max(170, 60.0, false, ageYears = 55)).limitedAccuracy)
    }

    @Test fun `impossible inputs give nothing`() {
        assertNull(Fitness.vo2max(190, 0.0, false, 30))
        assertNull(Fitness.vo2max(60, 70.0, false, 30))
    }
}

class HeartRateRecoveryTest {
    private fun hr(vararg v: Double) = v.mapIndexed { i, x -> TimedValue(i * 300L, x) }

    @Test fun `a sharp fall after a high peak is a recovery event`() {
        val events = HeartRateRecovery.events(hr(120.0, 150.0, 165.0, 130.0, 100.0), hrMax = 190)
        assertEquals(listOf(RecoveryEvent(peakTs = 600L, peakHr = 165.0, drop5min = 35.0)), events)
    }

    @Test fun `low peaks, slow falls and gaps are ignored`() {
        assertEquals(emptyList(), HeartRateRecovery.events(hr(90.0, 110.0, 95.0), hrMax = 190))
        assertEquals(emptyList(), HeartRateRecovery.events(hr(150.0, 160.0, 155.0), hrMax = 190))
        val gap = listOf(TimedValue(0, 165.0), TimedValue(900, 120.0))
        assertEquals(emptyList(), HeartRateRecovery.events(gap, hrMax = 190))
    }
}

class ApneaIndicatorTest {
    private fun night(vararg v: Double) = v.mapIndexed { i, x -> TimedValue(i * 3600L, x) }

    @Test fun `repeated dips below the night median flag the night`() {
        val flagged = ApneaIndicator.night(night(96.0, 96.0, 92.0, 96.0, 91.0, 96.0, 96.0))
        assertEquals(2, flagged.dips)
        assertTrue(flagged.flagged)
    }

    @Test fun `a very low value flags the night by itself`() {
        assertTrue(ApneaIndicator.night(night(96.0, 96.0, 87.0, 96.0, 96.0, 96.0)).flagged)
    }

    @Test fun `a steady night and a too short night are not flagged`() {
        assertFalse(ApneaIndicator.night(night(96.0, 95.0, 96.0, 97.0, 96.0, 95.0)).flagged)
        assertFalse(ApneaIndicator.night(night(96.0, 90.0, 85.0)).flagged)
    }

    @Test fun `three flagged nights out of the last seven make a pattern`() {
        val flagged = ApneaNight(dips = 2, lowest = 90.0, flagged = true)
        val calm = ApneaNight(dips = 0, lowest = 95.0, flagged = false)
        assertTrue(ApneaIndicator.pattern(listOf(flagged, calm, flagged, calm, calm, flagged, calm)))
        assertFalse(ApneaIndicator.pattern(listOf(flagged, calm, flagged, calm)))
    }
}

class HabitEffectTest {
    @Test fun `groups far apart show a large negative effect`() {
        val e = assertNotNull(HabitEffect.compare(with = listOf(40.0, 42.0, 38.0, 41.0, 39.0), without = listOf(50.0, 52.0, 48.0, 51.0, 49.0)))
        assertEquals(-20.0, e.percent, 1e-9)
        assertEquals(-6.32, e.cohenD, 0.01)
        assertTrue(e.noteworthy)
    }

    @Test fun `similar groups are not noteworthy`() {
        val e = assertNotNull(HabitEffect.compare(with = listOf(50.0, 52.0, 48.0, 51.0, 49.0), without = listOf(50.5, 51.5, 49.0, 50.0, 50.0)))
        assertFalse(e.noteworthy)
    }

    @Test fun `too few events give no result`() {
        assertNull(HabitEffect.compare(with = listOf(40.0, 41.0, 42.0, 43.0), without = List(10) { 50.0 }))
        assertNull(HabitEffect.compare(with = List(10) { 40.0 }, without = listOf(50.0, 51.0)))
    }
}
