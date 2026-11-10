package ring.app.insights

import ring.app.data.IncidentCategory
import ring.app.data.RingDao
import ring.app.data.Sample
import ring.app.data.SampleType
import ring.domain.ApneaIndicator
import ring.domain.ApneaNight
import ring.domain.CycleEstimate
import ring.domain.CycleTemperature
import ring.domain.Fitness
import ring.domain.HabitEffect
import ring.domain.HabitEffectResult
import ring.domain.HeartRateModel
import ring.domain.HeartRateRecovery
import ring.domain.Profile
import ring.domain.RecoveryEvent
import ring.domain.RestingHr
import ring.domain.Stats
import ring.domain.TimedValue
import ring.domain.Vo2Estimate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class ApneaReport(val lastNight: ApneaNight?, val flaggedNights: Int, val nightsWithData: Int, val pattern: Boolean)

enum class HabitMetric { HRV, RESTING_HR, SLEEP }

data class HabitRow(val category: IncidentCategory, val metric: HabitMetric, val effect: HabitEffectResult)

/** Fitness, heart rate recovery, oxygen dips and habit effects, all from stored data. Indicative estimates. */
class EstimateInsights(private val dao: RingDao, private val zone: ZoneId = ZoneId.systemDefault()) {
    private fun List<Sample>.timed() = map { TimedValue(it.ts, it.value) }

    fun fitness(profile: Profile, restingHr: Double?): Vo2Estimate? =
        restingHr?.let { Fitness.vo2max(HeartRateModel.hrMax(profile), it, profile.hrMaxOverride != null, profile.ageYears) }

    /** The most recent recovery event of the last day. */
    suspend fun recovery(profile: Profile, now: Long): RecoveryEvent? =
        HeartRateRecovery.events(dao.range(SampleType.HR, now - DAY, now).timed(), HeartRateModel.hrMax(profile)).lastOrNull()

    private fun nightWindow(endDay: LocalDate): Pair<Long, Long> {
        val end = endDay.atTime(LocalTime.of(NIGHT_END_HOUR, 0)).atZone(zone).toEpochSecond()
        val start = endDay.minusDays(1).atTime(LocalTime.of(NIGHT_START_HOUR, 0)).atZone(zone).toEpochSecond()
        return start to end
    }

    private fun latestNightEnd(now: Long): LocalDate {
        val local = Instant.ofEpochSecond(now).atZone(zone)
        return if (local.toLocalTime() >= LocalTime.of(NIGHT_END_HOUR, 0)) local.toLocalDate() else local.toLocalDate().minusDays(1)
    }

    suspend fun apnea(now: Long): ApneaReport {
        val latest = latestNightEnd(now)
        val nights = (APNEA_NIGHTS - 1 downTo 0).map { back ->
            val (start, end) = nightWindow(latest.minusDays(back.toLong()))
            ApneaIndicator.night(dao.range(SampleType.SPO2, start, end).timed()) to dao.range(SampleType.SPO2, start, end).size
        }
        val withData = nights.filter { it.second >= MIN_SPO2_SAMPLES }.map { it.first }
        return ApneaReport(withData.lastOrNull(), withData.count { it.flagged }, withData.size, ApneaIndicator.pattern(withData))
    }

    /** Mean skin temperature of each of the last 45 nights (null when a night has no data), oldest first. */
    suspend fun cycle(now: Long): CycleEstimate {
        val latest = latestNightEnd(now)
        val nightly = (CYCLE_NIGHTS - 1 downTo 0).map { back ->
            val (start, end) = nightWindow(latest.minusDays(back.toLong()))
            dao.range(SampleType.TEMP, start, end).map { it.value }.takeIf { it.isNotEmpty() }?.let(Stats::mean)
        }
        return CycleTemperature.estimate(nightly)
    }

    private class NightMetrics(val end: Long, val hrv: Double?, val rhr: Double?, val sleepMin: Double?)

    /** Effects (HRV, resting HR, sleep) of nights after each incident category against the other nights; only noteworthy ones. */
    suspend fun habits(now: Long): List<HabitRow> {
        val latest = latestNightEnd(now)
        val firstStart = nightWindow(latest.minusDays((HABIT_NIGHTS - 1).toLong())).first
        val incidents = dao.incidentsSince(firstStart - DAY).filter { it.categories.isNotBlank() }
        if (incidents.isEmpty()) return emptyList()
        val sleeps = dao.sleepSince(firstStart - DAY)
        val nights = (HABIT_NIGHTS - 1 downTo 0).map { back ->
            val (start, end) = nightWindow(latest.minusDays(back.toLong()))
            val hrv = dao.range(SampleType.HRV, start, end).map { it.value }
            NightMetrics(
                end = end,
                hrv = hrv.takeIf { it.isNotEmpty() }?.let(Stats::median),
                rhr = RestingHr.estimate(dao.range(SampleType.HR, start, end).timed(), start, end),
                sleepMin = sleeps.filter { it.end in start..end }.maxOfOrNull { (it.lightMin + it.deepMin + it.remMin).toDouble() },
            )
        }
        return IncidentCategory.entries.flatMap { category ->
            val afterEvent = nights.filter { night -> incidents.any { category.name in it.categories.split(",") && nightFollowing(it.ts, night, nights) } }
            val others = nights - afterEvent.toSet()
            listOf(
                HabitMetric.HRV to { n: NightMetrics -> n.hrv },
                HabitMetric.RESTING_HR to { n: NightMetrics -> n.rhr },
                HabitMetric.SLEEP to { n: NightMetrics -> n.sleepMin },
            ).mapNotNull { (metric, pick) ->
                HabitEffect.compare(afterEvent.mapNotNull(pick), others.mapNotNull(pick))?.takeIf { it.noteworthy }?.let { HabitRow(category, metric, it) }
            }
        }.sortedByDescending { kotlin.math.abs(it.effect.cohenD) }
    }

    /** True when `night` is the first night ending after the incident. */
    private fun nightFollowing(incidentTs: Long, night: NightMetrics, nights: List<NightMetrics>): Boolean =
        nights.firstOrNull { it.end > incidentTs }?.end == night.end

    private companion object {
        const val DAY = 86_400L
        const val NIGHT_START_HOUR = 22
        const val NIGHT_END_HOUR = 6
        const val APNEA_NIGHTS = 7
        const val MIN_SPO2_SAMPLES = 5
        const val HABIT_NIGHTS = 42
        const val CYCLE_NIGHTS = 45
    }
}
