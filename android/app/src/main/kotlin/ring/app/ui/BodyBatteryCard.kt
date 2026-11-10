package ring.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ring.app.Graph
import ring.app.R
import kotlin.math.roundToInt

private const val GOOD_LEVEL = 60
private const val LOW_LEVEL = 30

/** Energy reserve estimate from sleep, stress and effort over the last day and a half. */
@Composable
fun BodyBatteryCard(graph: Graph) {
    val status by graph.status.collectAsStateWithLifecycle()
    val report = status.insights.bodyBattery
    TCard(hot = report != null) {
        LabelWithInfo(stringResource(R.string.energy_title), R.string.info_energy)
        if (report == null) {
            T(stringResource(R.string.energy_need_data), size = 12.sp, color = Term.dim)
        } else if (!report.anchored) {
            T(stringResource(R.string.energy_no_sleep), size = 12.sp, color = Term.warn)
        } else {
            val level = report.current.roundToInt()
            val color = when {
                level >= GOOD_LEVEL -> Term.accent
                level >= LOW_LEVEL -> Term.warn
                else -> Term.crit
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Num("$level", "/100", 40.sp, color)
            }
            Sparkline(report.series, color, Modifier.fillMaxWidth().height(36.dp))
            T(stringResource(R.string.energy_hint), size = 10.sp, color = Term.faint)
        }
    }
}
