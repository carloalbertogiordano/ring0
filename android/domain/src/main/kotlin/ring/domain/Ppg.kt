package ring.domain

import ring.protocol.commands.RawSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/** Beats found in a raw optical recording. Times are seconds from the first sample. */
data class PpgBeats(
    val timesSec: List<Double>,
    /** Intervals between consecutive detected beats, in milliseconds (may still contain a few bad values: see [Ppg.plausible]). */
    val rrMs: List<Double>,
    val durationSec: Double,
    /** Share of the packets that never arrived. */
    val lostShare: Double,
    /**
     * Runs of consecutive valid intervals. An interval is cut when lost packets lie between its beats, or when it is about
     * double or half its neighbours (a missed or split beat),
     * so every segment is a contiguous beat series (what a rhythm analysis needs).
     */
    val segments: List<List<Double>>,
    /** Share of the recording during which the accelerometer shows the arm moving. */
    val motionShare: Double,
    /** 0..1: how trustworthy the beats are (plausible intervals, steadiness, no motion). */
    val quality: Double,
) {
    /** The longest contiguous beat series: what a breathing-rate estimate needs. */
    val longestSegment: List<Double> get() = segments.maxByOrNull { it.size }.orEmpty()

    /** RMSSD over the successive differences inside each segment only: a difference across a cut is not a heartbeat difference. */
    val rmssd: Double? get() {
        val diffs = segments.flatMap { seg -> seg.zipWithNext { a, b -> b - a } }
        return if (diffs.size < 2) null else sqrt(diffs.sumOf { it * it } / diffs.size)
    }

    val sdnn: Double? get() = segments.flatten().let { if (it.size < 2) null else Stats.sd(it) }

    val bpm: Double? get() = if (rrMs.isEmpty()) null else 60_000.0 / Stats.median(rrMs.filter(Ppg::plausible).ifEmpty { rrMs })
}

/**
 * Beat detection on the R09's raw optical stream (25 Hz, 4.5% of the packets lost). Steps: the time axis comes from the sequence
 * numbers and the lost samples are filled linearly; zero-phase band-pass 0.6-3.5 Hz (Butterworth, forward-backward); local maxima
 * refined with a parabola for sub-sample timing; beats are tracked from peak to peak inside a window around the expected period, which
 * keeps the diastolic bump from being taken for a beat. Both optical values are tried (and both signs) and the steadiest result wins.
 */
private typealias Gaps = List<ClosedFloatingPointRange<Double>>

object Ppg {
    const val FS = 25.0
    private const val HP_HZ = 0.6
    private const val LP_HZ = 3.5
    private const val PAD_SECONDS = 3
    private const val MIN_PERIOD_S = 0.35
    private const val MAX_PERIOD_S = 1.6
    private const val EARLY = 0.55
    private const val LATE = 1.3
    private const val START_PERIODS = 1.5
    private const val RECENT_INTERVALS = 6
    private const val AMPLITUDE_FRACTION = 0.3
    private const val MIN_RR_MS = 300.0
    private const val MAX_RR_MS = 2000.0
    private const val JUMP_MS = 250.0
    private const val MOTION_WINDOW_S = 2
    private const val MOTION_LIMIT = 5.0
    private const val MIN_SAMPLES = 100
    private const val MS = 1000.0
    private const val HALF = 0.5
    private const val TWO = 2.0
    private const val MIN_BEATS_FOR_QUALITY = 3
    private const val NEIGHBOURS = 5
    private const val MISSED_RATIO = 1.6
    private const val EXTRA_RATIO = 0.72
    private const val GAP_SAMPLES = 12
    private const val GAP_MARGIN_S = 0.3

    fun plausible(rrMs: Double): Boolean = rrMs in MIN_RR_MS..MAX_RR_MS

    /** Unwraps the byte sequence counter (wraps at 256) into increasing sample indices; a repeated value counts as a full wrap. */
    fun unwrap(seq: IntArray): IntArray {
        val index = IntArray(seq.size)
        for (i in 1 until seq.size) {
            val step = (seq[i] - seq[i - 1] + SEQ_WRAP) % SEQ_WRAP
            index[i] = index[i - 1] + if (step == 0) SEQ_WRAP else step
        }
        return index
    }

    /** Values on a uniform grid 0..index.last(): lost samples are linear interpolations of their neighbours. */
    fun fill(index: IntArray, values: DoubleArray): DoubleArray {
        val out = DoubleArray(index.last() + 1)
        for (k in 0 until index.size - 1) {
            val span = index[k + 1] - index[k]
            for (j in 0 until span) out[index[k] + j] = values[k] + (values[k + 1] - values[k]) * j / span
        }
        out[out.size - 1] = values.last()
        return out
    }

