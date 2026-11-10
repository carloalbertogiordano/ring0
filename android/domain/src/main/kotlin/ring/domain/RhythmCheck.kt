package ring.domain

import kotlin.math.ceil
import kotlin.math.sqrt

enum class RhythmLevel { REGULAR, IRREGULAR, INCONCLUSIVE }

data class RhythmResult(
    val level: RhythmLevel,
    /** Usable segments, and how many of them looked irregular. */
    val segments: Int,
    val irregularSegments: Int,
)

/**
 * Pulse irregularity screening from RR intervals (a rhythm "check", never a diagnosis).
 *
 * The ring stops its real-time stream after about 30 seconds and needs about 14 of them to lock on, so one session yields
 * only 17-20 contiguous beats. A check therefore restarts the stream six times and analyses each session as one
 * 18-beat segment: a segment is flagged when RMSSD relative to the mean RR is above 0.21 and more than half of its
 * beats are turning points (the pulse zig-zags). The check is irregular when at least four segments (and two thirds of the
 * usable ones) are flagged.
 *
 * Tuned on the MIT-BIH Atrial Fibrillation Database (PhysioNet; patients with atrial fibrillation, a hard case): on six
 * contiguous 18-beat segments, sensitivity about 67-73% and specificity about 93-97% (more timing noise lowers the
 * specificity). Those are results on ECG-derived intervals; the ring is optical and this was not validated on real
 * atrial fibrillation recorded with it. Ectopic beats, movement and a poor signal can all look irregular.
 */
object RhythmCheck {
    private const val SEGMENT_BEATS = 18
    private const val MIN_SEGMENT_BEATS = 16
    private const val MIN_SEGMENTS = 4
    private const val MIN_RR_MS = 300
    private const val MAX_RR_MS = 2000
    private const val MAX_ARTIFACT_SHARE = 0.15
    private const val NRMSSD_LIMIT = 0.21
    private const val TURNING_POINT_LIMIT = 0.5
    private const val FLAGGED_SHARE = 0.66
    private const val MIN_FOR_TURNING_POINTS = 3

    /** RMSSD divided by the mean RR: the beat-to-beat change relative to the heart rate. */
    fun nRmssd(rr: List<Double>): Double {
        if (rr.size < 2) return 0.0
        val diffs = rr.zipWithNext { a, b -> b - a }
        return sqrt(diffs.sumOf { it * it } / diffs.size) / Stats.mean(rr)
    }

    /** Share of interior beats that are a local maximum or minimum: about 2/3 for random intervals, less for a smooth pulse. */
    fun turningPointRatio(rr: List<Double>): Double {
        if (rr.size < MIN_FOR_TURNING_POINTS) return 0.0
        val turning = (1 until rr.size - 1).count { (rr[it] - rr[it - 1]) * (rr[it] - rr[it + 1]) > 0 }
        return turning.toDouble() / (rr.size - 2)
    }

    /** The cleaned first 18 beats of a session, or null when it is too short or has too many impossible values. */
    private fun usable(session: List<Int>): List<Double>? {
        val clean = session.filter { it in MIN_RR_MS..MAX_RR_MS }
        val artifactShare = if (session.isEmpty()) 1.0 else 1 - clean.size.toDouble() / session.size
        return if (clean.size >= MIN_SEGMENT_BEATS && artifactShare <= MAX_ARTIFACT_SHARE) clean.take(SEGMENT_BEATS).map { it.toDouble() } else null
    }

    /** One list of RR intervals (ms) per real-time session. */
    fun analyze(sessions: List<List<Int>>): RhythmResult {
        val segments = sessions.mapNotNull(::usable)
        if (segments.size < MIN_SEGMENTS) return RhythmResult(RhythmLevel.INCONCLUSIVE, segments.size, 0)
        val flagged = segments.count { nRmssd(it) > NRMSSD_LIMIT && turningPointRatio(it) > TURNING_POINT_LIMIT }
        val needed = maxOf(MIN_SEGMENTS, ceil(FLAGGED_SHARE * segments.size).toInt())
        return RhythmResult(if (flagged >= needed) RhythmLevel.IRREGULAR else RhythmLevel.REGULAR, segments.size, flagged)
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
