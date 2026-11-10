package ring.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import ring.protocol.commands.Hrv

data class RespirationEstimate(val breathsPerMinute: Double, val confidence: Double)

/**
 * Frequenza respiratoria dagli intervalli RR sfruttando l'aritmia sinusale respiratoria (RSA): il respiro
 * modula l'RR a 0,1-0,5 Hz (6-30 respiri/min). Passi: pulizia artefatti, tempi dei battiti, risampling a 4 Hz,
 * rimozione del trend lento, finestra di Hann, spettro di potenza nella banda respiratoria, picco con
 * interpolazione parabolica. Servono almeno ~45 s di battiti puliti.
 */
object RespirationRate {
    private const val FS = 4.0
    private const val MIN_BEATS = 30
    private const val MIN_SECONDS = 45.0
    private const val BAND_LOW = 0.10
    private const val BAND_HIGH = 0.50
    private const val STEP = 0.005
    private const val DETREND_SECONDS = 12.0
    private const val PEAK_HALF_WIDTH = 0.03
    private const val SECONDS_PER_MINUTE = 60.0
    private const val MS_PER_SECOND = 1000.0

    fun fromRr(rrMs: List<Int>): RespirationEstimate? {
        val rr = Hrv.clean(rrMs)
        if (rr.size < MIN_BEATS) return null
        val times = DoubleArray(rr.size)
        var acc = 0.0
        rr.forEachIndexed { i, v -> acc += v / MS_PER_SECOND; times[i] = acc }
        if (times.last() - times.first() < MIN_SECONDS) return null
        val signal = detrend(resample(times, rr.map { it.toDouble() }))
        val freqs = generateSequence(BAND_LOW) { it + STEP }.takeWhile { it <= BAND_HIGH + 1e-9 }.toList()
        val power = spectrum(signal, freqs)
        val total = power.sum()
        if (total <= 1e-9) return null
        val peak = power.indices.maxBy { power[it] }
        val refined = refine(freqs, power, peak)
        val near = freqs.indices.filter { kotlin.math.abs(freqs[it] - refined) <= PEAK_HALF_WIDTH }.sumOf { power[it] }
        return RespirationEstimate(refined * SECONDS_PER_MINUTE, (near / total).coerceIn(0.0, 1.0))
    }

    private fun resample(times: DoubleArray, values: List<Double>): DoubleArray {
        val n = floor((times.last() - times.first()) * FS).toInt()
        var j = 0
        return DoubleArray(n) { k ->
            val tk = times.first() + k / FS
            while (j < times.size - 2 && times[j + 1] < tk) j++
            val span = times[j + 1] - times[j]
            val w = if (span > 0) ((tk - times[j]) / span).coerceIn(0.0, 1.0) else 0.0
            values[j] + (values[j + 1] - values[j]) * w
        }
    }

    private fun detrend(x: DoubleArray): DoubleArray {
        val half = (DETREND_SECONDS * FS / 2).toInt()
        return DoubleArray(x.size) { i ->
            val lo = maxOf(0, i - half)
            val hi = minOf(x.size - 1, i + half)
            var s = 0.0
            for (k in lo..hi) s += x[k]
            x[i] - s / (hi - lo + 1)
        }
    }

    private fun spectrum(x: DoubleArray, freqs: List<Double>): DoubleArray {
        val n = x.size
        val windowed = DoubleArray(n) { x[it] * (0.5 - 0.5 * cos(2 * PI * it / (n - 1))) }
        return DoubleArray(freqs.size) { fi ->
            val w = 2 * PI * freqs[fi] / FS
            var re = 0.0
            var im = 0.0
            for (k in 0 until n) {
                re += windowed[k] * cos(w * k)
                im += windowed[k] * sin(w * k)
            }
            re * re + im * im
        }
    }

    /** Interpolazione parabolica del picco sui due punti vicini. */
    private fun refine(freqs: List<Double>, power: DoubleArray, peak: Int): Double {
        if (peak == 0 || peak == freqs.size - 1) return freqs[peak]
        val a = power[peak - 1]
        val b = power[peak]
        val c = power[peak + 1]
        val denom = a - 2 * b + c
        if (denom == 0.0) return freqs[peak]
        return freqs[peak] + 0.5 * (a - c) / denom * STEP
    }
}
