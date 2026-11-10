package ring.domain

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

enum class RhythmLevel { REGULAR, IRREGULAR, INCONCLUSIVE }

data class RhythmResult(
    val level: RhythmLevel,
    val beats: Int,
    val windows: Int,
    val irregularWindows: Int,
    /** Normalised RMSSD (RMSSD / mean RR) and sample entropy of the whole recording; null when inconclusive. */
    val nRmssd: Double?,
    val sampleEntropy: Double?,
)

/**
 * Pulse irregularity screening from RR intervals (a rhythm "check", never a diagnosis). Sliding windows of 64 beats are
 * flagged when RMSSD relative to the mean RR is above 0.17 AND sample entropy (m=2, r=0.2 SD) is above 1.2; the check is
 * irregular when at least half of the windows are flagged. Thresholds were tuned on the MIT-BIH Atrial Fibrillation
 * Database (PhysioNet): on 128-beat excerpts, sensitivity 87% and specificity 99.6%, about 96% with 20 ms of timing noise.
 * Those are results on ECG-derived intervals; the ring is optical, and this algorithm was not validated on real atrial
 * fibrillation recorded with it. Ectopic beats, movement and a poor signal can all look irregular.
 */
object RhythmCheck {
    private const val WINDOW = 64
    private const val STEP = 16
    private const val MIN_BEATS = 96
    private const val MIN_RR_MS = 300
    private const val MAX_RR_MS = 2000
    private const val MAX_ARTIFACT_SHARE = 0.15
    private const val IRREGULAR_SHARE = 0.5
    private const val NRMSSD_LIMIT = 0.17
    private const val SAMPEN_LIMIT = 1.2
    private const val TEMPLATE_LENGTH = 2
    private const val TOLERANCE_SD = 0.2
    private const val NO_MATCH_ENTROPY = 3.0

    /** RMSSD divided by the mean RR: the beat-to-beat change relative to the heart rate. */
    fun nRmssd(rr: List<Double>): Double {
        if (rr.size < 2) return 0.0
        val diffs = rr.zipWithNext { a, b -> b - a }
        return sqrt(diffs.sumOf { it * it } / diffs.size) / Stats.mean(rr)
    }

    /** Sample entropy (Richman-Moorman) with m = 2 and r = 0.2 x SD; a high value means an unpredictable series. */
    fun sampleEntropy(rr: List<Double>): Double {
        val tolerance = TOLERANCE_SD * populationSd(rr)
        val templates = rr.size - TEMPLATE_LENGTH
        val longer = matches(rr, TEMPLATE_LENGTH + 1, templates, tolerance)
        val shorter = matches(rr, TEMPLATE_LENGTH, templates, tolerance)
        return if (longer == 0 || shorter == 0) NO_MATCH_ENTROPY else -ln(longer.toDouble() / shorter)
    }

    private fun populationSd(x: List<Double>): Double {
        val mean = Stats.mean(x)
        return sqrt(x.sumOf { (it - mean) * (it - mean) } / x.size)
    }

    private fun matches(x: List<Double>, length: Int, templates: Int, tolerance: Double): Int {
        var count = 0
        for (i in 0 until templates) {
            for (j in i + 1 until templates) {
                if ((0 until length).all { k -> abs(x[i + k] - x[j + k]) <= tolerance }) count++
            }
        }
        return count
    }

    fun analyze(rrMs: List<Int>): RhythmResult {
        val clean = rrMs.filter { it in MIN_RR_MS..MAX_RR_MS }.map { it.toDouble() }
        val artifactShare = if (rrMs.isEmpty()) 1.0 else 1 - clean.size.toDouble() / rrMs.size
        if (clean.size < MIN_BEATS || artifactShare > MAX_ARTIFACT_SHARE) {
            return RhythmResult(RhythmLevel.INCONCLUSIVE, clean.size, 0, 0, null, null)
        }
        val windows = (0..clean.size - WINDOW step STEP).map { clean.subList(it, it + WINDOW) }
        val flagged = windows.count { nRmssd(it) > NRMSSD_LIMIT && sampleEntropy(it) > SAMPEN_LIMIT }
        val level = if (flagged.toDouble() / windows.size >= IRREGULAR_SHARE) RhythmLevel.IRREGULAR else RhythmLevel.REGULAR
        return RhythmResult(level, clean.size, windows.size, flagged, nRmssd(clean), sampleEntropy(clean))
    }
}

enum class RhythmAdvice { NONE, RECHECK, SEE_DOCTOR }

/** What to tell the user from the recent irregular results: one is worth repeating, repeated ones are worth a doctor's ECG. */
object RhythmHistory {
    private const val WEEK_SECONDS = 7 * 86_400L
    private const val DISTINCT_SECONDS = 3_600L

    fun advice(irregularTimestamps: List<Long>, now: Long): RhythmAdvice {
        val recent = irregularTimestamps.filter { now - it in 0..WEEK_SECONDS }.sorted()
        val distinct = recent.fold(emptyList<Long>()) { kept, ts -> if (kept.isEmpty() || ts - kept.last() >= DISTINCT_SECONDS) kept + ts else kept }
        return when {
            distinct.size >= 2 -> RhythmAdvice.SEE_DOCTOR
            distinct.size == 1 -> RhythmAdvice.RECHECK
            else -> RhythmAdvice.NONE
        }
    }
}
