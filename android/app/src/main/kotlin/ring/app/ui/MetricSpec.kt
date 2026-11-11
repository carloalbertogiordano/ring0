package ring.app.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import ring.app.R
import ring.app.data.SampleType
import ring.app.data.Thresholds

/** How one metric is charted: used by the History screen and by the detail screen opened from a Home tile. */
class MetricSpec(
    @StringRes val title: Int,
    val type: String,
    val unit: String,
    val color: Color,
    val minSpan: Double,
    val decimals: Int,
    @StringRes val info: Int?,
    val threshold: (Thresholds) -> Double?,
)

object Metrics {
    val HEART = MetricSpec(R.string.chart_hr, SampleType.HR, " bpm", Term.accent, 20.0, 0, null) { it.hrHigh.toDouble() }
    val TEMPERATURE = MetricSpec(R.string.chart_temp, SampleType.TEMP, " °C", Color(0xFFFF9D3B), 1.0, 1, R.string.info_temp) { it.tempHigh }
    val STRESS = MetricSpec(R.string.chart_stress, SampleType.STRESS, "", Term.rem, 20.0, 0, R.string.info_stress) { it.stressHigh.toDouble() }
    val HRV = MetricSpec(R.string.chart_hrv_ring, SampleType.HRV, " ms", Term.info, 20.0, 0, R.string.info_hrv) { null }
    val HRV_LIVE = MetricSpec(R.string.chart_hrv_live, SampleType.HRV_LIVE, " ms", Term.rem, 20.0, 0, R.string.info_hrv) { null }
    val BREATHING = MetricSpec(R.string.chart_resp, SampleType.RESP, " /min", Term.info, 4.0, 0, R.string.info_resp) { null }
    val SPO2 = MetricSpec(R.string.chart_spo2, SampleType.SPO2, " %", Term.accent, 4.0, 0, R.string.info_spo2) { it.spo2Low.toDouble() }
}
