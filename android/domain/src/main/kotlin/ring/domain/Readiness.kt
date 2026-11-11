package ring.domain

import kotlin.math.ln

enum class ReadinessLevel { HIGH, GOOD, MODERATE, LOW }

enum class ReadinessComponent { HRV, RESTING_HR, SLEEP }

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
    /** Contribution of each available signal (0-100). */
    val components: Map<ReadinessComponent, Int>,
    /** 0..1: how established the baseline is (days of history / 14). */
    val confidence: Double,
)

/**
 * Recovery score: last night's ln(RMSSD) against your moving baseline (z-score), resting heart rate
 * (lower is better) and sleep (duration and efficiency). Missing signals are dropped and the weights renormalized; at least
 * one of HRV and resting heart rate must have a personal baseline.
 */
object ReadinessScore {
    private const val W_HRV = 0.45
    private const val W_RHR = 0.25
    private const val W_SLEEP = 0.30
    private const val NEUTRAL_SCORE = 50.0
    private const val Z_TO_POINTS = 25.0
    private const val MIN_SPREAD_LN = 0.10
    private const val MIN_SPREAD_RHR = 1.5
    private const val FULL_CONFIDENCE_DAYS = 14.0
    private const val SLEEP_DURATION_WEIGHT = 0.7
    private const val LEVEL_HIGH = 75
    private const val LEVEL_GOOD = 55
    private const val LEVEL_MODERATE = 35

    fun compute(input: ReadinessInput): Readiness? {
        val parts = mutableMapOf<ReadinessComponent, Pair<Double, Double>>() // component -> (score, weight)
        var days = 0
        val hrvBase = Baseline.of(input.rmssdHistory.filter { it > 0 }.map(::ln), minSpread = MIN_SPREAD_LN)
        val hrvZ = input.rmssdToday?.takeIf { it > 0 }?.let { hrvBase?.z(ln(it)) }
        if (hrvZ != null) {
            parts[ReadinessComponent.HRV] = Stats.clamp(NEUTRAL_SCORE + Z_TO_POINTS * hrvZ) to W_HRV
            days = maxOf(days, hrvBase!!.days)
        }
        val rhrBase = Baseline.of(input.restingHrHistory, minSpread = MIN_SPREAD_RHR)
        val rhrZ = input.restingHrToday?.let { rhrBase?.z(it) }
        if (rhrZ != null) {
            parts[ReadinessComponent.RESTING_HR] = Stats.clamp(NEUTRAL_SCORE - Z_TO_POINTS * rhrZ) to W_RHR
            days = maxOf(days, rhrBase!!.days)
        }
        input.sleepMinutes?.let { minutes ->
            val duration = Stats.clamp(100.0 * minutes / input.sleepTargetMinutes)
            val efficiency = input.sleepEfficiency?.let { Stats.clamp(it * 100) }
            val score = if (efficiency == null) duration else SLEEP_DURATION_WEIGHT * duration + (1 - SLEEP_DURATION_WEIGHT) * efficiency
            parts[ReadinessComponent.SLEEP] = score to W_SLEEP
        }
        // sleep alone says nothing about recovery against your own norm: it needs an HRV or resting heart rate baseline
        if (ReadinessComponent.HRV !in parts && ReadinessComponent.RESTING_HR !in parts) return null
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
