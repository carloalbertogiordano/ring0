package ring.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import ring.app.Graph
import ring.app.R
import ring.app.data.AlarmEntity

private const val EVERY_DAY = 0b1111111
private const val WEEKDAYS = 0b0011111

@Composable
private fun daysSummary(mask: Int): String = when (mask) {
    0 -> stringResource(R.string.alarm_once)
    EVERY_DAY -> stringResource(R.string.alarm_every_day)
    WEEKDAYS -> stringResource(R.string.alarm_weekdays)
    else -> (0 until 7).filter { mask and (1 shl it) != 0 }.joinToString(" ") { DayOfWeek.of(it + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

/** Alarms stored on the ring (silent vibration), plus a vibration test. */
@Composable
fun AlarmsCard(graph: Graph) {
    val alarms by graph.dao.observeAlarms().collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<AlarmEntity?>(null) }
    editing?.let { current ->
        AlarmEditor(
            current,
            onSave = { graph.saveAlarm(it); editing = null },
            onDelete = if (current.id != 0L) ({ graph.deleteAlarm(current.id); editing = null }) else null,
            onDismiss = { editing = null },
        )
    }
    val status by graph.status.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(status.connected) { if (status.connected) graph.readRingAlarms() }
    TCard {
        Label(stringResource(R.string.alarms_title))
        status.ringAlarmCount?.let { T(stringResource(R.string.ring_alarm_count, it), size = 10.sp, color = Term.dim) }
        alarms.forEach { a ->
            Row(Modifier.fillMaxWidth().clickable { editing = a }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    T("%02d:%02d".format(a.hour, a.minute), size = 20.sp, weight = FontWeight.SemiBold)
                    T(daysSummary(a.weekdays), size = 11.sp, color = Term.dim)
                }
                TSwitch(a.enabled, { graph.saveAlarm(a.copy(enabled = it)) })
            }
        }
        TButton(stringResource(R.string.btn_add_alarm), { editing = newAlarmDraft() }, Modifier.fillMaxWidth(), BtnKind.GHOST)
        T(stringResource(R.string.alarm_hint), size = 10.sp, color = Term.faint)
        TButton(stringResource(R.string.btn_vibrate_test), { graph.vibrateTest() }, Modifier.fillMaxWidth(), BtnKind.GHOST)
        T(stringResource(R.string.vibrate_hint), size = 10.sp, color = Term.faint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmEditor(alarm: AlarmEntity, onSave: (AlarmEntity) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var hour by remember(alarm.id) { mutableStateOf(alarm.hour) }
    var minute by remember(alarm.id) { mutableStateOf(alarm.minute) }
    var days by remember(alarm.id) { mutableStateOf(alarm.weekdays) }
    Dialog(onDismissRequest = onDismiss) {
        TCard(hot = true) {
            T(stringResource(R.string.alarm_edit_title), size = 15.sp, weight = FontWeight.Bold)
            Stepper(stringResource(R.string.alarm_hour), "%02d".format(hour), { hour = (hour + 23) % 24 }, { hour = (hour + 1) % 24 })
            Stepper(stringResource(R.string.alarm_minute), "%02d".format(minute), { minute = (minute + 55) % 60 }, { minute = (minute + 5) % 60 })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in 0 until 7) {
                    TChip(DayOfWeek.of(d + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault()), days and (1 shl d) != 0) { days = days xor (1 shl d) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TButton(stringResource(R.string.btn_cancel), onDismiss, Modifier.weight(1f), BtnKind.GHOST)
                TButton(stringResource(R.string.btn_save), { onSave(alarm.copy(hour = hour, minute = minute, weekdays = days)) }, Modifier.weight(1f))
            }
            if (onDelete != null) TButton(stringResource(R.string.btn_delete_alarm), onDelete, Modifier.fillMaxWidth(), BtnKind.CRIT)
        }
    }
}

/** A new alarm starts five minutes from now (rounded up), once: quick to adjust and easy to try. */
private fun newAlarmDraft(): AlarmEntity {
    val now = java.time.LocalTime.now().plusMinutes(5)
    val minutes = (now.hour * 60 + now.minute + 4) / 5 * 5 % (24 * 60)
    return AlarmEntity(hour = minutes / 60, minute = minutes % 60, weekdays = 0)
}
