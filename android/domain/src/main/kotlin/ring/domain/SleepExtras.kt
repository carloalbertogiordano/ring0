package ring.domain

import kotlin.math.roundToInt

/**
 * Sleep score 0-100: duration against your target (50%), efficiency (20%), share of deep + REM (20%) and few awakenings (10%).
 * A heuristic of ours, in the spirit of consumer sleep scores: use it to compare your own nights.
 */
object SleepScoring {
    private const val W_DURATION = 0.5
    private const val W_EFFICIENCY = 0.2
    private const val W_RESTORATIVE = 0.2
    private const val W_CONTINUITY = 0.1
    private const val EFFICIENCY_FLOOR = 0.6
    private const val EFFICIENCY_SPAN = 0.3
    private const val RESTORATIVE_TARGET_PCT = 40.0
    private const val WASO_ZERO_SCORE_MIN = 60.0
    private const val SCORE_SCALE = 100.0

    fun score(summary: SleepSummary, targetMin: Int): Int {
        val duration = (summary.totalSleepMin.toDouble() / targetMin).coerceIn(0.0, 1.0)
        val efficiency = ((summary.efficiency - EFFICIENCY_FLOOR) / EFFICIENCY_SPAN).coerceIn(0.0, 1.0)
        val restorative = ((summary.deepPct + summary.remPct) / RESTORATIVE_TARGET_PCT).coerceIn(0.0, 1.0)
        val continuity = (1 - summary.wasoMin / WASO_ZERO_SCORE_MIN).coerceIn(0.0, 1.0)
        val total = W_DURATION * duration + W_EFFICIENCY * efficiency + W_RESTORATIVE * restorative + W_CONTINUITY * continuity
        return (SCORE_SCALE * total).roundToInt()
    }
}

data class Nap(val start: Long, val end: Long)

/**
 * Possible naps from the 5-minute heart rate log: at least 20 minutes of steady heart rate close to your resting rate,
 * outside the main night. Quiet sitting can look the same, so these are only "possible" naps.
 */
object NapDetector {
    private const val SLOT_SECONDS = 300L
    private const val MIN_SLOTS = 4
    private const val MAX_ABOVE_RESTING = 6.0
    private const val MAX_SPREAD = 8.0

    fun detect(hr: List<TimedValue>, restingHr: Double, sleepWindows: List<LongRange>): List<Nap> {
        val calm = hr.sortedBy { it.ts }.filter { it.value > 0 && it.value <= restingHr + MAX_ABOVE_RESTING }
        val runs = mutableListOf<MutableList<TimedValue>>()
        for (sample in calm) {
            val last = runs.lastOrNull()
            if (last != null && sample.ts - last.last().ts == SLOT_SECONDS) last += sample else runs += mutableListOf(sample)
        }
        return runs
            .filter { run -> run.size >= MIN_SLOTS && run.maxOf { it.value } - run.minOf { it.value } <= MAX_SPREAD }
            .map { Nap(it.first().ts, it.last().ts + SLOT_SECONDS) }
            .filter { nap -> sleepWindows.none { nap.start <= it.last && nap.end >= it.first } }
    }
}

/** Suggested bedtime: your usual wake time minus the sleep you want and a short wind-down. */
object Bedtime {
    private const val MIN_NIGHTS = 3
    private const val WIND_DOWN_MIN = 15
    private const val MINUTES_PER_DAY = 1440

    /** Minute of day (0..1439), or null with fewer than 3 nights. */
    fun suggest(wakeMinuteOfDay: List<Int>, targetMin: Int): Int? {
        if (wakeMinuteOfDay.size < MIN_NIGHTS) return null
        val wake = SleepRegularity.meanOnsetMinute(wakeMinuteOfDay)
        return ((wake - targetMin - WIND_DOWN_MIN) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY
    }
}
