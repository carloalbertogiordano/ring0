package ring.domain

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import ring.protocol.commands.SleepStage
import ring.protocol.commands.SleepStageType

data class SleepNight(val startEpoch: Long, val endEpoch: Long, val stages: List<SleepStage>)

data class SleepSummary(
    val timeInBedMin: Int,
    val totalSleepMin: Int,
    val efficiency: Double,
    val latencyMin: Int,
    val wasoMin: Int,
    val lightPct: Double,
    val deepPct: Double,
    val remPct: Double,
)

/** Sleep metrics from a session and its stages (light, deep, REM, awake). */
object SleepMetrics {
    private const val SECONDS_PER_MINUTE = 60

    fun summarize(night: SleepNight): SleepSummary {
        fun minutes(t: SleepStageType) = night.stages.filter { it.type == t }.sumOf { it.minutes }
        val light = minutes(SleepStageType.LIGHT)
        val deep = minutes(SleepStageType.DEEP)
        val rem = minutes(SleepStageType.REM)
        val awake = minutes(SleepStageType.AWAKE)
        val asleep = light + deep + rem
        val inBed = maxOf(((night.endEpoch - night.startEpoch) / SECONDS_PER_MINUTE).toInt(), asleep + awake)
        val latency = night.stages.takeWhile { it.type == SleepStageType.AWAKE }.sumOf { it.minutes }
        val trailing = night.stages.reversed().takeWhile { it.type == SleepStageType.AWAKE }.sumOf { it.minutes }
        return SleepSummary(
            timeInBedMin = inBed,
            totalSleepMin = asleep,
            efficiency = if (inBed > 0) asleep.toDouble() / inBed else 0.0,
            latencyMin = latency,
            // risvegli a sonno iniziato: veglia totale meno quella iniziale e quella finale
            wasoMin = (awake - latency - trailing).coerceAtLeast(0),
            lightPct = pct(light, asleep),
            deepPct = pct(deep, asleep),
            remPct = pct(rem, asleep),
        )
    }

    private fun pct(part: Int, total: Int) = if (total > 0) 100.0 * part / total else 0.0

    /** Debito di sonno: minuti mancanti rispetto all'obiettivo, sommati sulle ultime notti (surplus ignorato). */
    fun sleepDebtMin(totalSleepPerNight: List<Int>, targetMin: Int = 480, nights: Int = 7): Int =
        totalSleepPerNight.takeLast(nights).sumOf { (targetMin - it).coerceAtLeast(0) }
}

/** Regularity: how consistent the sleep onset times are (circular statistics, handles midnight wraparound). */
object SleepRegularity {
    private const val MINUTES_PER_DAY = 1440.0
    private const val ZERO_SCORE_SD_MIN = 90.0

    /** Circular standard deviation (minutes) of the times of day at which sleep starts. */
    fun onsetSdMinutes(onsetMinuteOfDay: List<Int>): Double? {
        if (onsetMinuteOfDay.size < 2) return null
        val angles = onsetMinuteOfDay.map { 2 * PI * it / MINUTES_PER_DAY }
        val c = angles.sumOf { cos(it) } / angles.size
        val s = angles.sumOf { sin(it) } / angles.size
        val r = sqrt(c * c + s * s).coerceIn(1e-12, 1.0)
        return sqrt(-2 * kotlin.math.ln(r)) * MINUTES_PER_DAY / (2 * PI)
    }

    /** 100 = identical onset times, 0 = a spread of 90 minutes or more. */
    fun score(onsetMinuteOfDay: List<Int>): Int? =
        onsetSdMinutes(onsetMinuteOfDay)?.let { Stats.clamp(100 * (1 - it / ZERO_SCORE_SD_MIN)).toInt() }

    /** Circular mean time of day (minutes since midnight, 0..1439). */
    fun meanOnsetMinute(onsetMinuteOfDay: List<Int>): Int {
        val angles = onsetMinuteOfDay.map { 2 * PI * it / MINUTES_PER_DAY }
        val mean = atan2(angles.sumOf { sin(it) }, angles.sumOf { cos(it) })
        return (((mean * MINUTES_PER_DAY / (2 * PI)) + MINUTES_PER_DAY) % MINUTES_PER_DAY).toInt()
    }
}
