package ring.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import ring.app.R
import ring.app.data.IncidentCategory
import ring.app.data.IncidentEntity

fun categoryRes(c: IncidentCategory): Int = when (c) {
    IncidentCategory.EXERCISE -> R.string.cat_exercise
    IncidentCategory.STRESS_EMOTION -> R.string.cat_stress_emotion
    IncidentCategory.ILLNESS -> R.string.cat_illness
    IncidentCategory.SUBSTANCES -> R.string.cat_substances
    IncidentCategory.SLEEP_FATIGUE -> R.string.cat_sleep_fatigue
    IncidentCategory.ENVIRONMENT -> R.string.cat_environment
    IncidentCategory.OTHER -> R.string.cat_other
}

fun parseCategories(csv: String): Set<IncidentCategory> =
    csv.split(",").filter { it.isNotBlank() }.mapNotNull { runCatching { IncidentCategory.valueOf(it) }.getOrNull() }.toSet()

private const val MAX_NOTE = 500

/** Edit the categories and the free note of an incident; `onDelete` is null for a draft that was never saved. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IncidentEditor(incident: IncidentEntity, onSave: (IncidentEntity) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var categories by remember(incident.id) { mutableStateOf(parseCategories(incident.categories)) }
    var note by remember(incident.id) { mutableStateOf(incident.note) }
    Dialog(onDismissRequest = onDismiss) {
        TCard(hot = true) {
            Box(Modifier.heightIn(max = 560.dp)) {
                androidx.compose.foundation.layout.Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    T(stringResource(R.string.incident_details), size = 15.sp, weight = androidx.compose.ui.text.font.FontWeight.Bold)
                    T(dayTime(incident.ts) + "  ·  " + stringResource(incidentSourceRes(incident.source)), size = 11.sp, color = Term.dim)
                    Label(stringResource(R.string.categories_title))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        IncidentCategory.entries.forEach { c ->
                            TChip(stringResource(categoryRes(c)), c in categories) { categories = if (c in categories) categories - c else categories + c }
                        }
                    }
                    Label(stringResource(R.string.note_title))
                    BasicTextField(
                        value = note,
                        onValueChange = { if (it.length <= MAX_NOTE) note = it },
                        textStyle = TextStyle(color = Term.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(Term.accent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).background(Term.bg).border(BorderStroke(1.dp, Term.line2)).padding(10.dp),
                        decorationBox = { inner ->
                            Box {
                                if (note.isEmpty()) T(stringResource(R.string.note_hint), size = 13.sp, color = Term.faint)
                                inner()
                            }
                        },
                    )
                    T("${note.length}/$MAX_NOTE", size = 10.sp, color = Term.faint)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TButton(stringResource(R.string.btn_cancel), onDismiss, Modifier.weight(1f), BtnKind.GHOST)
                        TButton(stringResource(R.string.btn_save), {
                            onSave(incident.copy(categories = categories.joinToString(",") { it.name }, note = note.trim()))
                        }, Modifier.weight(1f))
                    }
                    if (onDelete != null) TButton(stringResource(R.string.btn_delete), onDelete, Modifier.fillMaxWidth().padding(top = 2.dp), BtnKind.CRIT)
                }
            }
        }
    }
}
