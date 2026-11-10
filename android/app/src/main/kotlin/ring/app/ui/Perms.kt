package ring.app.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Controlli dello stato dei permessi e del Bluetooth. */
object Perms {
    val nearby = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    const val NOTIFICATIONS = Manifest.permission.POST_NOTIFICATIONS

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun hasNearby(c: Context) = nearby.all { granted(c, it) }

    fun hasNotifications(c: Context) = granted(c, NOTIFICATIONS)

    fun batteryUnrestricted(c: Context) = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

    fun bluetoothOn(c: Context): Boolean = c.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true

    val enableBluetoothAction = BluetoothAdapter.ACTION_REQUEST_ENABLE
}

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

fun clock(epoch: Long): String = CLOCK.format(Instant.ofEpochSecond(epoch))

/** "41 min fa" / "2 h fa" / "mai". */
fun ago(epoch: Long): String {
    if (epoch == 0L) return "mai"
    val min = (Instant.now().epochSecond - epoch) / 60
    return when {
        min < 1 -> "adesso"
        min < 60 -> "$min min fa"
        min < 60 * 48 -> "${min / 60} h fa"
        else -> "${min / (60 * 24)} gg fa"
    }
}
