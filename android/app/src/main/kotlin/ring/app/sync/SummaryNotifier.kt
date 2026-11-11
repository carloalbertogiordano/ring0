package ring.app.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ring.app.MainActivity
import ring.app.R
import ring.app.data.SettingsStore
import ring.app.insights.InsightsSnapshot
import ring.domain.SummarySchedule
import ring.domain.SummarySlot
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Sends the morning and evening summaries once per day, built from the latest insights snapshot. */
class SummaryNotifier(private val context: Context, private val settings: SettingsStore, private val zone: ZoneId = ZoneId.systemDefault()) {
    init {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_summary), NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun maybeNotify(snapshot: InsightsSnapshot, now: Instant = Instant.now()) {
        if (!settings.summaryEnabled) return
        val local = now.atZone(zone)
        val today = local.toLocalDate().toEpochDay()
        val lastSent = SummarySlot.entries.associateWith { settings.lastSummaryDay(it) }
        val slot = SummarySchedule.due(local.hour * MINUTES_PER_HOUR + local.minute, today, lastSent) ?: return
        val parts = if (slot == SummarySlot.MORNING) morningParts(snapshot) else eveningParts(snapshot)
        if (parts.isEmpty()) return
        val title = context.getString(if (slot == SummarySlot.MORNING) R.string.summary_morning_title else R.string.summary_evening_title)
        if (post(slot, title, parts.joinToString(" · "))) settings.markSummary(slot, today)
    }

    private fun morningParts(s: InsightsSnapshot): List<String> = listOfNotNull(
        s.readiness?.readiness?.let { context.getString(R.string.summary_recovery, it.score) },
        s.sleep?.lastNight?.let { night ->
            context.getString(R.string.summary_sleep, duration(night.totalSleepMin), s.sleep.score ?: 0)
        },
        s.bodyBattery?.takeIf { it.anchored }?.let { context.getString(R.string.summary_energy, it.current.roundToInt()) },
    )

    private fun eveningParts(s: InsightsSnapshot): List<String> = listOfNotNull(
        s.training?.let { context.getString(R.string.summary_load, it.today.trimp.roundToInt()) },
        s.training?.let { context.getString(R.string.summary_kcal, it.today.activeKcal.roundToInt(), (it.basalSoFarKcal + it.today.activeKcal).roundToInt()) },
        s.bodyBattery?.takeIf { it.anchored }?.let { context.getString(R.string.summary_energy, it.current.roundToInt()) },
        s.sleep?.bedtimeMinute?.let { context.getString(R.string.summary_bedtime, "%02d:%02d".format(it / MINUTES_PER_HOUR, it % MINUTES_PER_HOUR)) },
    )

    private fun duration(minutes: Int) = context.getString(R.string.duration_h_m, minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)

    @SuppressLint("MissingPermission")
    private fun post(slot: SummarySlot, title: String, text: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val open = PendingIntent.getActivity(context, slot.ordinal, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ring)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(SUMMARY_ID + slot.ordinal, notification)
        return true
    }

    private companion object {
        const val CHANNEL = "summary"
        const val SUMMARY_ID = 9_100
        const val MINUTES_PER_HOUR = 60
    }
}
