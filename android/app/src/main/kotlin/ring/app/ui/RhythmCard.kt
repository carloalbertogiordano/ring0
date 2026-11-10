package ring.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ring.app.Graph
import ring.app.R
import ring.app.sync.RhythmOutcome
import ring.app.sync.RhythmRunner
import ring.domain.RhythmAdvice
import ring.domain.RhythmLevel

/** On-demand pulse regularity screening: sit still for two minutes. Experimental and not a diagnosis. */
@Composable
fun RhythmCard(graph: Graph) {
    val status by graph.status.collectAsStateWithLifecycle()
    TCard {
        Label(stringResource(R.string.rhythm_title))
        T(stringResource(R.string.rhythm_intro), size = 11.sp, color = Term.dim)
        if (status.rhythmMeasuring) {
            T(stringResource(R.string.rhythm_measuring, status.rhythmSession, RhythmRunner.TARGET_SEGMENTS), size = 12.sp, color = Term.accent)
        }
        status.rhythm?.let { RhythmResultView(it) }
        TButton(
            stringResource(R.string.rhythm_start),
            { graph.rhythmCheck() },
            Modifier.fillMaxWidth(),
            BtnKind.GHOST,
            enabled = status.connected && !status.rhythmMeasuring && !status.measuring,
        )
        T(stringResource(R.string.rhythm_disclaimer), size = 10.sp, color = Term.faint)
    }
}

@Composable
private fun RhythmResultView(outcome: RhythmOutcome) {
    val (text, color) = when (outcome.result.level) {
        RhythmLevel.REGULAR -> R.string.rhythm_regular to Term.accent
        RhythmLevel.IRREGULAR -> R.string.rhythm_irregular to Term.warn
        RhythmLevel.INCONCLUSIVE -> R.string.rhythm_inconclusive to Term.dim
    }
    T(stringResource(text), size = 13.sp, color = color)
    when (outcome.advice) {
        RhythmAdvice.RECHECK -> T(stringResource(R.string.rhythm_advice_recheck), size = 11.sp, color = Term.warn)
        RhythmAdvice.SEE_DOCTOR -> T(stringResource(R.string.rhythm_advice_doctor), size = 11.sp, color = Term.crit)
        RhythmAdvice.NONE -> Unit
    }
}
