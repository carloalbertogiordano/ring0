package ring.app.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import ring.app.R
import ring.app.alerts.AlertKind
import ring.app.alerts.alertText
import ring.app.data.AlertEntity
import ring.app.data.RingDao
import ring.app.data.SampleType
import ring.app.data.SettingsStore
import ring.app.data.Thresholds
import ring.domain.ContextAlerts
import ring.domain.HrAlert
import ring.domain.TimedValue
import ring.domain.WarningLevel
import ring.app.insights.Insights

/** Checks thresholds on the latest measurements and notifies (with a cooldown per alert kind). */
class AlertEngine(private val context: Context, private val dao: RingDao, private val settings: SettingsStore, private val insights: Insights) {
    init {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    suspend fun evaluate(batteryLevel: Int?, charging: Boolean) {
        if (!settings.alertsEnabled) return
        val t = settings.thresholds.value
        val hr = dao.lastN(SampleType.HR, 2).map { TimedValue(it.ts, it.value) }
        // without step data the context is unknown: a high heart rate needs a margin above the threshold
        when (ContextAlerts.heartRate(hr, t.hrHigh.toDouble(), t.hrLow.toDouble()) { null }) {
            HrAlert.HIGH -> fire(AlertKind.HR_HIGH, hr.maxOf { it.value }, t.hrHigh.toDouble())
            HrAlert.LOW -> fire(AlertKind.HR_LOW, hr.minOf { it.value }, t.hrLow.toDouble())
            null -> Unit
        }
        latest(SampleType.TEMP)?.takeIf { it > t.tempHigh }?.let { fire(AlertKind.TEMP_HIGH, it, t.tempHigh) }
        latest(SampleType.STRESS)?.takeIf { it > t.stressHigh }?.let { fire(AlertKind.STRESS_HIGH, it, t.stressHigh.toDouble()) }
        val now = Instant.now().epochSecond
        val spo2 = dao.range(SampleType.SPO2, now - SPO2_LOOKBACK, now).map { TimedValue(it.ts, it.value) }
        if (ContextAlerts.spo2(spo2, t.spo2Low.toDouble(), now)) fire(AlertKind.SPO2_LOW, spo2.minOf { it.value }, t.spo2Low.toDouble())
        val illness = insights.illness()
        val detail = illness.reasons.joinToString(",") { it.name }
        when (illness.level) {
            WarningLevel.ALERT -> fire(AlertKind.ILLNESS, 0.0, 0.0, detail)
            WarningLevel.WATCH -> fire(AlertKind.ILLNESS_WATCH, 0.0, 0.0, detail)
            WarningLevel.NONE -> Unit
        }
        if (batteryLevel != null && !charging && batteryLevel <= t.batteryLow) fire(AlertKind.BATTERY_LOW, batteryLevel.toDouble(), t.batteryLow.toDouble())
    }

    suspend fun staleSync(hours: Long) = fire(AlertKind.STALE, hours.toDouble(), 0.0)

    private suspend fun latest(type: String): Double? = dao.lastN(type, 1).firstOrNull()?.value

    private suspend fun fire(kind: String, value: Double, threshold: Double, detail: String = "") {
        val now = Instant.now().epochSecond
        if (now - settings.lastAlert(kind) < COOLDOWN_SECONDS) return
        settings.markAlert(kind, now)
        dao.insertAlert(AlertEntity(ts = now, kind = kind, value = value, threshold = threshold, detail = detail))
        notify(kind, alertText(context, kind, value, threshold, detail))
    }

    @SuppressLint("MissingPermission")
    private fun notify(kind: String, message: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ring)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(kind.hashCode(), n)
    }

    private companion object {
        const val CHANNEL = "alerts"
        const val COOLDOWN_SECONDS = 60 * 60L
        const val SPO2_LOOKBACK = 4 * 3600L
    }
}
