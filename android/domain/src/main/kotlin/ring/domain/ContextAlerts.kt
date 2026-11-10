package ring.domain

enum class HrAlert { HIGH, LOW }

/** Stima della frequenza a riposo. */
object RestingHr {
    private const val MIN_SAMPLES = 6
    private const val LOW_QUANTILE = 25.0
    private const val DAY_QUANTILE = 10.0
    private const val MIN_DAY_SAMPLES = 24

    /** Con una finestra di sonno: mediana del quartile piu' basso nella finestra; altrimenti decimo percentile del giorno. */
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

/** Alert che tengono conto del contesto (a riposo o in attivita') per evitare falsi allarmi da sforzo fisico. */
object ContextAlerts {
    private const val CONSECUTIVE = 2
    private const val UNKNOWN_CONTEXT_MARGIN = 10.0
    private const val SPO2_WINDOW_SECONDS = 3 * 3600L
    private const val SPO2_SEVERE_DROP = 4.0

    /**
     * FC alta: ultimi 2 campioni oltre soglia e il contesto non e' "in movimento". Se il contesto e' ignoto
     * (nessun dato passi) la soglia sale di un margine. FC bassa: 2 campioni sotto soglia.
     */
    fun heartRate(recent: List<TimedValue>, high: Double, low: Double, isResting: (Long) -> Boolean?): HrAlert? {
        val last = recent.sortedBy { it.ts }.takeLast(CONSECUTIVE).filter { it.value > 0 }
        if (last.size < CONSECUTIVE) return null
        if (last.all { it.value < low }) return HrAlert.LOW
        val contexts = last.map { isResting(it.ts) }
        if (contexts.any { it == false }) return null
        val limit = if (contexts.all { it == true }) high else high + UNKNOWN_CONTEXT_MARGIN
        return if (last.all { it.value > limit }) HrAlert.HIGH else null
    }

    /** SpO2 bassa: due campioni sotto soglia nelle ultime 3 ore, oppure un calo severo isolato. */
    fun spo2(samples: List<TimedValue>, low: Double, now: Long): Boolean {
        val recent = samples.filter { now - it.ts in 0..SPO2_WINDOW_SECONDS }
        return recent.count { it.value < low } >= CONSECUTIVE || recent.any { it.value < low - SPO2_SEVERE_DROP }
    }
}
