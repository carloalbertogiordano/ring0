package ring.app.sync

import android.util.Log
import ring.app.ble.RingSession
import ring.app.data.RingDao
import ring.app.data.Sample
import ring.app.data.SampleType
import ring.app.data.SleepEntity
import ring.protocol.commands.Battery
import ring.protocol.commands.BatteryCommand
import ring.protocol.commands.Capabilities
import ring.protocol.commands.HrLogCommand
import ring.protocol.commands.Reading
import ring.protocol.commands.SetTimeCommand
import ring.protocol.commands.SleepCommand
import ring.protocol.commands.SleepSession
import ring.protocol.commands.SleepStageType
import ring.protocol.commands.SlotLogCommand
import ring.protocol.commands.Spo2Command
import ring.protocol.commands.TemperatureCommand
import ring.protocol.commands.TemperatureDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class SyncReport(
    val battery: Battery?,
    val capabilities: Capabilities?,
    val inserted: Int,
    val errors: List<String>,
)

/** Downloads the latest days from the ring and stores them (idempotent: key is (type, instant)). */
class SyncEngine(private val dao: RingDao) {
    private val wear = WearFilter(dao)

    /** Removes previously stored readings from periods when the ring was not worn. */
    suspend fun cleanNonWear() = wear.cleanHistory()

    suspend fun sync(session: RingSession, days: Int, onProgress: (String, Float) -> Unit = { _, _ -> }): SyncReport {
        val errors = mutableListOf<String>()
        var inserted = 0
        val total = TOTAL_FIXED_STEPS + 2 * days
        var done = 0

        suspend fun <T> step(name: String, block: suspend () -> T): T? =
            try {
                onProgress(name, done.toFloat() / total)
                block()
            } catch (e: Exception) {
                Log.w(TAG, "sync step $name failed", e)
                errors += "$name: ${e.message ?: e.javaClass.simpleName}"
                null
            } finally {
                done++
            }

        suspend fun save(type: String, readings: List<Reading>) {
            val kept = if (type in WearFilter.FILTERED_TYPES) wear.keepWorn(readings) else readings
            if (kept.isEmpty()) return
            dao.upsertSamples(kept.map { Sample(type, it.time.epochSecond, it.value) })
            inserted += kept.size
        }

        val caps = step("set-time") { SetTimeCommand.set(session.v1) }
        val battery = step("battery") { BatteryCommand.read(session.v1) }
        battery?.let { save(SampleType.BATTERY, listOf(Reading(Instant.now(), it.level.toDouble()))) }

        val today = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)
        for (d in 0 until days) {
            val dayStart = today.minusSeconds(d * SECONDS_PER_DAY)
            step("hr-log d-$d") { HrLogCommand.read(session.v1, dayStart)?.let { save(SampleType.HR, it.readings()) } }
            step("hrv d-$d") { SlotLogCommand.readHrv(session.v1, d)?.let { save(SampleType.HRV, it.readings(dayStart)) } }
        }
        step("stress") { SlotLogCommand.readStress(session.v1)?.let { save(SampleType.STRESS, it.readings(today)) } }
        step("temperature") {
            TemperatureCommand.read(session.big, days).forEach { day ->
                Log.i(
                    TAG,
                    "temperature block daysAgo=${day.daysAgo} step=${day.intervalMinutes} nonzero=${day.samples.count {
                        it > 0
                    }}/${day.samples.size} last=${day.samples.indexOfLast { it > 0 }}",
                )
                save(SampleType.TEMP, day.readings(today.minusSeconds(day.daysAgo * SECONDS_PER_DAY)))
            }
        }
        step("spo2") {
            Spo2Command.read(session.big).forEach { day ->
                save(SampleType.SPO2, day.readings(today.minusSeconds(day.daysAgo * SECONDS_PER_DAY)))
            }
        }
        step("sleep") { inserted += syncSleep(session, today) }
        return SyncReport(battery, caps, inserted, errors)
    }

    /** Downloads the sleep sessions and stores them; returns how many. */
    private suspend fun syncSleep(session: RingSession, today: Instant): Int {
        val raw = SleepCommand.read(session.big)
        raw.forEach(::logSleep)
        val sessions = raw.map { toEntity(it, today) }
        dao.upsertSleep(sessions)
        return sessions.size
    }

    // diagnostics only: counts and stage lengths, no raw values
    private fun logTemperature(day: TemperatureDay) {
        Log.i(TAG, "temperature block daysAgo=${day.daysAgo} step=${day.intervalMinutes} nonzero=${day.samples.count { it > 0 }}/${day.samples.size}")
    }

    private fun logSleep(s: SleepSession) {
        Log.i(
            TAG,
            "sleep session daysAgo=${s.daysAgo} start=${s.startMinute} end=${s.endMinute} stages=${s.stages.joinToString {
                "${it.type}:${it.minutes}"
            }}",
        )
    }

    private fun toEntity(s: SleepSession, today: Instant): SleepEntity {
        val (start, end) = s.window(today.minusSeconds(s.daysAgo * SECONDS_PER_DAY))

        fun minutes(t: SleepStageType) = s.stages.filter { it.type == t }.sumOf { it.minutes }
        return SleepEntity(
            start.epochSecond,
            end.epochSecond,
            minutes(SleepStageType.LIGHT),
            minutes(SleepStageType.DEEP),
            minutes(SleepStageType.REM),
            minutes(SleepStageType.AWAKE),
        )
    }

    private companion object {
        const val TAG = "SyncEngine"
        const val SECONDS_PER_DAY = 86_400L
        const val TOTAL_FIXED_STEPS = 6
    }
}
