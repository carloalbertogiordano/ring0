package ring.app.sync

import ring.app.alerts.AlertKind
import ring.app.data.IncidentEntity
import ring.app.data.RingDao
import ring.app.data.SampleType
import ring.domain.TriageInput
import ring.domain.TriageResult

/** Writes incidents: a snapshot when an alert fires, and the result when the user runs a check. */
class IncidentRecorder(private val dao: RingDao) {
    /** Alert kinds worth a snapshot of the surrounding context. */
    val incidentKinds = setOf(
        AlertKind.HR_HIGH, AlertKind.HR_LOW, AlertKind.SPO2_LOW, AlertKind.TEMP_HIGH, AlertKind.STRESS_HIGH, AlertKind.ILLNESS,
    )

    suspend fun recordAlert(kind: String, value: Double, now: Long): Long {
        val hr = dao.range(SampleType.HR, now - HOUR, now)
        return dao.insertIncident(
            IncidentEntity(
                ts = now, source = kind, triggerValue = value,
                hrTrend = hr.joinToString(";") { "${it.ts}:${it.value.toInt()}" },
                bpm = hr.lastOrNull()?.value?.toInt(),
                spo2 = latest(SampleType.SPO2)?.toInt(),
                stress = latest(SampleType.STRESS),
                skinTemp = latest(SampleType.TEMP),
            ),
        )
    }

    /** A not-yet-saved incident filled with the current context, for entries the user adds by hand. */
    suspend fun manualDraft(now: Long): IncidentEntity {
        val hr = dao.range(SampleType.HR, now - HOUR, now)
        return IncidentEntity(
            ts = now, source = MANUAL_ENTRY,
            hrTrend = hr.joinToString(";") { "${it.ts}:${it.value.toInt()}" },
            bpm = hr.lastOrNull()?.value?.toInt(),
            spo2 = latest(SampleType.SPO2)?.toInt(),
            stress = latest(SampleType.STRESS),
            skinTemp = latest(SampleType.TEMP),
        )
    }

    /** Inserts a new incident (id 0) or updates an existing one; returns its id. */
    suspend fun save(incident: IncidentEntity): Long =
        if (incident.id == 0L) dao.insertIncident(incident) else incident.id.also { dao.updateIncident(incident) }

    suspend fun delete(id: Long) = dao.deleteIncident(id)

    /** Attaches a check to the latest open incident of the last hour, or opens a manual one. Returns the incident id. */
    suspend fun saveCheck(now: Long, input: TriageInput, rmssd: Double?, result: TriageResult): Long {
        val open = dao.latestUnchecked(now - HOUR)
        val base = open ?: IncidentEntity(
            ts = now, source = MANUAL,
            hrTrend = dao.range(SampleType.HR, now - HOUR, now).joinToString(";") { "${it.ts}:${it.value.toInt()}" },
        )
        val filled = base.copy(
            level = result.level.name,
            causes = result.causes.joinToString(",") { it.name },
            advice = result.advice.joinToString(",") { it.name },
            symptoms = input.symptoms.joinToString(",") { it.name },
            bpm = input.bpm ?: base.bpm,
            rmssd = rmssd,
            spo2 = input.spo2 ?: base.spo2,
            stress = input.stress?.toDouble() ?: base.stress,
            skinTemp = input.skinTemp ?: base.skinTemp,
            checked = true,
        )
        return save(filled)
    }

    private suspend fun latest(type: String): Double? = dao.lastN(type, 1).firstOrNull()?.value

    companion object {
        const val MANUAL = "manual"
        const val MANUAL_ENTRY = "manual_entry"
        private const val HOUR = 3_600L
    }
}
