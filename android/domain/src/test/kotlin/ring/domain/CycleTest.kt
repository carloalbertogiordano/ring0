package ring.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Nightly skin temperature with a little deterministic noise around [level]. */
private fun nights(count: Int, level: Double, startIndex: Int = 0): List<Double?> =
    List(count) { level + (((startIndex + it) * 7) % 5 - 2) * 0.02 }

class CycleTest {
    @Test fun `a flat series shows no rise`() {
        assertEquals(CyclePhase.NO_RISE_YET, CycleTemperature.estimate(nights(24, 33.0)).phase)
    }

    @Test fun `a sustained rise after a flat stretch is post ovulation`() {
        val series = nights(14, 33.0) + nights(8, 33.4, 14)
        val estimate = CycleTemperature.estimate(series)
        assertEquals(CyclePhase.AFTER_RISE, estimate.phase)
        assertEquals(7, estimate.nightsSinceRise)
    }

    @Test fun `one hot night is not a rise`() {
        val series = nights(14, 33.0) + listOf(33.7) + nights(7, 33.0, 15)
        assertEquals(CyclePhase.NO_RISE_YET, CycleTemperature.estimate(series).phase)
    }

    @Test fun `a small rise under 0 2 degrees is not enough`() {
        val series = nights(14, 33.0) + nights(8, 33.1, 14)
        assertEquals(CyclePhase.NO_RISE_YET, CycleTemperature.estimate(series).phase)
    }

    @Test fun `when temperature falls back the phase is over`() {
        val series = nights(14, 33.0) + nights(10, 33.4, 14) + nights(4, 33.0, 24)
        assertEquals(CyclePhase.NO_RISE_YET, CycleTemperature.estimate(series).phase)
    }

    @Test fun `a few missing nights are tolerated`() {
        val series = (nights(14, 33.0) + nights(8, 33.4, 14)).toMutableList<Double?>().also {
            it[3] = null
            it[9] = null
            it[17] = null
        }
        assertEquals(CyclePhase.AFTER_RISE, CycleTemperature.estimate(series).phase)
    }

    @Test fun `too little data gives no estimate`() {
        assertEquals(CyclePhase.NOT_ENOUGH_DATA, CycleTemperature.estimate(nights(10, 33.0)).phase)
        val sparse = List(30) { if (it % 3 == 0) 33.0 else null }
        assertEquals(CyclePhase.NOT_ENOUGH_DATA, CycleTemperature.estimate(sparse).phase)
    }

    @Test fun `a rise that is too old is no longer reported`() {
        val series = nights(10, 33.0) + nights(30, 33.4, 10)
        assertNull(CycleTemperature.estimate(series).nightsSinceRise)
    }
}
