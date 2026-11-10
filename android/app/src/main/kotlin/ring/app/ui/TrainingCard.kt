package ring.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ring.app.Graph
import ring.app.R
import ring.app.insights.TrainingReport
import ring.domain.HrZone
import ring.domain.LoadLevel
import kotlin.math.roundToInt

private val ZONE_COLORS = mapOf(
    HrZone.Z1 to Term.accentDim.copy(alpha = 0.5f),
    HrZone.Z2 to Term.accentDim,
    HrZone.Z3 to Term.accent,
    HrZone.Z4 to Term.warn,
    HrZone.Z5 to Term.crit,
)
private val BAR_ZONES = HrZone.entries.filter { it != HrZone.REST }

/** Today's zones, load and calories, and the recent load trend. All estimates from heart rate and the profile. */
@Composable
fun TrainingCard(graph: Graph) {
    val status by graph.status.collectAsStateWithLifecycle()
    val profile by graph.settings.profile.collectAsStateWithLifecycle()
    LaunchedEffect(profile) { graph.refreshTraining() }
    val report = status.training
    TCard {
        Label(stringResource(R.string.training_title))
        when {
            profile == null -> T(stringResource(R.string.training_need_profile), size = 12.sp, color = Term.dim)
            report == null -> T(stringResource(R.string.training_need_data), size = 12.sp, color = Term.dim)
            else -> TrainingBody(report)
        }
        T(stringResource(R.string.training_hint), size = 10.sp, color = Term.faint)
    }
}

@Composable
private fun TrainingBody(report: TrainingReport) {
    val today = report.today
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Num("${today.trimp.roundToInt()}", stringResource(R.string.training_trimp_unit), 32.sp, Term.accent)
        T(stringResource(R.string.training_load_today), size = 11.sp, color = Term.dim)
    }
    ZoneBar(today.zoneMinutes)
    val zoneLegend = listOf(stringResource(R.string.zone_rest_min, today.zoneMinutes.getValue(HrZone.REST))) +
        BAR_ZONES.mapIndexedNotNull { i, z ->
            today.zoneMinutes.getValue(z).takeIf { it > 0 }?.let { stringResource(R.string.zone_min, i + 1, it) }
        }
    T(zoneLegend.joinToString("  ·  "), size = 10.sp, color = Term.dim)
    val kcalActive = today.activeKcal.roundToInt()
    KeyValue(stringResource(R.string.training_worn), "${durationText(report.wornSeconds)} / ${durationText(report.elapsedSeconds)}")
    KeyValue(
        stringResource(R.string.training_kcal_total),
        stringResource(R.string.training_kcal_value, (report.basalSoFarKcal + today.activeKcal).roundToInt()),
    )
    KeyValue(stringResource(R.string.training_kcal_active), stringResource(R.string.training_kcal_value, kcalActive))
    val load = report.load
    if (load == null) {
        T(stringResource(R.string.training_trend_unknown), size = 11.sp, color = Term.dim)
    } else {
        val (label, color) = when (load.level) {
            LoadLevel.LOW -> R.string.training_trend_low to Term.warn
            LoadLevel.USUAL -> R.string.training_trend_usual to Term.accent
            LoadLevel.HIGH -> R.string.training_trend_high to Term.warn
        }
        KeyValue(stringResource(R.string.training_trend), stringResource(R.string.training_trend_value, stringResource(label), load.ratio), color)
    }
}

/** Stacked bar of the minutes spent in zones 1 to 5; empty when there is no effort yet. */
@Composable
private fun ZoneBar(minutes: Map<HrZone, Int>) {
    val total = BAR_ZONES.sumOf { minutes.getValue(it) }
    Row(Modifier.fillMaxWidth().height(8.dp).background(Term.bg)) {
        if (total > 0) {
            BAR_ZONES.forEach { zone ->
                val m = minutes.getValue(zone)
                if (m > 0) Box(Modifier.weight(m.toFloat()).height(8.dp).background(ZONE_COLORS.getValue(zone) as Color))
            }
        }
    }
}

private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60

@Composable
private fun durationText(seconds: Long): String {
    val minutes = (seconds / SECONDS_PER_MINUTE).toInt()
    return stringResource(R.string.duration_h_m, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
}
