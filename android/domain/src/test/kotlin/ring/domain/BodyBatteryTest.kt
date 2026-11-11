package ring.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SLOT = 1800L

private fun slots(count: Int, sleep: Double = 0.0, stress: Double? = 0.0, trimp: Double = 0.0) =
    List(count) { EnergySlot(it * SLOT, sleep, stress, trimp) }

class BodyBatteryTest {
    @Test fun `eight hours of sleep recharge about 96 points and stop at 100`() {
        val series = BodyBattery.simulate(slots(16, sleep = 1.0), start = 20.0)
        assertEquals(16, series.size)
        assertEquals(68.0, series[7].value, 1e-9) // after 8 slots (4 h)
        assertEquals(100.0, series.last().value, 1e-9)
    }

    @Test fun `calm wakefulness recovers a little`() {
        val series = BodyBattery.simulate(slots(16, stress = 0.0), start = 80.0)
        assertEquals(84.8, series.last().value, 1e-9)
    }

    @Test fun `high stress drains faster`() {
        val series = BodyBattery.simulate(slots(16, stress = 80.0), start = 80.0)
        assertEquals(51.2, series.last().value, 1e-9)
    }

    @Test fun `training load drains energy`() {
        val calm = BodyBattery.simulate(slots(1), start = 80.0).single().value
        val exercised = BodyBattery.simulate(slots(1, trimp = 60.0), start = 80.0).single().value
        assertEquals(15.0, calm - exercised, 1e-9)
    }

    @Test fun `partial sleep mixes recharge and drain`() {
        val value = BodyBattery.simulate(slots(1, sleep = 0.5, stress = 50.0), start = 50.0).single().value
        assertEquals(52.325, value, 1e-9)
    }

    @Test fun `unknown stress is treated as moderate`() {
        val value = BodyBattery.simulate(slots(1, stress = null), start = 50.0).single().value
        assertEquals(48.8, value, 1e-9)
    }

    @Test fun `the level never leaves 0 to 100`() {
        val low = BodyBattery.simulate(slots(10, stress = 100.0, trimp = 80.0), start = 5.0)
        assertTrue(low.all { it.value in 0.0..100.0 })
        assertEquals(0.0, low.last().value)
    }

    @Test fun `slots without data keep the level instead of draining it`() {
        val unobserved = List(4) { EnergySlot(it * SLOT, 0.0, null, 0.0, observed = false) }
        assertEquals(listOf(60.0, 60.0, 60.0, 60.0), BodyBattery.simulate(unobserved, start = 60.0).map { it.value })
        val mixed = unobserved + EnergySlot(4 * SLOT, 0.0, 0.0, 0.0)
        assertEquals(60.3, BodyBattery.simulate(mixed, start = 60.0).last().value, 1e-9)
    }

    @Test fun `timestamps follow the slots`() {
        val series = BodyBattery.simulate(slots(3), start = 50.0)
        assertEquals(listOf(0L, SLOT, 2 * SLOT), series.map { it.ts })
    }

    @Test fun `sleep quality weights deep and rem fully and light sleep less`() {
        assertEquals(0.7, BodyBattery.sleepQuality(lightMin = 480, deepMin = 0, remMin = 0, inBedMin = 480), 1e-9)
        assertEquals(1.0, BodyBattery.sleepQuality(lightMin = 0, deepMin = 240, remMin = 240, inBedMin = 480), 1e-9)
        assertEquals(0.5, BodyBattery.sleepQuality(lightMin = 0, deepMin = 240, remMin = 0, inBedMin = 480), 1e-9)
        assertEquals(0.0, BodyBattery.sleepQuality(lightMin = 0, deepMin = 0, remMin = 0, inBedMin = 0), 1e-9)
    }
}
