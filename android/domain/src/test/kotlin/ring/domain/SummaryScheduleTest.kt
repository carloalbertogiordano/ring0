package ring.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SummaryScheduleTest {
    private val today = 20_000L

    @Test fun `the morning summary is due from 7 until noon`() {
        assertEquals(SummarySlot.MORNING, SummarySchedule.due(minuteOfDay = 7 * 60, today = today, lastSent = emptyMap()))
        assertEquals(SummarySlot.MORNING, SummarySchedule.due(11 * 60 + 59, today, emptyMap()))
        assertNull(SummarySchedule.due(6 * 60 + 59, today, emptyMap()))
        assertNull(SummarySchedule.due(12 * 60, today, emptyMap()))
    }

    @Test fun `the evening summary is due from 20 30 until 23 30`() {
        assertEquals(SummarySlot.EVENING, SummarySchedule.due(20 * 60 + 30, today, emptyMap()))
        assertEquals(SummarySlot.EVENING, SummarySchedule.due(23 * 60 + 29, today, emptyMap()))
        assertNull(SummarySchedule.due(20 * 60 + 29, today, emptyMap()))
        assertNull(SummarySchedule.due(23 * 60 + 30, today, emptyMap()))
    }

    @Test fun `a slot already sent today is not due again but tomorrow it is`() {
        val sent = mapOf(SummarySlot.MORNING to today)
        assertNull(SummarySchedule.due(8 * 60, today, sent))
        assertEquals(SummarySlot.MORNING, SummarySchedule.due(8 * 60, today + 1, sent))
        assertEquals(SummarySlot.EVENING, SummarySchedule.due(21 * 60, today, sent))
    }

    @Test fun `between the windows nothing is due`() {
        assertNull(SummarySchedule.due(15 * 60, today, emptyMap()))
    }
}
