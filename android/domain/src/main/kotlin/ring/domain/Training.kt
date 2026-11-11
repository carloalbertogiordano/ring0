package ring.domain

import kotlin.math.exp
import kotlin.math.roundToInt

/** Intensity zones as a percentage of maximum heart rate (REST is below 50%). */
enum class HrZone { REST, Z1, Z2, Z3, Z4, Z5 }

object HeartRateModel {
    private const val TANAKA_BASE = 208.0
    private const val TANAKA_SLOPE = 0.7
    private val ZONE_LOWER_BOUNDS = listOf(0.5 to HrZone.Z1, 0.6 to HrZone.Z2, 0.7 to HrZone.Z3, 0.8 to HrZone.Z4, 0.9 to HrZone.Z5)

    /** Measured value if the user entered one, else Tanaka 2001: 208 - 0.7 x age (about +/-10 bpm for a single person). */
    fun hrMax(profile: Profile): Int =
        profile.hrMaxOverride ?: (TANAKA_BASE - TANAKA_SLOPE * profile.ageYears).roundToInt()

    fun zone(hr: Double, hrMax: Int): HrZone {
        val fraction = hr / hrMax
        return ZONE_LOWER_BOUNDS.lastOrNull { fraction >= it.first }?.second ?: HrZone.REST
    }

    /** Minutes spent in each zone; every sample counts as the sampling interval, zero readings (no signal) are skipped. */
    fun minutesPerZone(samples: List<TimedValue>, sampleMinutes: Int, hrMax: Int): Map<HrZone, Int> {
        val minutes = HrZone.entries.associateWith { 0 }.toMutableMap()
        for (s in samples.filter { it.value > 0 }) {
            val zone = zone(s.value, hrMax)
            minutes[zone] = minutes.getValue(zone) + sampleMinutes
        }
        return minutes
    }
}

/** Banister training impulse: minutes x heart rate reserve fraction x exponential intensity weight. */
object Trimp {
    private const val MALE_FACTOR = 0.64
    private const val MALE_EXPONENT = 1.92
    private const val FEMALE_FACTOR = 0.86
    private const val FEMALE_EXPONENT = 1.67

    fun session(avgHr: Double, minutes: Double, hrRest: Int, hrMax: Int, sex: Sex): Double {
        val ratio = ((avgHr - hrRest) / (hrMax - hrRest)).coerceIn(0.0, 1.0)
        val (factor, exponent) = if (sex == Sex.MALE) MALE_FACTOR to MALE_EXPONENT else FEMALE_FACTOR to FEMALE_EXPONENT
        return minutes * ratio * factor * exp(exponent * ratio)
    }

    /**
     * Load of a stretch of evenly spaced samples. Only effort counts (from 50% of the maximum heart rate, the first zone): the formula
     * was made for training sessions, and summed over a sedentary day the minutes just above the resting rate would add up to a
     * large load. Zero readings (no signal) are skipped.
     */
    fun fromSamples(samples: List<TimedValue>, sampleMinutes: Int, hrRest: Int, hrMax: Int, sex: Sex): Double =
        samples
            .filter { HeartRateModel.zone(it.value, hrMax) != HrZone.REST && it.value > 0 }
            .sumOf { session(it.value, sampleMinutes.toDouble(), hrRest, hrMax, sex) }
}

enum class LoadLevel { LOW, USUAL, HIGH }

data class LoadTrendResult(val acute: Double, val chronic: Double, val ratio: Double, val level: LoadLevel)

/**
 * Recent load against your own usual load (last 7 days over the last 28). A trend, not an injury predictor:
 * the acute:chronic ratio and its thresholds are debated in the literature.
 */
object LoadTrend {
    private const val ACUTE_DAYS = 7
    private const val CHRONIC_DAYS = 28
    private const val MIN_DAYS = 14
    private const val LOW_BELOW = 0.8
    private const val HIGH_ABOVE = 1.3

    fun compute(dailyTrimp: List<Double>): LoadTrendResult? {
        if (dailyTrimp.size < MIN_DAYS) return null
        val acute = Stats.mean(dailyTrimp.takeLast(ACUTE_DAYS))
        val chronic = Stats.mean(dailyTrimp.takeLast(CHRONIC_DAYS))
        if (chronic <= 0) return null
        val ratio = acute / chronic
        val level = when {
            ratio < LOW_BELOW -> LoadLevel.LOW
            ratio > HIGH_ABOVE -> LoadLevel.HIGH
            else -> LoadLevel.USUAL
        }
        return LoadTrendResult(acute, chronic, ratio, level)
    }
}

data class TrainingDay(val zoneMinutes: Map<HrZone, Int>, val trimp: Double, val activeKcal: Double)

/** Everything the training card needs for one stretch of heart rate samples. */
object TrainingSummary {
    fun day(samples: List<TimedValue>, sampleMinutes: Int, profile: Profile, hrRest: Int): TrainingDay {
        val hrMax = HeartRateModel.hrMax(profile)
        return TrainingDay(
            zoneMinutes = HeartRateModel.minutesPerZone(samples, sampleMinutes, hrMax),
            trimp = Trimp.fromSamples(samples, sampleMinutes, hrRest, hrMax, profile.sex),
            activeKcal = Energy.activeKcal(samples, sampleMinutes, profile, hrMax),
        )
    }
}
