package ring.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ring.app.data.Sample

private const val GRID_LINES = 3
private const val POINT_DOT_LIMIT = 80

/** Grafico a linea di una serie temporale (asse X in epoch secondi). */
@Composable
fun LineChart(
    samples: List<Sample>,
    from: Long,
    to: Long,
    color: Color,
    modifier: Modifier = Modifier,
    minSpan: Double = 1.0,
    format: (Double) -> String = { "%.0f".format(it) },
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val timeFormat = DateTimeFormatter.ofPattern(if (to - from > TWO_DAYS) "d/M" else "HH:mm").withZone(ZoneId.systemDefault())
    Canvas(modifier = modifier.fillMaxWidth().height(150.dp)) {
        val left = 44.dp.toPx()
        val bottom = 18.dp.toPx()
        val w = size.width - left
        val h = size.height - bottom
        val style = TextStyle(color = labelColor, fontSize = 10.sp)
        if (samples.isEmpty()) {
            drawText(measurer, "nessun dato", Offset(left + w / 2 - 28.dp.toPx(), h / 2), style)
            return@Canvas
        }
        val rawMin = samples.minOf { it.value }
        val rawMax = samples.maxOf { it.value }
        val span = maxOf(rawMax - rawMin, minSpan)
        val mid = (rawMax + rawMin) / 2
        val yMin = mid - span * 0.6
        val yMax = mid + span * 0.6
        fun x(ts: Long) = left + w * ((ts - from).toFloat() / (to - from).coerceAtLeast(1))
        fun y(v: Double) = (h - h * ((v - yMin) / (yMax - yMin))).toFloat()
        for (i in 0..GRID_LINES) {
            val value = yMin + (yMax - yMin) * i / GRID_LINES
            val gy = y(value)
            drawLine(gridColor, Offset(left, gy), Offset(size.width, gy), strokeWidth = 1f)
            drawText(measurer, format(value), Offset(0f, gy - 7.dp.toPx()), style)
        }
        for (i in 0..2) {
            val ts = from + (to - from) * i / 2
            drawText(measurer, timeFormat.format(Instant.ofEpochSecond(ts)), Offset(x(ts) - 14.dp.toPx() * i / 2 - 8.dp.toPx(), h + 3.dp.toPx()), style)
        }
        val path = Path()
        samples.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(s.ts), y(s.value)) else path.lineTo(x(s.ts), y(s.value)) }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        if (samples.size <= POINT_DOT_LIMIT) samples.forEach { drawCircle(color, 3.dp.toPx(), Offset(x(it.ts), y(it.value))) }
    }
}

private const val TWO_DAYS = 2 * 86_400L

/** Card con titolo, riepilogo (ultimo/min/media/max) e grafico. */
@Composable
fun MetricCard(
    title: String,
    unit: String,
    samples: List<Sample>,
    from: Long,
    to: Long,
    color: Color,
    minSpan: Double,
    decimals: Int = 0,
) {
    val fmt = { v: Double -> "%.${decimals}f".format(v) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (samples.isNotEmpty()) {
                Row {
                    Text(
                        "ultimo ${fmt(samples.last().value)}$unit  ·  min ${fmt(samples.minOf { it.value })}  ·  " +
                            "media ${fmt(samples.map { it.value }.average())}  ·  max ${fmt(samples.maxOf { it.value })}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            LineChart(samples, from, to, color, Modifier.padding(top = 8.dp), minSpan, fmt)
        }
    }
}
