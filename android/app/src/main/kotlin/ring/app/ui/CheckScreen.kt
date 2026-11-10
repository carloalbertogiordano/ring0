package ring.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ring.app.CheckPhase
import ring.app.Graph
import ring.app.R
import ring.domain.Symptom
import ring.domain.TriageAdvice
import ring.domain.TriageCause
import ring.domain.TriageLevel

fun symptomRes(s: Symptom): Int = when (s) {
    Symptom.CHEST_PAIN -> R.string.symptom_chest_pain
    Symptom.SHORTNESS_OF_BREATH -> R.string.symptom_shortness
    Symptom.DIZZINESS_OR_FAINTING -> R.string.symptom_dizzy
    Symptom.PALPITATIONS -> R.string.symptom_palpitations
    Symptom.EXERCISED -> R.string.symptom_exercised
    Symptom.CAFFEINE_OR_STIMULANT -> R.string.symptom_stimulant
    Symptom.FEVER_OR_CHILLS -> R.string.symptom_fever
}

fun causeRes(c: TriageCause): Int = when (c) {
    TriageCause.ACTIVITY -> R.string.cause_activity
    TriageCause.STRESS -> R.string.cause_stress
    TriageCause.POSSIBLE_ILLNESS -> R.string.cause_illness
    TriageCause.LOW_OXYGEN -> R.string.cause_low_oxygen
    TriageCause.STIMULANT -> R.string.cause_stimulant
    TriageCause.UNEXPLAINED -> R.string.cause_unexplained
}

fun adviceRes(a: TriageAdvice): Int = when (a) {
    TriageAdvice.REST_AND_RECHECK -> R.string.advice_rest
    TriageAdvice.MONITOR -> R.string.advice_monitor
    TriageAdvice.SEEK_MEDICAL_HELP -> R.string.advice_medical
}

fun levelTitleRes(l: TriageLevel): Int = when (l) {
    TriageLevel.NORMAL -> R.string.level_normal_title
    TriageLevel.WATCH -> R.string.level_watch_title
    TriageLevel.URGENT -> R.string.level_urgent_title
}

fun levelColor(l: TriageLevel): Color = when (l) {
    TriageLevel.NORMAL -> Term.accent
    TriageLevel.WATCH -> Term.warn
    TriageLevel.URGENT -> Term.crit
}

@androidx.compose.runtime.Composable
private fun CheckEditorHost(graph: Graph, incident: ring.app.data.IncidentEntity?, onDone: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (incident != null) {
        IncidentEditor(
            incident,
            onSave = {
                scope.launch {
                    graph.incidents.save(it)
                    onDone()
                }
            },
            onDelete = {
                scope.launch {
                    graph.incidents.delete(incident.id)
                    onDone()
                }
            },
            onDismiss = onDone,
        )
    }
}

