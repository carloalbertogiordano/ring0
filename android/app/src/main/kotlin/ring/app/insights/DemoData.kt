package ring.app.insights

import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import ring.app.data.Sample
import ring.app.data.SampleType
import ring.app.data.SleepEntity

/** Dati sintetici per provare grafici, recupero e allarmi senza aspettare giorni di dati reali. */
object DemoData {
    data class Demo(val samples: List<Sample>, val sleep: List<SleepEntity>)

    private const val DAY = 86_400L
    private const val DAYS = 14
    private const val HOUR = 3_600L

    /** `sick`: nelle ultime due notti temperatura e FC a riposo salgono e l'HRV scende. */
    fun generate(now: Long, sick: Boolean, zone: ZoneId = ZoneId.systemDefault()): Demo {
        val samples = mutableListOf<Sample>()
        val sleep = mutableListOf<SleepEntity>()
        val todayStart = Instant.ofEpochSecond(now).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
        for (back in DAYS downTo 0) {
            val dayStart = todayStart - back * DAY
            val lastTwo = sick && back <= 1
            fun hourOfDay(ts: Long) = ((ts - dayStart) / HOUR).toInt()
            var ts = dayStart
            // oggi: solo fino alle 07:00, cosi' la notte appena trascorsa e' completa e non si sovrappone ai dati veri
            val limit = if (back == 0) dayStart + 7 * HOUR else dayStart + DAY
            while (ts < limit) {
                val h = (ts - dayStart) / HOUR.toDouble()
                val night = h < 6.5 || h >= 23
                val circ = cos((h - 15) / 24 * 2 * PI) // massimo di pomeriggio
                val noise = sin(ts * 0.00113 + back) * 2.5 + sin(ts * 0.0071) * 1.5
                val hr = (if (night) 55.0 else 68.0 + 9 * circ) + noise + (if (lastTwo && night) 7.0 else 0.0)
                samples += Sample(SampleType.HR, ts, hr.coerceAtLeast(42.0), demo = true)
                if ((ts - dayStart) % (30 * 60L) == 0L) {
                    val temp = (if (night) 36.2 else 36.5 + 0.35 * circ) + sin(ts * 0.0009 + back) * 0.12 + (if (lastTwo && night) 0.9 else 0.0)
                    val hrv = (if (night) 46.0 else 36.0 + 4 * circ) + noise * 1.2 - (if (lastTwo && night) 15.0 else 0.0)
                    val stress = (if (night) 20.0 else 38.0 - 10 * circ) + noise * 2 + (if (lastTwo) 18.0 else 0.0)
                    samples += Sample(SampleType.TEMP, ts, temp, demo = true)
                    samples += Sample(SampleType.HRV, ts, hrv.coerceAtLeast(12.0), demo = true)
                    samples += Sample(SampleType.STRESS, ts, stress.coerceIn(5.0, 95.0), demo = true)
                }
                if ((ts - dayStart) % HOUR == 0L) {
                    val dip = if (night && hourOfDay(ts) == 3) -4.0 else 0.0
                    samples += Sample(SampleType.SPO2, ts, 96.5 + sin(ts * 0.002) * 1.2 + dip, demo = true)
                }
                ts += 5 * 60L
            }
            val start = dayStart - HOUR + 30 * 60 // 23:30 del giorno prima
            val end = dayStart + 6 * HOUR + 40 * 60
            val total = ((end - start) / 60).toInt()
            val awake = if (lastTwo) 55 else 28
            val deep = if (lastTwo) 60 else 95
            val rem = if (lastTwo) 70 else 100
            sleep += SleepEntity(start, end, total - awake - deep - rem, deep, rem, awake, demo = true)
        }
        return Demo(samples, sleep)
    }
}
