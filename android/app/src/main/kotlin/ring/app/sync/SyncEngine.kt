package ring.app.sync

import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import ring.app.ble.RingSession
import ring.app.data.RingDao
import ring.app.data.Sample
import ring.app.data.SampleType
import ring.app.data.SleepEntity
import ring.protocol.commands.Battery
import ring.protocol.commands.BatteryCommand
import ring.protocol.commands.Capabilities
import ring.protocol.commands.Goals
import ring.protocol.commands.GoalsCommand
import ring.protocol.commands.StepsCommand
import ring.protocol.commands.HrLogCommand
import ring.protocol.commands.Reading
import ring.protocol.commands.SetTimeCommand
import ring.protocol.commands.SleepCommand
import ring.protocol.commands.SleepStageType
import ring.protocol.commands.SlotLogCommand
import ring.protocol.commands.Spo2Command
import ring.protocol.commands.TemperatureCommand

data class SyncReport(
    val battery: Battery?,
    val capabilities: Capabilities?,
    val goals: Goals?,
    val stepsSynced: Boolean,
    val inserted: Int,
    val errors: List<String>,
)

/** Downloads the latest days from the ring and stores them (idempotent: key is (type, instant)). */
class SyncEngine(private val dao: RingDao) {
    suspend fun sync(session: RingSession, days: Int, onProgress: (String, Float) -> Unit = { _, _ -> }): SyncReport {
        val errors = mutableListOf<String>()
        var inserted = 0
        val total = TOTAL_FIXED_STEPS + 3 * days
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
            if (readings.isEmpty()) return
            dao.upsertSamples(readings.map { Sample(type, it.time.epochSecond, it.value) })
            inserted += readings.size
        }

        val caps = step("set-time") { SetTimeCommand.set(session.v1) }
        val battery = step("battery") { BatteryCommand.read(session.v1) }
        battery?.let { save(SampleType.BATTERY, listOf(Reading(Instant.now(), it.level.toDouble()))) }

        val today = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)
        for (d in 0 until days) {
            val dayStart = today.minusSeconds(d * SECONDS_PER_DAY)
            step("hr-log d-$d") { HrLogCommand.read(session.v1, dayStart)?.let { save(SampleType.HR, it.readings()) } }
            step("steps d-$d") { StepsCommand.read(session.v1, d).let { slots -> save(SampleType.STEPS, slots.map { Reading(it.time, it.steps.toDouble()) }) } }
            step("hrv d-$d") { SlotLogCommand.readHrv(session.v1, d)?.let { save(SampleType.HRV, it.readings(dayStart)) } }
        }
        step("stress") { SlotLogCommand.readStress(session.v1)?.let { save(SampleType.STRESS, it.readings(today)) } }
        step("temperature") {
            TemperatureCommand.read(session.big, days).forEach { day ->
                save(SampleType.TEMP, day.readings(today.minusSeconds(day.daysAgo * SECONDS_PER_DAY)))
            }
        }
        step("spo2") {
            Spo2Command.read(session.big).forEach { day ->
                save(SampleType.SPO2, day.readings(today.minusSeconds(day.daysAgo * SECONDS_PER_DAY)))
            }
        }
        step("sleep") {
            val sessions = SleepCommand.read(session.big).map { s ->
                val (start, end) = s.window(today.minusSeconds(s.daysAgo * SECONDS_PER_DAY))
                fun minutes(t: SleepStageType) = s.stages.filter { it.type == t }.sumOf { it.minutes }
                SleepEntity(
                    start.epochSecond, end.epochSecond,
                    minutes(SleepStageType.LIGHT), minutes(SleepStageType.DEEP), minutes(SleepStageType.REM), minutes(SleepStageType.AWAKE),
                )
            }
            dao.upsertSleep(sessions)
            inserted += sessions.size
        }
        val goals = step("goals") { GoalsCommand.read(session.v1) }
        return SyncReport(battery, caps, goals, errors.none { it.startsWith("steps") }, inserted, errors)
    }

    /** Quick download of today's step slots (used right before a check, so the "moving?" context is fresh). */
    suspend fun syncSteps(session: RingSession): Boolean =
        try {
            val slots = StepsCommand.read(session.v1, 0)
            if (slots.isNotEmpty()) dao.upsertSamples(slots.map { Sample(SampleType.STEPS, it.time.epochSecond, it.steps.toDouble()) })
            true
        } catch (e: Exception) {
            Log.w(TAG, "steps sync failed", e)
            false
        }

    private companion object {
        const val TAG = "SyncEngine"
        const val SECONDS_PER_DAY = 86_400L
        const val TOTAL_FIXED_STEPS = 6
    }
}
