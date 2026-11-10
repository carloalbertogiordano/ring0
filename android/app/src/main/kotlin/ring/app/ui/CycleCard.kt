package ring.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ring.app.Graph
import ring.app.R
import ring.domain.CycleEstimate
import ring.domain.CyclePhase

/** Optional, experimental: has the post-ovulation rise of nightly skin temperature happened? Shown only when switched on. */
@Composable
fun CycleCard(graph: Graph) {
    val status by graph.status.collectAsStateWithLifecycle()
    val estimate = status.insights.cycle ?: return
    TCard {
        LabelWithInfo(stringResource(R.string.cycle_title), R.string.info_cycle)
        CycleBody(estimate)
        T(stringResource(R.string.cycle_hint), size = 10.sp, color = Term.faint)
    }
}

@Composable
private fun CycleBody(estimate: CycleEstimate) {
    when (estimate.phase) {
        CyclePhase.NOT_ENOUGH_DATA -> T(stringResource(R.string.cycle_need_data, estimate.nightsUsed), size = 12.sp, color = Term.dim)
        CyclePhase.NO_RISE_YET -> T(stringResource(R.string.cycle_no_rise), size = 12.sp)
        CyclePhase.AFTER_RISE -> T(stringResource(R.string.cycle_after_rise, estimate.nightsSinceRise ?: 0), size = 12.sp, color = Term.accent)
    }
}
