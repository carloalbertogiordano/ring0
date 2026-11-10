package ring.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val HOUR = 3600L

private fun hourly(vararg levels: Double): List<TimedValue> = levels.mapIndexed { i, v -> TimedValue(i * HOUR, v) }

class BatteryForecastTest {
    @Test fun `a steady discharge predicts the hours left`() {
        val forecast = assertNotNull(BatteryForecast.estimate(hourly(100.0, 98.0, 96.0, 94.0, 92.0, 90.0, 88.0, 86.0, 84.0, 82.0, 80.0)))
        assertEquals(2.0, forecast.percentPerHour, 1e-9)
        assertEquals(40.0, forecast.hoursLeft, 1e-9)
    }

    @Test fun `samples before the last charge are ignored`() {
        val samples = hourly(30.0, 40.0, 60.0, 100.0, 98.0, 96.0, 94.0, 92.0)
        val forecast = assertNotNull(BatteryForecast.estimate(samples))
        assertEquals(2.0, forecast.percentPerHour, 1e-9)
        assertEquals(46.0, forecast.hoursLeft, 1e-9)
    }

    @Test fun `one point jitter does not count as charging`() {
        val forecast = assertNotNull(BatteryForecast.estimate(hourly(80.0, 79.0, 80.0, 78.0, 77.0, 76.0, 75.0)))
        assertEquals(87.5, forecast.hoursLeft, 0.01)
    }

    @Test fun `too little or flat or charging data gives no forecast`() {
        assertNull(BatteryForecast.estimate(hourly(90.0, 89.0)))
        assertNull(BatteryForecast.estimate(hourly(90.0, 90.0, 90.0, 90.0, 90.0)))
        assertNull(BatteryForecast.estimate(hourly(50.0, 60.0, 70.0, 80.0, 90.0)))
        assertNull(BatteryForecast.estimate(emptyList()))
    }

    @Test fun `a short span is not enough`() {
        val quick = listOf(TimedValue(0, 90.0), TimedValue(600, 89.0), TimedValue(1200, 88.0), TimedValue(1800, 87.0))
        assertNull(BatteryForecast.estimate(quick))
    }
}

class WearTest {
    private val slot = 300L

    private fun worn(from: Int, to: Int, value: Double = 70.0) = (from until to).map { TimedValue(it * slot, value) }

    @Test fun `a long run of missing or zero slots is a gap`() {
        val samples = worn(0, 24) + worn(24, 48, 0.0) + worn(48, 60)
        val gaps = Wear.gaps(samples, from = 0, to = 60 * slot, slotSeconds = slot)
        assertEquals(listOf(WearGap(24 * slot, 48 * slot)), gaps)
    }

    @Test fun `missing slots count the same as zero readings`() {
        val samples = worn(0, 10) + worn(40, 50)
        assertEquals(listOf(WearGap(10 * slot, 40 * slot)), Wear.gaps(samples, 0, 50 * slot, slot))
    }

    @Test fun `short gaps are ignored`() {
        val samples = worn(0, 10) + worn(13, 30)
        assertEquals(emptyList(), Wear.gaps(samples, 0, 30 * slot, slot))
    }

    @Test fun `a trailing gap runs to the end of the window and worn time subtracts gaps`() {
        val samples = worn(0, 20)
        val gaps = Wear.gaps(samples, 0, 40 * slot, slot)
        assertEquals(listOf(WearGap(20 * slot, 40 * slot)), gaps)
        assertEquals(20 * slot, Wear.wornSeconds(0, 40 * slot, gaps))
    }
}
