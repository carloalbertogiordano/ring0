package ring.domain

enum class HrAlert { HIGH, LOW }

/** Resting heart rate estimation. */
object RestingHr {
    private const val MIN_SAMPLES = 6
    private const val LOW_QUANTILE = 25.0
    private const val DAY_QUANTILE = 10.0
    private const val MIN_DAY_SAMPLES = 24

    /** With a sleep window: median of the lowest quartile inside it; otherwise the 10th percentile of the day. */
    fun estimate(hr: List<TimedValue>, sleepStart: Long? = null, sleepEnd: Long? = null): Double? {
        val valid = hr.filter { it.value > 0 }
        if (sleepStart != null && sleepEnd != null) {
            val inside = valid.filter { it.ts in sleepStart..sleepEnd }.map { it.value }
            if (inside.size >= MIN_SAMPLES) {
                val cut = Stats.percentile(inside, LOW_QUANTILE)
                return Stats.median(inside.filter { it <= cut })
            }
        }
        return if (valid.size >= MIN_DAY_SAMPLES) Stats.percentile(valid.map { it.value }, DAY_QUANTILE) else null
    }
}

/** Alerts that take context (resting or active) into account to avoid false alarms from exercise. */
object ContextAlerts {
    private const val CONSECUTIVE = 2
    private const val EXERCISE_MARGIN = 10.0
    private const val SPO2_WINDOW_SECONDS = 3 * 3600L
    private const val SPO2_SEVERE_DROP = 4.0

    /**
     * High HR: the last 2 samples above the threshold plus a margin, because exercise cannot be ruled out from the heart
     * rate alone. Low HR: 2 samples below the threshold.
     */
    fun heartRate(recent: List<TimedValue>, high: Double, low: Double): HrAlert? {
        val last = recent.sortedBy { it.ts }.takeLast(CONSECUTIVE).filter { it.value > 0 }
        if (last.size < CONSECUTIVE) return null
        if (last.all { it.value < low }) return HrAlert.LOW
        return if (last.all { it.value > high + EXERCISE_MARGIN }) HrAlert.HIGH else null
    }

    /** Low SpO2: two samples under the threshold within the last 3 hours, or one isolated severe drop. */
    fun spo2(samples: List<TimedValue>, low: Double, now: Long): Boolean {
        val recent = samples.filter { now - it.ts in 0..SPO2_WINDOW_SECONDS }
        return recent.count { it.value < low } >= CONSECUTIVE || recent.any { it.value < low - SPO2_SEVERE_DROP }
    }
}