    /**
     * The sensor starts saturated and settles for several seconds with status 0; those samples are dropped (when the rest of the recording
     * has a status), and times then count from the first settled sample.
     */
    private fun settled(samples: List<RawSample>): List<RawSample> =
        if (samples.count { it.status != 0 } * 2 >= samples.size) samples.dropWhile { it.status == 0 } else samples

    fun analyze(recording: List<RawSample>): PpgBeats {
        val samples = settled(recording)
        if (samples.size < MIN_SAMPLES) return PpgBeats(emptyList(), emptyList(), 0.0, 0.0, emptyList(), 0.0, 0.0)
        val first = unwrap(IntArray(samples.size) { samples[it].seq })
        val index = IntArray(first.size) { first[it] - first[0] }
        val grid = index.last() + 1
        val lost = 1.0 - samples.size.toDouble() / grid
        val gaps = lostRuns(index)
        val candidates = listOf(samples.map { it.a }, samples.map { it.b }).flatMap { channel ->
            val x = silenceGaps(bandpass(fill(index, DoubleArray(channel.size) { channel[it].toDouble() })), gaps)
            listOf(1.0, -1.0).map { sign -> detect(DoubleArray(x.size) { x[it] * sign }).filterNot { inGap(it, gaps) } }
        }
        val best = candidates.maxBy { score(it, grid / FS, gaps) }
        val segments = segments(best, gaps)
        val rr = segments.flatten()
        val motion = motionShare(index, samples)
        val quality = quality(best, grid / FS, gaps) * (1 - motion).coerceAtLeast(0.0)
        return PpgBeats(best, rr, grid / FS, lost, segments, motion, quality)
    }

    /** Lost stretches longer than [GAP_SAMPLES] samples, as time ranges in seconds. */
    private fun lostRuns(index: IntArray): Gaps =
        (0 until index.size - 1).filter { index[it + 1] - index[it] > GAP_SAMPLES }.map { (index[it] / FS)..(index[it + 1] / FS) }

    private fun inGap(t: Double, gaps: Gaps) =
        gaps.any { t >= it.start - GAP_MARGIN_S && t <= it.endInclusive + GAP_MARGIN_S }

    private fun silenceGaps(x: DoubleArray, gaps: Gaps): DoubleArray {
        val out = x.copyOf()
        for (gap in gaps) {
            val from = maxOf(0, ((gap.start - GAP_MARGIN_S) * FS).toInt())
            val to = minOf(x.size - 1, ((gap.endInclusive + GAP_MARGIN_S) * FS).toInt())
            for (i in from..to) out[i] = 0.0
        }
        return out
    }

    private fun segments(beats: List<Double>, gaps: Gaps): List<List<Double>> {
        val raw = beats.zipWithNext { a, b -> (b - a) * MS }
        val out = mutableListOf<MutableList<Double>>()
        var current = mutableListOf<Double>()
        for ((i, pair) in beats.zipWithNext().withIndex()) {
            if (spansGap(pair, gaps) || missedOrExtra(raw, i)) {
                if (current.isNotEmpty()) out += current
                current = mutableListOf()
            } else {
                current += raw[i]
            }
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    private fun spansGap(pair: Pair<Double, Double>, gaps: Gaps) =
        gaps.any { it.start > pair.first && it.endInclusive < pair.second }

    /** An interval far from its neighbours' median is a beat the detector missed (about double) or split (about half), not a heartbeat. */
    private fun missedOrExtra(raw: List<Double>, i: Int): Boolean {
        val around = raw.subList(maxOf(0, i - NEIGHBOURS), minOf(raw.size, i + NEIGHBOURS + 1))
        val ratio = raw[i] / Stats.median(around)
        return ratio > MISSED_RATIO || ratio < EXTRA_RATIO
    }

    /** Steadier and more complete is better: plausible, jump-free intervals covering the recorded (not lost) time. */
    private fun score(beats: List<Double>, seconds: Double, gaps: Gaps): Double {
        val rr = segments(beats, gaps).flatMap { seg -> seg }
        if (rr.size < 2) return 0.0
        val ok = rr.count { plausible(it) }.toDouble() / rr.size
        val jumps = segments(beats, gaps).sumOf { seg -> seg.zipWithNext().count { (a, b) -> abs(b - a) > JUMP_MS } }.toDouble() / rr.size
        val recorded = (seconds - gaps.sumOf { it.endInclusive - it.start }).coerceAtLeast(1.0)
        return ok * (1 - jumps) * (rr.sum() / MS / recorded).coerceAtMost(1.0)
    }

    private fun quality(beats: List<Double>, seconds: Double, gaps: Gaps): Double =
        if (beats.size < MIN_BEATS_FOR_QUALITY) 0.0 else score(beats, seconds, gaps)

    private fun motionShare(index: IntArray, samples: List<RawSample>): Double {
        fun axis(pick: (RawSample) -> Int) = fill(index, DoubleArray(samples.size) { pick(samples[it]).toDouble() })
        val x = axis { it.x }
        val y = axis { it.y }
        val z = axis { it.z }
        val magnitude = DoubleArray(x.size) { sqrt(x[it] * x[it] + y[it] * y[it] + z[it] * z[it]) }
        val window = (MOTION_WINDOW_S * FS).toInt()
        val windows = magnitude.size / window
        if (windows == 0) return 0.0
        val moving = (0 until windows).count { w ->
            val part = magnitude.sliceArray(w * window until (w + 1) * window).toList()
            Stats.sd(part) > MOTION_LIMIT
        }
        return moving.toDouble() / windows
    }

    // ---- signal processing -------------------------------------------------------------------------------------------------

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun apply(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size)
            var x1 = 0.0
            var x2 = 0.0
            var y1 = 0.0
            var y2 = 0.0
            for (i in x.indices) {
                y[i] = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1
                x1 = x[i]
                y2 = y1
                y1 = y[i]
            }
            return y
        }
    }

