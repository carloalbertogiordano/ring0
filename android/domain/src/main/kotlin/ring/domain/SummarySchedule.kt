package ring.domain

enum class SummarySlot { MORNING, EVENING }

/** When the daily summaries go out: one in the morning (07:00-12:00) and one in the evening (20:30-23:30), once per day each. */
object SummarySchedule {
    private val WINDOWS = mapOf(
        SummarySlot.MORNING to (7 * 60 until 12 * 60),
        SummarySlot.EVENING to (20 * 60 + 30 until 23 * 60 + 30),
    )

    /** The slot to send now, or null. `today` is the local epoch day; `lastSent` maps a slot to the epoch day it was last sent. */
    fun due(minuteOfDay: Int, today: Long, lastSent: Map<SummarySlot, Long>): SummarySlot? =
        WINDOWS.entries.firstOrNull { (slot, window) -> minuteOfDay in window && lastSent[slot] != today }?.key
}
