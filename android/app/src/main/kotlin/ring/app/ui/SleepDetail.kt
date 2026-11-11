package ring.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ring.app.Graph
import ring.app.R
import ring.app.data.SleepEntity
import ring.domain.Hypnogram
import ring.domain.SleepStageCheck
import ring.protocol.commands.SleepStageType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private const val NIGHTS_BACK_DAYS = 14L
private const val SECONDS_PER_DAY = 86_400L
private const val DEFAULT_AGE = 30
private const val PERCENT = 100
private val NIGHT_LABEL = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())

/** Stage rows from top to bottom, as on a clinical hypnogram: awake on top, deep sleep at the bottom. */
private val STAGE_ROWS = listOf(SleepStageType.AWAKE, SleepStageType.REM, SleepStageType.LIGHT, SleepStageType.DEEP)

private fun stageColor(type: SleepStageType): Color = when (type) {
    SleepStageType.AWAKE -> Term.crit
    SleepStageType.REM -> Term.rem
    SleepStageType.LIGHT -> Term.accent
    else -> Term.info
}

/** Opened from the Home sleep tile: the stages of a night over time, and the last two weeks to pick from. */
@Composable
fun SleepDetailScreen(graph: Graph, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val from = remember { Instant.now().epochSecond - NIGHTS_BACK_DAYS * SECONDS_PER_DAY }
    val nights by remember { graph.dao.observeSleep(from) }.collectAsStateWithLifecycle(emptyList())
    var selected by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        T("← " + stringResource(R.string.tile_sleep), Modifier.clickable(onClick = onBack).padding(vertical = 6.dp), size = 15.sp, weight = FontWeight.Bold)
        if (nights.isEmpty()) {
            T(stringResource(R.string.no_night), size = 12.sp, color = Term.dim)
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                nights.forEachIndexed { i, n -> TChip(NIGHT_LABEL.format(Instant.ofEpochSecond(n.end)), selected == i) { selected = i } }
            }
            val profile by graph.settings.profile.collectAsStateWithLifecycle()
            NightCard(nights[selected.coerceIn(0, nights.lastIndex)], profile?.ageYears ?: DEFAULT_AGE)
        }
    }
}

@Composable
private fun NightCard(night: SleepEntity, ageYears: Int) = TCard(hot = true) {
    val total = ((night.end - night.start) / 60).toInt()
    T("${dayTime(night.start)} → ${dayTime(night.end)}  (${total / 60}h ${total % 60}m)", size = 12.sp)
    val stages = Hypnogram.decode(night.stages)
    if (stages.isEmpty()) {
        T(stringResource(R.string.sleep_no_stages), size = 11.sp, color = Term.dim)
    } else {
        HypnogramChart(night.start, Hypnogram.timeline(night.start, stages))
    }
    T(stringResource(R.string.sleep_stages, night.deepMin, night.lightMin, night.remMin, night.awakeMin), size = 10.sp, color = Term.dim)
    StageAdequacy(SleepStageCheck.assess(night.lightMin, night.deepMin, night.remMin, ageYears))
}

/** Is each stage in the typical range? Under the hypnogram, so the chart and the verdict are read together. */
@Composable
private fun StageAdequacy(assessments: List<SleepStageCheck.Assessment>?) {
    Label(stringResource(R.string.sleep_enough_title))
    if (assessments == null) {
        T(stringResource(R.string.sleep_enough_short), size = 11.sp, color = Term.dim)
        return
    }
    for (a in assessments) {
        val (word, color) = when (a.verdict) {
            SleepStageCheck.Verdict.LOW -> R.string.sleep_verdict_low to Term.warn
            SleepStageCheck.Verdict.OK -> R.string.sleep_verdict_ok to Term.accent
            SleepStageCheck.Verdict.HIGH -> R.string.sleep_verdict_high to Term.warn
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T(stringResource(stageName(a.stage)), size = 12.sp)
            T(
                stringResource(
                    R.string.sleep_enough_row,
                    a.minutes,
                    (a.share * PERCENT).roundToInt(),
                    a.typicalPercent.start.roundToInt(),
                    a.typicalPercent.endInclusive.roundToInt(),
                ),
                size = 11.sp,
                color = Term.dim,
            )
            T(stringResource(word), size = 12.sp, color = color, weight = FontWeight.Bold)
        }
    }
    T(stringResource(R.string.sleep_enough_hint), size = 10.sp, color = Term.faint)
}

private fun stageName(stage: SleepStageCheck.Stage) = when (stage) {
    SleepStageCheck.Stage.LIGHT -> R.string.stage_light
    SleepStageCheck.Stage.DEEP -> R.string.stage_deep
    SleepStageCheck.Stage.REM -> R.string.stage_rem
}

@Composable
private fun HypnogramChart(start: Long, blocks: List<ring.domain.StageBlock>) {
    val measurer = rememberTextMeasurer()
    val labels = mapOf(
        SleepStageType.AWAKE to stringResource(R.string.stage_awake),
        SleepStageType.REM to stringResource(R.string.stage_rem),
        SleepStageType.LIGHT to stringResource(R.string.stage_light),
        SleepStageType.DEEP to stringResource(R.string.stage_deep),
    )
    val end = blocks.last().end
    val style = TextStyle(color = Term.dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    Canvas(Modifier.fillMaxWidth().height(HYPNOGRAM_HEIGHT)) {
        val labelWidth = LABEL_WIDTH.toPx()
        val axisHeight = AXIS_HEIGHT.toPx()
        val rowHeight = (size.height - axisHeight) / STAGE_ROWS.size
        val plotWidth = size.width - labelWidth
        val span = (end - start).coerceAtLeast(1).toFloat()
        STAGE_ROWS.forEachIndexed { row, type ->
            val top = row * rowHeight
            drawLine(Term.line, Offset(labelWidth, top + rowHeight), Offset(size.width, top + rowHeight))
            drawText(measurer, labels.getValue(type), Offset(0f, top + rowHeight / 2 - style.fontSize.toPx() / 2), style)
        }
        for (b in blocks) {
            val row = STAGE_ROWS.indexOf(b.type).takeIf { it >= 0 } ?: continue
            val x = labelWidth + (b.start - start) / span * plotWidth
            val width = ((b.end - b.start) / span * plotWidth).coerceAtLeast(1f)
            drawRect(stageColor(b.type).copy(alpha = 0.85f), Offset(x, row * rowHeight + BLOCK_INSET.toPx()), Size(width, rowHeight - 2 * BLOCK_INSET.toPx()))
        }
        for (t in listOf(start, (start + end) / 2, end)) {
            val text = clock(t)
            val x = labelWidth + (t - start) / span * plotWidth
            val w = measurer.measure(text, style).size.width
            drawText(measurer, text, Offset((x - w / 2).coerceIn(labelWidth, size.width - w), size.height - axisHeight + 2.dp.toPx()), style)
        }
    }
}

private val HYPNOGRAM_HEIGHT = 170.dp
private val LABEL_WIDTH = 56.dp
private val AXIS_HEIGHT = 18.dp
private val BLOCK_INSET = 3.dp
