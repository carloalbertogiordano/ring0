package ring.domain

import org.junit.jupiter.api.Assumptions.assumeTrue
import ring.protocol.commands.RawSample
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FS = 25.0

/** A synthetic PPG at 25 Hz: a pulse with a dicrotic bump at exact (sub-sample) beat times, baseline wander, noise, lost packets. */
private class Synth(
    val rrMs: List<Double>,
    val noise: Double = 0.05,
    val loss: Double = 0.045,
    val inverted: Boolean = false,
    val seed: Int = 7,
    val motion: ClosedFloatingPointRange<Double>? = null,
) {
    val beatTimes: List<Double> = rrMs.runningFold(1.0) { t, rr -> t + rr / 1000 }

    private fun pulse(t: Double): Double = exp(-((t / 0.10) * (t / 0.10))) + 0.35 * exp(-(((t - 0.32) / 0.12) * ((t - 0.32) / 0.12)))

    fun samples(): List<RawSample> {
        val rnd = Random(seed)
        val duration = beatTimes.last() + 1.0
        val out = mutableListOf<RawSample>()
        var seq = 0
        for (i in 0 until (duration * FS).toInt()) {
            val t = i / FS
            val signal = beatTimes.sumOf { pulse(t - it) } + 2.0 * sin(2 * PI * 0.15 * t) + 0.7 * sin(2 * PI * 0.35 * t + 1)
            val v = (if (inverted) -1 else 1) * (signal + noise * rnd.nextDouble(-1.0, 1.0) * 2)
            val moving = motion?.let { t in it } ?: false
            val ax = if (moving) (80 * sin(2 * PI * 1.7 * t)).toInt() else 0
            if (rnd.nextDouble() >= loss) {
                out += RawSample(seq % 256, 0x60, (40_000 + 800 * v).toInt(), (30_000 + 600 * v).toInt(), ax, if (moving) 60 else -4, 5)
            }
            seq++
        }
        return out
    }
}

private fun rsa(seconds: Int, base: Double = 830.0, amp: Double = 60.0, breathHz: Double = 0.25): List<Double> {
    val rr = mutableListOf<Double>()
    var t = 0.0
    while (t < seconds) {
        val v = base + amp * sin(2 * PI * breathHz * t)
        rr += v
        t += v / 1000
    }
    return rr
}

private fun rmssd(rr: List<Double>): Double = sqrt(rr.zipWithNext { a, b -> (b - a) * (b - a) }.average())

class PpgTest {
    @Test fun `sequence numbers are unwrapped and lost samples are filled linearly`() {
        val index = Ppg.unwrap(intArrayOf(254, 255, 0, 1, 3, 4)) // 2 is lost
        assertEquals(listOf(0, 1, 2, 3, 5, 6), index.toList())
        val filled = Ppg.fill(index, doubleArrayOf(10.0, 11.0, 12.0, 13.0, 15.0, 16.0))
        assertEquals(listOf(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0), filled.toList())
    }

    @Test fun `beats of a clean synthetic pulse are found with accurate intervals despite lost packets`() {
        val synth = Synth(rsa(120))
        val beats = Ppg.analyze(synth.samples())
        assertTrue(abs(beats.timesSec.size - synth.beatTimes.size) <= 2, "beats ${beats.timesSec.size} vs ${synth.beatTimes.size}")
        // the true intervals can be matched one to one: compare each detected interval with the nearest true one
        val truth = synth.rrMs
        val errors = beats.rrMs.map { d -> truth.minOf { abs(it - d) } }.sorted()
        assertTrue(errors[errors.size / 2] <= 14.0, "median error ${errors[errors.size / 2]}")
        assertTrue(errors[(errors.size * 95) / 100] <= 45.0, "95th percentile ${errors[(errors.size * 95) / 100]}")
        assertTrue(abs(rmssd(beats.rrMs) - rmssd(truth)) / rmssd(truth) < 0.35, "rmssd ${rmssd(beats.rrMs)} vs ${rmssd(truth)}")
        assertTrue(beats.lostShare in 0.02..0.08, "lost ${beats.lostShare}")
        assertTrue(beats.quality > 0.8)
    }

