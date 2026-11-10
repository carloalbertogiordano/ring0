package ring.protocol.commands

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** HRV from RR intervals (ms): artifact cleaning and time-domain metrics. */
object Hrv {
    private const val MIN_RR_MS = 300
    private const val MAX_RR_MS = 2000
    private const val MAX_DEVIATION = 0.2
    private const val MS_PER_MINUTE = 60_000.0

    /** Drops physiologically implausible values and beats deviating >20% from the local median (Malik-style rule). */
    fun clean(rr: List<Int>): List<Int> {
        val plausible = rr.filter { it in MIN_RR_MS..MAX_RR_MS }
        if (plausible.size < 3) return plausible
        return plausible.filterIndexed { i, value ->
            val window = plausible.subList(maxOf(0, i - 2), minOf(plausible.size, i + 3))
            abs(value - median(window)) <= MAX_DEVIATION * median(window)
        }
    }

    /** RMSSD: root mean square of successive RR differences; null with fewer than 2 RR. */
    fun rmssd(rr: List<Int>): Double? {
        if (rr.size < 2) return null
        val diffs = rr.zipWithNext { a, b -> (b - a).toDouble() }
        return sqrt(diffs.sumOf { it * it } / diffs.size)
    }

    /** SDNN: standard deviation of the RR intervals; null with fewer than 2 RR. */
    fun sdnn(rr: List<Int>): Double? {
        if (rr.size < 2) return null
        val mean = rr.average()
        return sqrt(rr.sumOf { (it - mean) * (it - mean) } / (rr.size - 1))
    }

    fun meanBpm(rr: List<Int>): Int? = if (rr.isEmpty()) null else (MS_PER_MINUTE / median(rr)).roundToInt()

    private fun median(values: List<Int>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
