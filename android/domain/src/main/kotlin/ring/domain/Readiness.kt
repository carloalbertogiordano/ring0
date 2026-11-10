package ring.domain

import kotlin.math.ln

enum class ReadinessLevel(val label: String) { HIGH("ottimo"), GOOD("buono"), MODERATE("moderato"), LOW("basso") }

data class ReadinessInput(
    val rmssdToday: Double?,
    val rmssdHistory: List<Double>,
    val restingHrToday: Double?,
    val restingHrHistory: List<Double>,
    val sleepMinutes: Int?,
    val sleepEfficiency: Double?,
    val sleepTargetMinutes: Int = 480,
)

data class Readiness(
    val score: Int,
    val level: ReadinessLevel,
    /** Contributo dei singoli segnali (0-100) con cio' che e' disponibile. */
    val components: Map<String, Int>,
    /** 0..1: quanto la baseline e' consolidata (giorni di storico / 14). */
    val confidence: Double,
)

/**
 * Punteggio di recupero: ln(RMSSD) di oggi contro la tua baseline mobile (z-score), frequenza a riposo
 * (piu' bassa = meglio) e sonno (durata e efficienza). Se manca un segnale, i pesi si rinormalizzano.
 */
object ReadinessScore {
    private const val W_HRV = 0.45
    private const val W_RHR = 0.25
    private const val W_SLEEP = 0.30
    private const val Z_TO_POINTS = 25.0
    private const val MIN_SPREAD_LN = 0.05
    private const val MIN_SPREAD_RHR = 1.5
    private const val FULL_CONFIDENCE_DAYS = 14.0
    private const val SLEEP_DURATION_WEIGHT = 0.7
    private const val LEVEL_HIGH = 75
    private const val LEVEL_GOOD = 55
    private const val LEVEL_MODERATE = 35

    fun compute(input: ReadinessInput): Readiness? {
        val parts = mutableMapOf<String, Pair<Double, Double>>() // nome -> (punteggio, peso)
        var days = 0
        val hrvBase = Baseline.of(input.rmssdHistory.filter { it > 0 }.map(::ln), minSpread = MIN_SPREAD_LN)
        val hrvZ = input.rmssdToday?.takeIf { it > 0 }?.let { hrvBase?.z(ln(it)) }
        if (hrvZ != null) {
            parts["hrv"] = Stats.clamp(50 + Z_TO_POINTS * hrvZ) to W_HRV
            days = maxOf(days, hrvBase!!.days)
        }
        val rhrBase = Baseline.of(input.restingHrHistory, minSpread = MIN_SPREAD_RHR)
        val rhrZ = input.restingHrToday?.let { rhrBase?.z(it) }
        if (rhrZ != null) {
            parts["fc_riposo"] = Stats.clamp(50 - Z_TO_POINTS * rhrZ) to W_RHR
            days = maxOf(days, rhrBase!!.days)
        }
        input.sleepMinutes?.let { minutes ->
            val duration = Stats.clamp(100.0 * minutes / input.sleepTargetMinutes)
            val efficiency = input.sleepEfficiency?.let { Stats.clamp(it * 100) }
            val score = if (efficiency == null) duration else SLEEP_DURATION_WEIGHT * duration + (1 - SLEEP_DURATION_WEIGHT) * efficiency
            parts["sonno"] = score to W_SLEEP
        }
        if (parts.isEmpty()) return null
        val weight = parts.values.sumOf { it.second }
        val score = (parts.values.sumOf { it.first * it.second } / weight).toInt().coerceIn(0, 100)
        val level = when {
            score >= LEVEL_HIGH -> ReadinessLevel.HIGH
            score >= LEVEL_GOOD -> ReadinessLevel.GOOD
            score >= LEVEL_MODERATE -> ReadinessLevel.MODERATE
            else -> ReadinessLevel.LOW
        }
        return Readiness(score, level, parts.mapValues { it.value.first.toInt() }, (days / FULL_CONFIDENCE_DAYS).coerceAtMost(1.0))
    }
}
