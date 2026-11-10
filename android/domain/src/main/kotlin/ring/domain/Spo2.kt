package ring.domain

data class Spo2Summary(val min: Double, val mean: Double, val belowThreshold: Int, val samples: Int)

data class OdiResult(val events: Int, val hours: Double) {
    val indexPerHour: Double get() = if (hours > 0) events / hours else 0.0
}

/** Nocturnal SpO2 analysis. Not a medical device. */
object NocturnalSpo2 {
    private const val SECONDS_PER_HOUR = 3600.0

    fun summary(samples: List<TimedValue>, threshold: Double = 92.0): Spo2Summary? {
        if (samples.isEmpty()) return null
        val values = samples.map { it.value }
        return Spo2Summary(values.min(), Stats.mean(values), values.count { it < threshold }, values.size)
    }

    /**
     * Oxygen desaturation index (ODI): counts drops of at least `dropPct` points below the local baseline
     * (median of the previous `baselineSamples` samples). An event ends when the value climbs back above
     * baseline - dropPct/2. With hourly samples (like the R09) the resolution is very low: the index is only indicative.
     */
    fun odi(samples: List<TimedValue>, dropPct: Double = 3.0, baselineSamples: Int = 4): OdiResult {
        if (samples.size < 2) return OdiResult(0, 0.0)
        val sorted = samples.sortedBy { it.ts }
        var events = 0
        var inEvent = false
        for (i in sorted.indices) {
            if (i == 0) continue
            val base = Stats.median(sorted.subList(maxOf(0, i - baselineSamples), i).map { it.value })
            val v = sorted[i].value
            if (!inEvent && v <= base - dropPct) {
                events++
                inEvent = true
            } else if (inEvent && v >= base - dropPct / 2) {
                inEvent = false
            }
        }
        return OdiResult(events, (sorted.last().ts - sorted.first().ts) / SECONDS_PER_HOUR)
    }
}
