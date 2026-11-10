package ring.app.insights

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import ring.app.data.RingDao
import ring.app.data.Sample
import ring.app.data.SampleType
import ring.domain.IllnessAssessment
import ring.domain.IllnessWarning
import ring.domain.NightSignals
import ring.domain.Readiness
import ring.domain.ReadinessInput
import ring.domain.ReadinessScore
import ring.domain.RestingHr
import ring.domain.Stats
import ring.domain.TimedValue
import ring.domain.Baseline

data class ReadinessReport(val readiness: Readiness?, val baselineDays: Int)

/** Recent context used by the check: baselines and the latest values. */
data class CheckContext(
    val restingHr: Double?,
    val hrvZ: Double?,
    val skinTemp: Double?,
    val tempZ: Double?,
    val stress: Int?,
    /** True when step data says the user is moving right now; null when the step data is not fresh. */
    val moving: Boolean?,
)

/** Computes recovery and the illness warning from stored data (all local). */
class Insights(private val dao: RingDao, private val zone: ZoneId = ZoneId.systemDefault()) {
    private fun List<Sample>.timed() = map { TimedValue(it.ts, it.value) }

    private class Night(val temp: Double?, val rhr: Double?, val hrv: Double?)

    /** Night ending at 06:00 local time of `endDay`: mean temperature, resting HR and median HRV. */
    private suspend fun night(endDay: LocalDate): Night {
        val end = endDay.atTime(LocalTime.of(6, 0)).atZone(zone).toEpochSecond()
        val start = endDay.minusDays(1).atTime(LocalTime.of(22, 0)).atZone(zone).toEpochSecond()
        val temp = dao.range(SampleType.TEMP, start, end).map(Sample::value)
        val hrv = dao.range(SampleType.HRV, start, end).map(Sample::value)
        return Night(
            temp = temp.takeIf { it.isNotEmpty() }?.let(Stats::mean),
            rhr = RestingHr.estimate(dao.range(SampleType.HR, start, end).timed(), start, end),
            hrv = hrv.takeIf { it.isNotEmpty() }?.let(Stats::median),
        )
    }

    /** Last `count` complete nights in chronological order (the last one is the most recent). */
    private suspend fun nights(now: Long, count: Int): List<Night> {
        val local = Instant.ofEpochSecond(now).atZone(zone)
        val latestEnd = if (local.toLocalTime() >= LocalTime.of(6, 0)) local.toLocalDate() else local.toLocalDate().minusDays(1)
        return (count - 1 downTo 0).map { night(latestEnd.minusDays(it.toLong())) }
    }

    /** Recovery: last night's HRV and resting HR against the baseline of earlier nights, plus sleep. */
    suspend fun readiness(now: Long = Instant.now().epochSecond): ReadinessReport {
        val all = nights(now, HISTORY_NIGHTS + 1)
        val last = all.last()
        val history = all.dropLast(1)
        val sleep = dao.sleepSince(now - 2 * DAY).lastOrNull()
        val summary = sleep?.let {
            val asleep = it.lightMin + it.deepMin + it.remMin
            val inBed = ((it.end - it.start) / SECONDS_PER_MINUTE).toInt().coerceAtLeast(1)
            asleep to (asleep.toDouble() / inBed)
        }
        val input = ReadinessInput(
            rmssdToday = last.hrv,
            rmssdHistory = history.mapNotNull { it.hrv },
            restingHrToday = last.rhr,
            restingHrHistory = history.mapNotNull { it.rhr },
            sleepMinutes = summary?.first,
            sleepEfficiency = summary?.second,
        )
        return ReadinessReport(ReadinessScore.compute(input), all.count { it.hrv != null })
    }

    /** Early warning: the last two nights against the baseline of earlier nights. */
    suspend fun illness(now: Long = Instant.now().epochSecond): IllnessAssessment {
        val all = nights(now, ILLNESS_NIGHTS)
        val tempBase = Baseline.of(all.dropLast(2).mapNotNull { it.temp }, minSpread = MIN_TEMP_SPREAD)
        val rhrBase = Baseline.of(all.dropLast(2).mapNotNull { it.rhr }, minSpread = MIN_RHR_SPREAD)
        val hrvBase = Baseline.of(all.dropLast(2).mapNotNull { it.hrv }, minSpread = MIN_HRV_SPREAD)
        val signals = all.takeLast(2).map { n ->
            NightSignals(n.temp?.let { tempBase?.z(it) }, n.rhr?.let { rhrBase?.z(it) }, n.hrv?.let { hrvBase?.z(it) })
        }
        return IllnessWarning.assess(signals)
    }

    /** Context for a check: resting HR, how HRV and skin temperature compare with the last two weeks, latest stress. */
    suspend fun checkContext(now: Long = Instant.now().epochSecond, stepsFresh: Boolean = false): CheckContext {
        val twoWeeks = now - 14 * DAY
        fun recent(samples: List<Sample>) = samples.lastOrNull()?.takeIf { now - it.ts <= RECENT_SECONDS }?.value
        val hrv = dao.range(SampleType.HRV, twoWeeks, now)
        val temp = dao.range(SampleType.TEMP, twoWeeks, now)
        val hrvLatest = recent(hrv)
        val tempLatest = recent(temp)
        val hrvBase = Baseline.of(hrv.map { kotlin.math.ln(it.value.coerceAtLeast(1.0)) }, window = BASELINE_SAMPLES, minSpread = MIN_HRV_LN_SPREAD)
        val tempBase = Baseline.of(temp.map(Sample::value), window = BASELINE_SAMPLES, minSpread = MIN_TEMP_SPREAD)
        return CheckContext(
            restingHr = RestingHr.estimate(dao.range(SampleType.HR, now - DAY, now).timed()),
            hrvZ = hrvLatest?.let { hrvBase?.z(kotlin.math.ln(it.coerceAtLeast(1.0))) },
            skinTemp = tempLatest,
            tempZ = tempLatest?.let { tempBase?.z(it) },
            stress = recent(dao.range(SampleType.STRESS, twoWeeks, now))?.toInt(),
            moving = if (stepsFresh) dao.range(SampleType.STEPS, now - MOVING_WINDOW - SLOT, now).sumOf { it.value } >= MOVING_STEPS else null,
        )
    }

    private companion object {
        const val RECENT_SECONDS = 3 * 3_600L
        const val SLOT = 900L
        const val MOVING_WINDOW = 900L
        const val MOVING_STEPS = 40
        const val BASELINE_SAMPLES = 2_000
        const val MIN_HRV_LN_SPREAD = 0.10
        const val DAY = 86_400L
        const val SECONDS_PER_MINUTE = 60
        const val HISTORY_NIGHTS = 28
        const val ILLNESS_NIGHTS = 9
        const val MIN_TEMP_SPREAD = 0.15
        const val MIN_RHR_SPREAD = 1.5
        const val MIN_HRV_SPREAD = 3.0
    }
}