    private fun butterworth(highPass: Boolean, hz: Double): Biquad {
        val k = tan(PI * hz / FS)
        val sq = sqrt(2.0)
        val a0 = 1 + sq * k + k * k
        val a1 = 2 * (k * k - 1) / a0
        val a2 = (1 - sq * k + k * k) / a0
        return if (highPass) Biquad(1 / a0, -TWO / a0, 1 / a0, a1, a2) else Biquad(k * k / a0, TWO * k * k / a0, k * k / a0, a1, a2)
    }

    /** Zero-phase band-pass: each biquad runs forward and backward on a signal padded with odd reflections at both ends. */
    private fun bandpass(x: DoubleArray): DoubleArray {
        val pad = minOf((PAD_SECONDS * FS).toInt(), x.size - 1)
        val padded = DoubleArray(x.size + 2 * pad) { i ->
            when {
                i < pad -> 2 * x[0] - x[pad - i]
                i >= pad + x.size -> 2 * x.last() - x[2 * x.size - 2 - (i - pad)]
                else -> x[i - pad]
            }
        }
        var y = padded
        for (filter in listOf(butterworth(true, HP_HZ), butterworth(false, LP_HZ))) {
            y = filter.apply(y).reversedArray()
            y = filter.apply(y).reversedArray()
        }
        return y.sliceArray(pad until pad + x.size)
    }

    /** Dominant pulse period (seconds) from the autocorrelation of the filtered signal. */
    private fun period(x: DoubleArray): Double {
        val lo = (MIN_PERIOD_S * FS).toInt()
        val hi = minOf((MAX_PERIOD_S * FS).toInt(), x.size / 2)
        var best = lo
        var bestValue = Double.NEGATIVE_INFINITY
        for (lag in lo..hi) {
            var sum = 0.0
            for (i in 0 until x.size - lag) sum += x[i] * x[i + lag]
            if (sum > bestValue) {
                bestValue = sum
                best = lag
            }
        }
        return best / FS
    }

    /**
     * Beat tracking: from the tallest early peak, the next beat is the tallest peak between 0.55 and 1.3 of the expected period (the expected
     * period is the median of the last intervals), so the smaller diastolic peak that follows each beat is never taken instead of it. A
     * missed beat shows up as a longer interval: the next peak after the window is then accepted.
     */
    private fun detect(x: DoubleArray): List<Double> {
        val first = period(x)
        val maxima = (1 until x.size - 1).filter { x[it] > x[it - 1] && x[it] >= x[it + 1] && x[it] > 0 }
        if (maxima.isEmpty()) return emptyList()
        val tall = maxima.map { x[it] }.sortedDescending()
        val threshold = AMPLITUDE_FRACTION * tall[(tall.size / 4).coerceAtMost(tall.size - 1)]
        val peaks = maxima.filter { x[it] >= threshold }
        if (peaks.isEmpty()) return emptyList()
        var current = peaks.filter { it / FS <= START_PERIODS * first }.maxByOrNull { x[it] } ?: peaks.first()
        val accepted = mutableListOf(current)
        val recent = ArrayDeque<Double>()
        var expected = first
        while (true) {
            val window = peaks.filter { it > current + (EARLY * expected * FS) && it <= current + (LATE * expected * FS) }
            val next = window.maxByOrNull { x[it] } ?: peaks.firstOrNull { it > current + (LATE * expected * FS) } ?: break
            recent.addLast((next - current) / FS)
            if (recent.size > RECENT_INTERVALS) recent.removeFirst()
            expected = Stats.median(recent.toList()).coerceIn(MIN_PERIOD_S, MAX_PERIOD_S)
            accepted += next
            current = next
        }
        return accepted.map { i ->
            val denominator = x[i - 1] - 2 * x[i] + x[i + 1]
            (i + if (denominator == 0.0) 0.0 else HALF * (x[i - 1] - x[i + 1]) / denominator) / FS
        }
    }

    private const val SEQ_WRAP = 256
}
