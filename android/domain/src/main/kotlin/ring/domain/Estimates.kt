package ring.domain

import kotlin.math.sqrt

/** VO2max in ml/kg/min with the error band of the method; `limitedAccuracy` for ages where the method agrees poorly. */
data class Vo2Estimate(val value: Double, val low: Double, val high: Double, val limitedAccuracy: Boolean)

/**
 * VO2max from the heart rate ratio method (Uth 2004): 15.3 x HRmax / HRrest. It reduces to a function of age and resting
 * heart rate, with no oxygen measurement: error about 4.5% with a measured max and 7.8% with the age formula, worse in
 * older adults. Treat it as a trend (a falling resting heart rate means better fitness), not as an absolute number.
 */
object Fitness {
    private const val UTH_FACTOR = 15.3
    private const val ERROR_MEASURED = 0.045
    private const val ERROR_PREDICTED = 0.078
    private const val MIN_RESTING_HR = 25.0
    private const val LIMITED_FROM_AGE = 50

    fun vo2max(hrMax: Int, hrRest: Double, hrMaxMeasured: Boolean, ageYears: Int): Vo2Estimate? {
        if (hrRest < MIN_RESTING_HR || hrMax <= hrRest) return null
        val value = UTH_FACTOR * hrMax / hrRest
        val error = if (hrMaxMeasured) ERROR_MEASURED else ERROR_PREDICTED
        return Vo2Estimate(value, value * (1 - error), value * (1 + error), ageYears >= LIMITED_FROM_AGE)
    }
}

data class RecoveryEvent(val peakTs: Long, val peakHr: Double, val drop5min: Double)

/**
 * Heart rate recovery from the 5-minute log: a peak at 70% of max or more followed, one sample (5 minutes) later, by a fall of
 * at least 15 bpm. The log has no exact end of effort, so the value is indicative and best read as your own trend.
 */
object HeartRateRecovery {
    private const val SLOT_SECONDS = 300L
    private const val PEAK_FRACTION = 0.7
    private const val MIN_DROP = 15.0

    fun events(hr: List<TimedValue>, hrMax: Int): List<RecoveryEvent> {
        val sorted = hr.sortedBy { it.ts }
        return sorted.indices.mapNotNull { i ->
            val peak = sorted[i]
            val next = sorted.getOrNull(i + 1)
            if (isHighLocalMax(peak, sorted.getOrNull(i - 1), hrMax) && isSharpFall(peak, next)) {
                RecoveryEvent(peak.ts, peak.value, peak.value - next!!.value)
            } else {
                null
            }
        }
    }

    private fun isHighLocalMax(peak: TimedValue, previous: TimedValue?, hrMax: Int): Boolean =
        peak.value >= PEAK_FRACTION * hrMax && (previous == null || peak.value >= previous.value)

    private fun isSharpFall(peak: TimedValue, next: TimedValue?): Boolean =
        next != null && next.ts - peak.ts == SLOT_SECONDS && next.value > 0 && peak.value - next.value >= MIN_DROP
}

data class ApneaNight(val dips: Int, val lowest: Double, val flagged: Boolean)

/**
 * Screening hint, never a diagnosis: the ring stores one SpO2 value per hour (mean of min and max), far too coarse for a true
 * oxygen desaturation index. A night is flagged when two or more hours sit 3 points under that night's median, or any hour
 * is under 88%. Three flagged nights out of seven make a pattern worth mentioning to a doctor.
 */
object ApneaIndicator {
    private const val MIN_SAMPLES = 5
    private const val DIP_BELOW_MEDIAN = 3.0
    private const val FLAG_DIPS = 2
    private const val SEVERE_LOW = 88.0
    private const val PATTERN_NIGHTS = 7
    private const val PATTERN_FLAGGED = 3

    fun night(samples: List<TimedValue>): ApneaNight {
        if (samples.size < MIN_SAMPLES) return ApneaNight(0, samples.minOfOrNull { it.value } ?: 0.0, flagged = false)
        val values = samples.map { it.value }
        val median = Stats.median(values)
        val dips = values.count { it <= median - DIP_BELOW_MEDIAN }
        val lowest = values.min()
        return ApneaNight(dips, lowest, dips >= FLAG_DIPS || lowest < SEVERE_LOW)
    }

    fun pattern(nights: List<ApneaNight>): Boolean = nights.takeLast(PATTERN_NIGHTS).count { it.flagged } >= PATTERN_FLAGGED
}

data class HabitEffectResult(
    val meanWith: Double,
    val meanWithout: Double,
    val percent: Double,
    val cohenD: Double,
    val noteworthy: Boolean,
    val nWith: Int,
    val nWithout: Int,
)

/** Compares a recovery metric on nights after a habit/event against the other nights. Needs 5 of each; no causal claim. */
object HabitEffect {
    private const val MIN_EACH = 5
    private const val NOTEWORTHY_D = 0.5
    private const val PERCENT = 100.0

    fun compare(with: List<Double>, without: List<Double>): HabitEffectResult? {
        if (with.size < MIN_EACH || without.size < MIN_EACH) return null
        val meanWith = Stats.mean(with)
        val meanWithout = Stats.mean(without)
        val pooled = sqrt((Stats.sd(with) * Stats.sd(with) + Stats.sd(without) * Stats.sd(without)) / 2)
        val d = if (pooled > 0) (meanWith - meanWithout) / pooled else 0.0
        val percent = if (meanWithout != 0.0) (meanWith - meanWithout) / meanWithout * PERCENT else 0.0
        return HabitEffectResult(meanWith, meanWithout, percent, d, kotlin.math.abs(d) >= NOTEWORTHY_D, with.size, without.size)
    }
}
