package ring.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ring.app.R
import ring.app.data.Sample
import ring.domain.Series
import ring.domain.TimedValue
import ring.domain.WearGap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val GRID_LINES = 3
private const val TWO_DAYS = 2 * 86_400L
private const val DAY_MINUS = 20 * 3600L

/**
 * Compact trend (area + line + end point) for the Home tiles. The X axis is time ([from], [to] default to the first and last
 * point), and stretches without data (ring not worn) stay blank with a faint band.
 */
@Composable
fun Sparkline(points: List<TimedValue>, color: Color, modifier: Modifier = Modifier, from: Long? = null, to: Long? = null) {
    Canvas(modifier.fillMaxWidth().height(26.dp)) {
        if (points.size < 2) return@Canvas
        val start = from ?: points.first().ts
        val end = to ?: points.last().ts
        val min = points.minOf { it.value }
        val span = (points.maxOf { it.value } - min).coerceAtLeast(1e-6)
        val h = size.height - 4.dp.toPx()

        fun x(ts: Long) = size.width * ((ts - start).toFloat() / (end - start).coerceAtLeast(1))

        fun y(v: Double) = (h - h * ((v - min) / span)).toFloat() + 2.dp.toPx()
        val segments = Series.segments(points)
        drawHoles(Series.holes(segments), ::x, size.height)
        segments.forEach { segment ->
            if (segment.size < 2) {
                drawCircle(color, 1.6.dp.toPx(), Offset(x(segment.first().ts), y(segment.first().value)))
                return@forEach
            }
            val line = Path().apply { segment.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.ts), y(p.value)) else lineTo(x(p.ts), y(p.value)) } }
            val area = Path().apply {
                addPath(line)
                lineTo(x(segment.last().ts), size.height)
                lineTo(x(segment.first().ts), size.height)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), Color.Transparent)))
            drawPath(line, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawCircle(color, 2.4.dp.toPx(), Offset(x(points.last().ts), y(points.last().value)))
    }
}

/** Faint bands over the stretches where there is no data, so a gap reads as "ring not worn" rather than as a flat line. */
private fun DrawScope.drawHoles(holes: List<WearGap>, x: (Long) -> Float, height: Float) {
    holes.forEach { hole ->
        val left = x(hole.start)
        drawRect(Term.faint.copy(alpha = HOLE_ALPHA), Offset(left, 0f), Size((x(hole.end) - left).coerceAtLeast(1f), height))
    }
}

private const val HOLE_ALPHA = 0.18f

