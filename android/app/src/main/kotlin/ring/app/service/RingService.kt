package ring.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ring.app.R
import ring.app.RingApp
import java.time.Instant

/** Servizio in primo piano: tiene la connessione col ring, sincronizza periodicamente e genera gli alert. */
class RingService : Service() {
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel(CHANNEL, getString(R.string.notif_channel_connection), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ring)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_service_text))
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (loop?.isActive != true) loop = (application as RingApp).graph.scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val graph = (application as RingApp).graph
        while (currentCoroutineContext().isActive) {
            if (graph.settings.deviceAddress == null) {
                delay(NO_DEVICE_POLL_MS)
                continue
            }
            run {
                graph.syncNow()
                val last = graph.settings.lastSyncEpoch
                val hours = (Instant.now().epochSecond - last) / SECONDS_PER_HOUR
                if (last != 0L && hours >= STALE_HOURS) graph.alerts.staleSync(hours)
            }
            val failed = graph.status.value.lastError != null
            delay(if (failed) RETRY_MS else graph.settings.syncIntervalMin * MS_PER_MINUTE)
        }
    }

    override fun onDestroy() {
        loop?.cancel()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "connection"
        const val NOTIFICATION_ID = 1
        const val RETRY_MS = 60_000L
        const val NO_DEVICE_POLL_MS = 3_000L
        const val MS_PER_MINUTE = 60_000L
        const val SECONDS_PER_HOUR = 3_600L
        const val STALE_HOURS = 3L
    }
}