    @Test fun `a stall of lost packets cuts the interval series instead of making one huge interval`() {
        val all = Synth(rsa(90), loss = 0.0).samples()
        val stalled = all.filterIndexed { i, _ -> i !in 1000..1140 } // 141 packets (5.6 s) never arrive
        val beats = Ppg.analyze(stalled)
        assertEquals(2, beats.segments.size)
        assertTrue(beats.rrMs.all { Ppg.plausible(it) }, "no interval may span the stall: ${beats.rrMs.filterNot { Ppg.plausible(it) }}")
        assertTrue(beats.lostShare > 0.05)
    }

    @Test fun `rmssd and sdnn never use the step between two segments`() {
        val segments = listOf(listOf(800.0, 810.0, 800.0), listOf(1000.0, 1010.0, 1000.0))
        val beats = PpgBeats(emptyList(), segments.flatten(), 30.0, 0.0, segments, 0.0, 0.9)
        assertEquals(10.0, beats.rmssd!!, 1e-9) // the 190 ms step between the segments is not a heartbeat difference
        assertEquals(100.0, beats.sdnn!!, 15.0)
        assertEquals(null, PpgBeats(emptyList(), emptyList(), 30.0, 0.0, emptyList(), 0.0, 0.0).rmssd)
    }

    @Test fun `an inverted signal gives the same beats`() {
        val rr = rsa(90)
        val normal = Ppg.analyze(Synth(rr).samples())
        val inverted = Ppg.analyze(Synth(rr, inverted = true).samples())
        assertTrue(abs(normal.timesSec.size - inverted.timesSec.size) <= 1)
    }

    @Test fun `a noisy signal still has no wild intervals`() {
        val beats = Ppg.analyze(Synth(rsa(120), noise = 0.35, seed = 3).samples())
        val wild = beats.rrMs.count { it < 400 || it > 1500 }
        assertTrue(wild.toDouble() / beats.rrMs.size < 0.05, "wild $wild of ${beats.rrMs.size}")
    }

    @Test fun `the arm moving is flagged from the accelerometer`() {
        val beats = Ppg.analyze(Synth(rsa(120), motion = 40.0..70.0).samples())
        assertTrue(beats.motionShare in 0.15..0.40, "motion ${beats.motionShare}")
        assertEquals(0.0, Ppg.analyze(Synth(rsa(60)).samples()).motionShare, 0.01)
    }

    @Test fun `no pulse gives no beats and no crash`() {
        assertEquals(0, Ppg.analyze(emptyList()).timesSec.size)
        val flat = List(300) { RawSample(it % 256, 0x60, 40_000, 30_000, 0, 0, 0) }
        val beats = Ppg.analyze(flat)
        assertTrue(beats.timesSec.size < 3)
        assertTrue(beats.quality < 0.5)
    }

    /** Local regression on a real recording (health data, never committed): runs only when the file exists. */
    @Test fun `a real recording at rest gives physiological beats`() {
        val log = File("../../tools/ppg/local/still_120s.log")
        assumeTrue(log.exists())
        val samples = log.readLines().mapNotNull { line ->
            Regex("""t=\d+ms ([0-9a-f]{28})""").find(line)?.let { m ->
                val b = m.groupValues[1].chunked(2).map { it.toInt(16) }

                fun u(i: Int) = b[i] or (b[i + 1] shl 8)

                fun s(i: Int) = u(i).toShort().toInt()
                RawSample(b[2], b[3], u(4), u(6), s(8), s(10), s(12))
            }
        }.drop(3)
        val beats = Ppg.analyze(samples)
        val jumps = beats.segments.sumOf { seg -> seg.zipWithNext().count { (a, b) -> abs(b - a) > 200 } }
        assertTrue(beats.rrMs.size in 115..160, "intervals ${beats.rrMs.size}")
        assertTrue(jumps.toDouble() / beats.rrMs.size < 0.08, "jumps $jumps of ${beats.rrMs.size}")
        assertTrue(beats.segments.size >= 2, "a 5.7 s stall in the recording must cut the series")
        assertTrue(RhythmCheck.analyze(beats.segments).level != RhythmLevel.IRREGULAR, "a resting pulse must not be called irregular")
    }
}
