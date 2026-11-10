package ring.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ring.app.Graph
import ring.app.R
import ring.app.insights.SleepReport

private const val MINUTES_PER_HOUR = 60
private const val SECONDS_PER_MINUTE = 60
private const val GOOD_SCORE = 75
private const val OK_SCORE = 55

/** Last night's score, sleep debt, regularity, suggested bedtime and possible naps today. */
@Composable
fun SleepInsightsCard(graph: Graph) {
    val status by graph.status.collectAsStateWithLifecycle()
    val report = status.sleep
    TCard {
        Label(stringResource(R.string.sleep_insights_title))
        if (report == null || (report.lastNight == null && report.bedtimeMinute == null)) {
            T(stringResource(R.string.sleep_insights_need_data), size = 12.sp, color = Term.dim)
        } else {
            SleepBody(report)
        }
    }
}

@Composable
private fun SleepBody(report: SleepReport) {
    report.score?.let { score ->
        val color = when {
            score >= GOOD_SCORE -> Term.accent
            score >= OK_SCORE -> Term.warn
            else -> Term.crit
        }
        Num("$score", "/100", 36.sp, color)
    }
    report.lastNight?.let {
        KeyValue(
            stringResource(R.string.sleep_last_night),
            stringResource(
                R.string.duration_h_m,
                it.totalSleepMin / MINUTES_PER_HOUR,
                it.totalSleepMin % MINUTES_PER_HOUR,
            ),
        )
    }
    KeyValue(
        stringResource(R.string.sleep_target),
        stringResource(
            R.string.duration_h_m,
            report.targetMin / MINUTES_PER_HOUR,
            report.targetMin % MINUTES_PER_HOUR,
        ),
    )
    KeyValue(
        stringResource(R.string.sleep_debt_7d),
        stringResource(R.string.duration_h_m, report.debtMin / MINUTES_PER_HOUR, report.debtMin % MINUTES_PER_HOUR),
    )
    report.regularity?.let { KeyValue(stringResource(R.string.sleep_regularity), "$it/100") }
    report.bedtimeMinute?.let { KeyValue(stringResource(R.string.sleep_bedtime), "%02d:%02d".format(it / MINUTES_PER_HOUR, it % MINUTES_PER_HOUR)) }
    if (report.naps.isNotEmpty()) {
        val minutes = report.naps.sumOf { (it.end - it.start) / SECONDS_PER_MINUTE }
        KeyValue(stringResource(R.string.sleep_naps), stringResource(R.string.sleep_naps_value, report.naps.size, minutes))
    }
    T(stringResource(R.string.sleep_insights_hint), size = 10.sp, color = Term.faint)
}