/** "Why is my heart rate up?": symptoms, heart + SpO2 measurement, then the triage result. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheckScreen(graph: Graph, onClose: () -> Unit) {
    val status by graph.status.collectAsStateWithLifecycle()
    val check = status.check
    var symptoms by remember { mutableStateOf(emptySet<Symptom>()) }
    var editing by remember { mutableStateOf<ring.app.data.IncidentEntity?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val close = {
        graph.resetCheck()
        onClose()
    }
    BackHandler(onBack = close)
    CheckEditorHost(graph, editing) { editing = null }
    Column(Modifier.fillMaxSize().background(Term.bg).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
        T(stringResource(R.string.check_title), size = 15.sp, weight = FontWeight.Bold, spacing = 3.sp)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (check.phase) {
                CheckPhase.IDLE -> {
                    T(stringResource(R.string.check_intro), size = 12.sp, color = Term.dim)
                    Label(stringResource(R.string.check_symptoms_title))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Symptom.entries.forEach { s ->
                            TChip(stringResource(symptomRes(s)), s in symptoms) { symptoms = if (s in symptoms) symptoms - s else symptoms + s }
                        }
                    }
                    T(stringResource(R.string.not_medical), size = 10.sp, color = Term.faint)
                }

                CheckPhase.HEART -> {
                    TCard(hot = true) {
                        T(
                            if (status.liveBeats == 0) {
                                stringResource(R.string.check_step_heart_lock)
                            } else {
                                stringResource(R.string.check_step_heart, status.liveBeats, status.liveTarget)
                            },
                            color = Term.accent,
                        )
                        Ecg(active = true)
                    }
                }

                CheckPhase.SPO2 -> {
                    TCard(hot = true) {
                        T(stringResource(R.string.check_step_spo2), color = Term.accent)
                        Ecg(active = true)
                    }
                }

                CheckPhase.FAILED -> {
                    Banner(stringResource(R.string.check_failed_title), stringResource(R.string.check_failed), Term.crit)
                }

                CheckPhase.DONE -> {
                    check.result?.let { r ->
                        val color = levelColor(r.level)
                        Banner(stringResource(levelTitleRes(r.level)), stringResource(R.string.check_saved), color)
                        r.causes.forEach { T("• " + stringResource(causeRes(it)), size = 13.sp) }
                        r.advice.forEach { T(stringResource(adviceRes(it)), size = 12.sp, color = color) }
                        TCard {
                            Label(stringResource(R.string.check_vitals))
                            KeyValue(stringResource(R.string.tile_heart), check.bpm?.let { "$it bpm" } ?: "—")
                            KeyValue(stringResource(R.string.hrv_rmssd), check.rmssd?.let { "%.0f ms".format(it) } ?: "—")
                            KeyValue(stringResource(R.string.tile_spo2), check.spo2?.let { "$it %" } ?: "—")
                            KeyValue(stringResource(R.string.tile_stress), check.stress?.toString() ?: "—")
                            KeyValue(stringResource(R.string.tile_temp), check.skinTemp?.let { "%.1f °C".format(it) } ?: "—")
                        }
                        T(stringResource(R.string.not_medical), size = 10.sp, color = Term.faint)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (check.phase) {
                CheckPhase.IDLE -> {
                    TButton(stringResource(R.string.btn_start_check), { graph.runCheck(symptoms) }, Modifier.fillMaxWidth())
                }

                CheckPhase.DONE, CheckPhase.FAILED -> {
                    check.incidentId?.let { id ->
                        TButton(stringResource(R.string.btn_add_details), { scope.launch { editing = graph.dao.incidentById(id) } }, Modifier.fillMaxWidth())
                    }
                    TButton(stringResource(R.string.btn_check_again), { graph.resetCheck() }, Modifier.fillMaxWidth(), BtnKind.GHOST)
                }

                else -> {
                    Unit
                }
            }
            if (check.phase != CheckPhase.HEART && check.phase != CheckPhase.SPO2) {
                TButton(stringResource(R.string.btn_close), close, Modifier.fillMaxWidth(), BtnKind.GHOST)
            }
        }
    }
}

/** One incident of the log. */
@Composable
fun IncidentCard(incident: ring.app.data.IncidentEntity, onClick: () -> Unit) {
    val level = runCatching { TriageLevel.valueOf(incident.level) }.getOrNull()
    TCard(accent = level?.let { levelColor(it).copy(alpha = 0.6f) }, modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T(dayTime(incident.ts), size = 12.sp, weight = FontWeight.Bold)
            if (level !=
                null
            ) {
                TPill(stringResource(levelTitleRes(level)), levelColor(level))
            } else {
                T(stringResource(R.string.incident_unchecked), size = 11.sp, color = Term.dim)
            }
        }
        T(stringResource(incidentSourceRes(incident.source)), size = 12.sp)
        val causes = incident.causes.split(",").filter { it.isNotBlank() }.mapNotNull { runCatching { TriageCause.valueOf(it) }.getOrNull() }
        if (causes.isNotEmpty()) T(causes.map { stringResource(causeRes(it)) }.joinToString(" · "), size = 11.sp, color = Term.dim)
        val cats = parseCategories(incident.categories).map { stringResource(categoryRes(it)) }
        if (cats.isNotEmpty()) T(cats.joinToString(" · "), size = 11.sp, color = Term.info)
        if (incident.note.isNotBlank()) T(incident.note, size = 12.sp)
        T(
            stringResource(
                R.string.incident_vitals,
                incident.bpm?.toString() ?: "—",
                incident.spo2?.toString() ?: "—",
                incident.stress?.toInt()?.toString() ?: "—",
                incident.skinTemp?.let { "%.1f".format(it) } ?: "—",
            ),
            size = 10.sp,
            color = Term.dim,
        )
    }
}

fun incidentSourceRes(source: String): Int = when (source) {
    "hr_high" -> R.string.source_hr_high
    "hr_low" -> R.string.source_hr_low
    "spo2_low" -> R.string.source_spo2_low
    "temp_high" -> R.string.source_temp_high
    "stress_high" -> R.string.source_stress_high
    "illness" -> R.string.source_illness
    "manual_entry" -> R.string.source_manual_entry
    else -> R.string.source_manual
}
