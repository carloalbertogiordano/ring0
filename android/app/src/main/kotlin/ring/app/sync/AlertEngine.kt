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

/** Valuta le soglie sulle misure piu' recenti e notifica (con pausa per tipo di alert). */
class AlertEngine(private val context: Context, private val dao: RingDao, private val settings: SettingsStore, private val insights: Insights) {
    init {
        val channel = NotificationChannel(CHANNEL, "Alert salute", NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    suspend fun evaluate(batteryLevel: Int?, charging: Boolean) {
        if (!settings.alertsEnabled) return
        val t = settings.thresholds.value
        val hr = dao.lastN(SampleType.HR, 2).map { TimedValue(it.ts, it.value) }
        // senza dati sui passi il contesto e' ignoto: la FC alta richiede un margine sopra soglia
        when (ContextAlerts.heartRate(hr, t.hrHigh.toDouble(), t.hrLow.toDouble()) { null }) {
            HrAlert.HIGH -> fire("hr_high", "Frequenza cardiaca alta: ${hr.maxOf { it.value }.toInt()} bpm (soglia ${t.hrHigh})")
            HrAlert.LOW -> fire("hr_low", "Frequenza cardiaca bassa: ${hr.minOf { it.value }.toInt()} bpm (soglia ${t.hrLow})")
            null -> Unit
        }
        latest(SampleType.TEMP)?.takeIf { it > t.tempHigh }?.let { fire("temp_high", "Temperatura alta: %.1f °C (soglia %.1f)".format(it, t.tempHigh)) }
        latest(SampleType.STRESS)?.takeIf { it > t.stressHigh }?.let { fire("stress_high", "Stress alto: ${it.toInt()} (soglia ${t.stressHigh})") }
        val now = Instant.now().epochSecond
        val spo2 = dao.range(SampleType.SPO2, now - SPO2_LOOKBACK, now).map { TimedValue(it.ts, it.value) }
        if (ContextAlerts.spo2(spo2, t.spo2Low.toDouble(), now)) fire("spo2_low", "SpO2 bassa: ${spo2.minOf { it.value }.toInt()}% (soglia ${t.spo2Low})")
        val illness = insights.illness()
        when (illness.level) {
            WarningLevel.ALERT -> fire("illness", "Possibile malessere: ${illness.reasons.joinToString(", ")}. Due notti di fila.")
            WarningLevel.WATCH -> fire("illness_watch", "Da tenere d'occhio: ${illness.reasons.joinToString(", ")}.")
            WarningLevel.NONE -> Unit
        }
        if (batteryLevel != null && !charging && batteryLevel <= t.batteryLow) fire("battery_low", "Batteria del ring bassa: $batteryLevel%")
    }

    suspend fun staleSync(hours: Long) = fire("stale", "Nessuna sincronizzazione con il ring da $hours ore")

    private suspend fun latest(type: String): Double? = dao.lastN(type, 1).firstOrNull()?.value

    private suspend fun fire(kind: String, message: String) {
        val now = Instant.now().epochSecond
        if (now - settings.lastAlert(kind) < COOLDOWN_SECONDS) return
        settings.markAlert(kind, now)
        dao.insertAlert(AlertEntity(ts = now, kind = kind, message = message))
        notify(kind, message)
    }

    @SuppressLint("MissingPermission")
    private fun notify(kind: String, message: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ring)
            .setContentTitle("Ring")
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
