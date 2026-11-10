package ring.app.sync

import ring.app.data.RingDao
import ring.app.data.SampleType
import ring.domain.TimedValue
import ring.domain.Wear
import ring.domain.WearGap
import ring.protocol.commands.Reading
import java.time.Instant

/**
 * Leaves out what the ring recorded while it was not worn. The heart rate log is the reference: a stretch of at least 30
 * minutes without heart rate is a gap, and readings of the other signals inside it (skin temperature of an empty ring,
 * stress or HRV from no pulse) are dropped. Days with too little heart rate data are left alone, because then the
 * missing readings say nothing about wearing.
 */
class WearFilter(private val dao: RingDao) {
    suspend fun keepWorn(readings: List<Reading>): List<Reading> {
        if (readings.isEmpty()) return readings
        val times = readings.map { it.time.epochSecond }
        val gaps = gapsBetween(times.min(), times.max() + SLOT_SECONDS)
        return readings.filterNot { Wear.inGap(it.time.epochSecond, gaps) }
    }

    /** One-time cleanup of data stored before the filter existed. */
    suspend fun cleanHistory(now: Long = Instant.now().epochSecond) {
        for (gap in gapsBetween(now - HISTORY_DAYS * DAY, now)) {
            for (type in FILTERED_TYPES) dao.deleteRange(type, gap.start, gap.end)
        }
    }

    private suspend fun gapsBetween(from: Long, to: Long): List<WearGap> {
        val firstDay = from / DAY * DAY
        val hr = dao.range(SampleType.HR, firstDay, to).map { TimedValue(it.ts, it.value) }
        return hr.groupBy { it.ts / DAY * DAY }
            .filterValues { day -> day.count { it.value > 0 } >= MIN_DAY_SAMPLES }
            .flatMap { (dayStart, samples) -> Wear.gaps(samples, dayStart, minOf(dayStart + DAY, to + SLOT_SECONDS), SLOT_SECONDS) }
    }

    companion object {
        val FILTERED_TYPES = listOf(SampleType.HRV, SampleType.STRESS, SampleType.TEMP, SampleType.SPO2)
        private const val SLOT_SECONDS = 300L
        private const val DAY = 86_400L
        private const val HISTORY_DAYS = 60
        private const val MIN_DAY_SAMPLES = 36
    }
}