/** Chart with grid, labels, dashed threshold and highlighted last point (X axis in epoch seconds). */
@Composable
fun TermChart(
    samples: List<Sample>,
    from: Long,
    to: Long,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    minSpan: Double = 1.0,
    threshold: Double? = null,
    format: (Double) -> String = { "%.0f".format(it) },
) {
    val measurer = rememberTextMeasurer()
    val noDataText = stringResource(R.string.no_data_period)
    val label = TextStyle(color = Term.dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    val pattern = if (to - from > TWO_DAYS) {
        "d MMM"
    } else if (to - from >= DAY_MINUS) {
        "d/M HH:mm"
    } else {
        "HH:mm"
    }
    val fmt = DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault())
    Canvas(modifier.fillMaxWidth().height(height)) {
        val left = 34.dp.toPx()
        val bottom = 16.dp.toPx()
        val w = size.width - left - 4.dp.toPx()
        val h = size.height - bottom - 4.dp.toPx()
        if (samples.isEmpty()) {
            drawText(measurer, noDataText, Offset(left + 8.dp.toPx(), h / 2), label)
            return@Canvas
        }
        val values = samples.map { it.value } + listOfNotNull(threshold)
        val mid = (values.max() + values.min()) / 2
        val span = maxOf(values.max() - values.min(), minSpan) * 1.15
        val yMin = mid - span / 2

        fun x(ts: Long) = left + w * ((ts - from).toFloat() / (to - from).coerceAtLeast(1))

        fun y(v: Double) = 4.dp.toPx() + (h - h * ((v - yMin) / span)).toFloat()
        for (i in 0..GRID_LINES) {
            val value = yMin + span * (GRID_LINES - i) / GRID_LINES
            val gy = 4.dp.toPx() + h * i / GRID_LINES
            drawLine(Term.line, Offset(left, gy), Offset(size.width, gy), 1f)
            drawText(measurer, format(value), Offset(0f, gy - 6.dp.toPx()), label)
        }
        for (i in 0..2) {
            val ts = from + (to - from) * i / 2
            val text = fmt.format(Instant.ofEpochSecond(ts))
            val width = measurer.measure(text, label).size.width
            val px = x(ts) - if (i == 0) {
                0f
            } else if (i == 2) {
                width.toFloat()
            } else {
                width / 2f
            }
            drawText(measurer, text, Offset(px, size.height - 13.dp.toPx()), label)
        }
        threshold?.let {
            drawLine(Term.warn, Offset(left, y(it)), Offset(size.width, y(it)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
        val segments = Series.segments(samples.map { TimedValue(it.ts, it.value) })
        val top = 4.dp.toPx()
        val holes = Series.holes(segments)
        holes.forEach { hole ->
            val left = x(hole.start)
            drawRect(Term.faint.copy(alpha = HOLE_ALPHA), Offset(left, top), Size((x(hole.end) - left).coerceAtLeast(1f), h))
        }
        segments.forEach { segment ->
            if (segment.size < 2) {
                drawCircle(color, 2.dp.toPx(), Offset(x(segment.first().ts), y(segment.first().value)))
                return@forEach
            }
            val line = Path().apply { segment.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.ts), y(p.value)) else lineTo(x(p.ts), y(p.value)) } }
            val area = Path().apply {
                addPath(line)
                lineTo(x(segment.last().ts), top + h)
                lineTo(x(segment.first().ts), top + h)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)))
            drawPath(line, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawCircle(color, 3.2.dp.toPx(), Offset(x(samples.last().ts), y(samples.last().value)))
    }
}

/** ECG trace that draws itself in a loop (decorative, shows that a measurement is running). */
@Composable
fun Ecg(modifier: Modifier = Modifier, active: Boolean = true) {
    val t = rememberInfiniteTransition(label = "ecg")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Canvas(modifier.fillMaxWidth().height(42.dp)) {
        val pts = listOf(
            0f to 24f,
            70f to 24f,
            78f to 8f,
            86f to 38f,
            94f to 14f,
            100f to 24f,
            190f to 24f,
            198f to 8f,
            206f to 38f,
            214f to 14f,
            220f to 24f,
            300f to 24f,
        )
        val path = Path().apply {
            pts.forEachIndexed { i, (px, py) ->
                val x = size.width * px / 300f
                val y = size.height * py / 42f
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        val len = size.width * 1.4f
        val effect = if (active) PathEffect.dashPathEffect(floatArrayOf(len, len), len * (1 - phase)) else null
        drawPath(
            path,
            if (active) Term.accent else Term.faint,
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect),
        )
    }
}

/** Animated radar for the ring search. */
@Composable
fun Radar(size: Dp = 150.dp, found: Boolean = false, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "radar")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "a")
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.width / 2
        drawCircle(Brush.radialGradient(listOf(Term.accent.copy(alpha = 0.12f), Color.Transparent), c, r), r, c)
        listOf(1f, 0.58f, 0.16f).forEach { drawCircle(Term.line2, r * it, c, style = Stroke(1.dp.toPx())) }
        rotate(angle, c) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, Term.accent.copy(alpha = 0.5f)), c),
                startAngle = -70f,
                sweepAngle = 70f,
                useCenter = true,
                topLeft = Offset.Zero,
                size = this.size,
            )
        }
        if (found) drawCircle(Term.accent, 4.dp.toPx(), Offset(c.x + r * 0.35f, c.y - r * 0.4f))
    }
}
