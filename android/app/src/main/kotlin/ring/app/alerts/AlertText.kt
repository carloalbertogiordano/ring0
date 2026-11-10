package ring.app.alerts

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import ring.app.R
import ring.app.data.AlertEntity
import ring.domain.IllnessReason

/** Alert kinds stored in the database. */
object AlertKind {
    const val HR_HIGH = "hr_high"
    const val HR_LOW = "hr_low"
    const val TEMP_HIGH = "temp_high"
    const val STRESS_HIGH = "stress_high"
    const val SPO2_LOW = "spo2_low"
    const val BATTERY_LOW = "battery_low"
    const val STALE = "stale"
    const val ILLNESS = "illness"
    const val ILLNESS_WATCH = "illness_watch"
}

/** Localized text of an alert, in the language the system currently uses. */
fun alertText(context: Context, alert: AlertEntity): String = alertText(context, alert.kind, alert.value, alert.threshold, alert.detail, alert.message)

fun alertText(context: Context, kind: String, value: Double, threshold: Double, detail: String, legacy: String = ""): String = when (kind) {
    AlertKind.HR_HIGH -> context.getString(R.string.alert_hr_high, value.toInt(), threshold.toInt())
    AlertKind.HR_LOW -> context.getString(R.string.alert_hr_low, value.toInt(), threshold.toInt())
    AlertKind.TEMP_HIGH -> context.getString(R.string.alert_temp_high, value, threshold)
    AlertKind.STRESS_HIGH -> context.getString(R.string.alert_stress_high, value.toInt(), threshold.toInt())
    AlertKind.SPO2_LOW -> context.getString(R.string.alert_spo2_low, value.toInt(), threshold.toInt())
    AlertKind.BATTERY_LOW -> context.getString(R.string.alert_battery_low, value.toInt())
    AlertKind.STALE -> context.getString(R.string.alert_stale, value.toInt())
    AlertKind.ILLNESS -> context.getString(R.string.alert_illness, reasons(context, detail))
    AlertKind.ILLNESS_WATCH -> context.getString(R.string.alert_illness_watch, reasons(context, detail))
    else -> legacy
}

@Composable
fun alertText(alert: AlertEntity): String = alertText(LocalContext.current, alert)

private fun reasons(context: Context, detail: String): String = detail.split(",").filter { it.isNotBlank() }.joinToString(", ") {
    when (IllnessReason.valueOf(it)) {
        IllnessReason.SKIN_TEMP_HIGH -> context.getString(R.string.reason_skin_temp)
        IllnessReason.RESTING_HR_HIGH -> context.getString(R.string.reason_resting_hr)
        IllnessReason.HRV_LOW -> context.getString(R.string.reason_hrv_low)
    }
}
