package ring.domain

/** Battery discharge forecast: hours until empty at the recent rate. */
data class BatteryEstimate(val percentPerHour: Double, val hoursLeft: Double)

object BatteryForecast {
    private const val SECONDS_PER_HOUR = 3600.0
    private const val CHARGE_RISE = 1.0
    private const val MIN_SAMPLES = 4
    private const val MIN_SPAN_HOURS = 3.0
    private const val RECENT_HOURS = 12.0

    /**
     * Least-squares slope over the current discharge: the samples of the last 12 hours since the last charge (a rise of more
     * than one point), so that the recent drain counts more than a flat night.
     * Null when there are too few samples, too short a span, or the level is not falling.
     */
    fun estimate(samples: List<TimedValue>): BatteryEstimate? {
        val segment = currentDischarge(samples)
        val slope = slopePerHour(segment)
        return if (slope == null || slope >= 0) null else BatteryEstimate(-slope, segment.last().value / -slope)
    }

    private fun currentDischarge(samples: List<TimedValue>): List<TimedValue> {
        val sorted = samples.sortedBy { it.ts }
        val lastRise = (1 until sorted.size).lastOrNull { sorted[it].value > sorted[it - 1].value + CHARGE_RISE }
        val sinceCharge = if (lastRise == null) sorted else sorted.drop(lastRise)
        val segment = sinceCharge.filter { it.ts >= sinceCharge.last().ts - RECENT_HOURS * SECONDS_PER_HOUR }
        val spanHours = if (segment.isEmpty()) 0.0 else (segment.last().ts - segment.first().ts) / SECONDS_PER_HOUR
        return if (segment.size >= MIN_SAMPLES && spanHours >= MIN_SPAN_HOURS) segment else emptyList()
    }

    private fun slopePerHour(segment: List<TimedValue>): Double? {
        if (segment.isEmpty()) return null
        val hours = segment.map { (it.ts - segment.first().ts) / SECONDS_PER_HOUR }
        val levels = segment.map { it.value }
        val meanH = Stats.mean(hours)
        val meanL = Stats.mean(levels)
        val denominator = hours.sumOf { (it - meanH) * (it - meanH) }
        if (denominator == 0.0) return null
        return hours.indices.sumOf { (hours[it] - meanH) * (levels[it] - meanL) } / denominator
    }
}

data class WearGap(val start: Long, val end: Long)

/** Finds stretches where the ring gave no heart rate (not worn, or not locked on), so they can be left out of averages. */
object Wear {
    private const val MIN_GAP_SLOTS = 6

    /** Runs of at least 30 minutes of empty or zero slots inside [from, to). */
    fun gaps(hr: List<TimedValue>, from: Long, to: Long, slotSeconds: Long = 300): List<WearGap> {
        val wornSlots = hr.filter { it.value > 0 && it.ts in from until to }.map { (it.ts - from) / slotSeconds }.toSet()
        val slotCount = (to - from) / slotSeconds
        val gaps = mutableListOf<WearGap>()
        var runStart: Long? = null
        for (slot in 0..slotCount) {
            val empty = slot < slotCount && slot !in wornSlots
            if (empty && runStart == null) runStart = slot
            if (!empty && runStart != null) {
                if (slot - runStart >= MIN_GAP_SLOTS) gaps += WearGap(from + runStart * slotSeconds, from + slot * slotSeconds)
                runStart = null
            }
        }
        return gaps
    }

    /** True when the timestamp falls inside one of the gaps (the end of a gap is exclusive). */
    fun inGap(ts: Long, gaps: List<WearGap>): Boolean = gaps.any { ts >= it.start && ts < it.end }

    fun wornSeconds(from: Long, to: Long, gaps: List<WearGap>): Long = (to - from) - gaps.sumOf { it.end - it.start }
}
