package ring.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Misura con istante (epoch secondi UTC). */
data class TimedValue(val ts: Long, val value: Double)

/** Statistiche di base su liste di numeri. */
object Stats {
    fun mean(xs: List<Double>): Double = xs.sum() / xs.size

    fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }

    /** Deviazione standard campionaria; 0 con meno di 2 valori. */
    fun sd(xs: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val m = mean(xs)
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - 1))
    }

    /** Scarto assoluto mediano, scalato per essere confrontabile con la deviazione standard (x1.4826). */
    fun robustSd(xs: List<Double>): Double {
        val m = median(xs)
        return MAD_SCALE * median(xs.map { abs(it - m) })
    }

    /** Percentile con interpolazione lineare (p in 0..100). */
    fun percentile(xs: List<Double>, p: Double): Double {
        val s = xs.sorted()
        val pos = (p / 100) * (s.size - 1)
        val lo = floor(pos).toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    fun clamp(x: Double, lo: Double = 0.0, hi: Double = 100.0): Double = x.coerceIn(lo, hi)

    private const val MAD_SCALE = 1.4826
}

/** Baseline personale robusta (mediana e deviazione robusta) su una finestra mobile. */
data class Baseline(val center: Double, val spread: Double, val days: Int) {
    /** z-score di `x` rispetto alla baseline; null se i giorni sono pochi o la dispersione e' nulla. */
    fun z(x: Double): Double? = if (days < MIN_DAYS || spread <= 0.0) null else (x - center) / spread

    companion object {
        const val MIN_DAYS = 5

        /**
         * Baseline dagli ultimi `window` valori. `minSpread` evita z-score esplosivi quando i valori sono
         * quasi costanti (es. pochi giorni con lo stesso numero).
         */
        fun of(values: List<Double>, window: Int = 28, minSpread: Double = 0.0): Baseline? {
            val recent = values.takeLast(window)
            if (recent.isEmpty()) return null
            val spread = maxOf(Stats.robustSd(recent).takeIf { it > 0 } ?: Stats.sd(recent), minSpread)
            return Baseline(Stats.median(recent), spread, recent.size)
        }
    }
}
