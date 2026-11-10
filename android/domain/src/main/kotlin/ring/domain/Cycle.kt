package ring.domain

enum class CyclePhase { NOT_ENOUGH_DATA, NO_RISE_YET, AFTER_RISE }

data class CycleEstimate(val phase: CyclePhase, val nightsSinceRise: Int?, val nightsUsed: Int)

/**
 * Optional, experimental: spots the sustained rise of nightly skin temperature that follows ovulation, with the classic
 * "three over six" rule: three nights in a row above the highest of the previous six, the third at least 0.2 C higher.
 * Distal skin temperature behaves like basal temperature in small studies (a ring detected ovulation with 83% sensitivity
 * in a window of -3 to +2 days), but illness, alcohol, the room and a new sleep pattern can mimic a rise. Never a
 * contraceptive and not for planning a pregnancy.
 */
object CycleTemperature {
    private const val MIN_NIGHTS = 15
    private const val RECENT_NIGHTS = 21
    private const val MIN_RECENT = 12
    private const val BASELINE_NIGHTS = 6
    private const val MIN_BASELINE = 5
    private const val RISE_NIGHTS = 3
    private const val THIRD_NIGHT_MARGIN = 0.2
    private const val BACK_BELOW_NIGHTS = 2
    private const val MAX_LUTEAL_NIGHTS = 20

    /** `nightly` is chronological (the last value is the latest night), null for a night without data. */
    fun estimate(nightly: List<Double?>): CycleEstimate {
        val used = nightly.count { it != null }
        if (used < MIN_NIGHTS || nightly.takeLast(RECENT_NIGHTS).count { it != null } < MIN_RECENT) {
            return CycleEstimate(CyclePhase.NOT_ENOUGH_DATA, null, used)
        }
        val rise = (BASELINE_NIGHTS..nightly.size - RISE_NIGHTS).lastOrNull { isRise(nightly, it) }
        val active = rise != null && nightly.size - 1 - rise <= MAX_LUTEAL_NIGHTS && !backBelow(nightly, rise)
        return if (active) CycleEstimate(CyclePhase.AFTER_RISE, nightly.size - 1 - rise!!, used) else CycleEstimate(CyclePhase.NO_RISE_YET, null, used)
    }

    private fun coverline(nightly: List<Double?>, start: Int): Double? =
        nightly.subList(start - BASELINE_NIGHTS, start).filterNotNull().takeIf { it.size >= MIN_BASELINE }?.max()

    private fun isRise(nightly: List<Double?>, start: Int): Boolean {
        val cover = coverline(nightly, start) ?: return false
        val run = nightly.subList(start, start + RISE_NIGHTS)
        return run.all { it != null && it > cover } && run.last()!! >= cover + THIRD_NIGHT_MARGIN
    }

    /** True when the temperature went back under the coverline for two nights in a row after the rise (cycle over). */
    private fun backBelow(nightly: List<Double?>, rise: Int): Boolean {
        val cover = coverline(nightly, rise) ?: return false
        return nightly.drop(rise + RISE_NIGHTS).windowed(BACK_BELOW_NIGHTS).any { pair -> pair.all { it != null && it <= cover } }
    }
}
