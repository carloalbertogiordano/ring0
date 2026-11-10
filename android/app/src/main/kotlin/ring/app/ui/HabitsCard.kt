package ring.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ring.app.Graph
import ring.app.R
import ring.app.insights.HabitMetric
import ring.app.insights.HabitRow

/** How nights after tagged incidents (caffeine, alcohol, exercise, illness...) differ from the others. Correlation only. */
@Composable
fun HabitsCard(graph: Graph) {
    val status by graph.status.collectAsStateWithLifecycle()
    val rows = status.insights.habits
    TCard {
        Label(stringResource(R.string.habits_title))
        if (rows.isEmpty()) {
            T(stringResource(R.string.habits_need_data), size = 12.sp, color = Term.dim)
        } else {
            rows.take(MAX_ROWS).forEach { HabitLine(it) }
        }
        T(stringResource(R.string.habits_hint), size = 10.sp, color = Term.faint)
    }
}

private const val MAX_ROWS = 6

@Composable
private fun HabitLine(row: HabitRow) {
    val metric = when (row.metric) {
        HabitMetric.HRV -> R.string.habit_metric_hrv
        HabitMetric.RESTING_HR -> R.string.habit_metric_rhr
        HabitMetric.SLEEP -> R.string.habit_metric_sleep
    }
    KeyValue(
        stringResource(categoryRes(row.category)) + " · " + stringResource(metric),
        stringResource(R.string.habit_value, row.effect.percent, row.effect.nWith),
    )
}
