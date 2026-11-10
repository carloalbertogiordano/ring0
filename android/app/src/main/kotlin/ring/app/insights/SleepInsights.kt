package ring.app.insights

import ring.app.data.RingDao
import ring.app.data.SampleType
import ring.app.data.SleepEntity
import ring.domain.Bedtime
import ring.domain.Nap
import ring.domain.NapDetector
import ring.domain.SleepMetrics
import ring.domain.SleepRegularity
import ring.domain.SleepScoring
import ring.domain.SleepSummary
import ring.domain.TimedValue
import java.time.Instant
import java.time.ZoneId

data class SleepReport(
    val lastNight: SleepSummary?,
    val score: Int?,
    val debtMin: Int,
    val regularity: Int?,
    /** Suggested bedtime as minute of the local day, null until a few nights are known. */
    val bedtimeMinute: Int?,
    val naps: List<Nap>,
    val targetMin: Int,
)

/** Sleep score, debt, regularity, suggested bedtime and possible naps from the stored sleep sessions. */
class SleepInsights(private val dao: RingDao, private val zone: ZoneId = ZoneId.systemDefault()) {
    private fun minuteOfDay(epoch: Long): Int = Instant.ofEpochSecond(epoch).atZone(zone).toLocalTime().let { it.hour * MINUTES_PER_HOUR + it.minute }

    private fun SleepEntity.summary() = SleepMetrics.fromTotals(start, end, lightMin, deepMin, remMin, awakeMin)

    suspend fun report(targetMin: Int, restingHr: Double?, now: Long = Instant.now().epochSecond): SleepReport {
        val nights = dao.sleepSince(now - HISTORY_DAYS * DAY).filter { it.lightMin + it.deepMin + it.remMin >= MIN_NIGHT_SLEEP_MIN }.sortedBy { it.start }
        val last = nights.lastOrNull()?.takeIf { now - it.end <= RECENT_NIGHT_SECONDS }
        val lastSummary = last?.summary()
        return SleepReport(
            lastNight = lastSummary,
            score = lastSummary?.let { SleepScoring.score(it, targetMin) },
            debtMin = SleepMetrics.sleepDebtMin(nights.map { it.lightMin + it.deepMin + it.remMin }, targetMin),
            regularity = SleepRegularity.score(nights.map { minuteOfDay(it.start) }),
            bedtimeMinute = Bedtime.suggest(nights.map { minuteOfDay(it.end) }, targetMin),
            naps = if (restingHr == null) emptyList() else naps(nights, restingHr, now),
            targetMin = targetMin,
        )
    }

    private suspend fun naps(nights: List<SleepEntity>, restingHr: Double, now: Long): List<Nap> {
        val dayStart = Instant.ofEpochSecond(now).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
        val hr = dao.range(SampleType.HR, dayStart, now).map { TimedValue(it.ts, it.value) }
        return NapDetector.detect(hr, restingHr, nights.map { it.start..it.end })
            .filter { minuteOfDay(it.start) in NAP_FROM_MIN until NAP_TO_MIN }
    }

    private companion object {
        const val DAY = 86_400L
        const val HISTORY_DAYS = 8
        const val MIN_NIGHT_SLEEP_MIN = 180
        const val RECENT_NIGHT_SECONDS = 36 * 3_600L
        const val MINUTES_PER_HOUR = 60
        const val NAP_FROM_MIN = 8 * MINUTES_PER_HOUR
        const val NAP_TO_MIN = 21 * MINUTES_PER_HOUR
    }
}
