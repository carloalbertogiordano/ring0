package ring.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ring.app.R

/** A card label with a small "?" that opens a short plain-language explanation of the metric. */
@Composable
fun LabelWithInfo(
    title: String,
    @StringRes info: Int,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Label(title)
        T(
            "?",
            Modifier.border(BorderStroke(1.dp, Term.line2)).clickable { open = !open }.padding(horizontal = 8.dp, vertical = 2.dp),
            size = 11.sp,
            color = if (open) Term.accent else Term.dim,
        )
    }
    if (open) T(stringResource(info), size = 11.sp, color = Term.dim)
}

private val GLOSSARY = listOf(
    R.string.tile_hrv to R.string.info_hrv,
    R.string.tile_stress to R.string.info_stress,
    R.string.tile_spo2 to R.string.info_spo2,
    R.string.chart_temp to R.string.info_temp,
    R.string.chart_resp to R.string.info_resp,
    R.string.recovery_title to R.string.info_recovery,
    R.string.energy_title to R.string.info_energy,
    R.string.sleep_insights_title to R.string.info_sleep,
    R.string.training_title to R.string.info_training,
    R.string.rhythm_title to R.string.info_rhythm,
    R.string.habits_title to R.string.info_habits,
    R.string.cycle_title to R.string.info_cycle,
)

/** Every explanation in one place (Settings). */
@Composable
fun GlossaryCard() {
    var open by rememberSaveable { mutableStateOf(false) }
    TCard {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(stringResource(R.string.glossary_title))
            T(if (open) "−" else "+", size = 14.sp, color = Term.accent)
        }
        if (open) {
            GLOSSARY.forEach { (title, info) ->
                T(stringResource(title), size = 12.sp, color = Term.accent)
                T(stringResource(info), size = 11.sp, color = Term.dim)
            }
        }
    }
}
