package ring.app.ui

import androidx.compose.runtime.Composable
import ring.app.R
import androidx.compose.ui.res.stringResource
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

/** Permission and Bluetooth state checks. */
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

/** "41 min ago" / "2 h ago" / "never", localized. */
@Composable
fun ago(epoch: Long): String {
    if (epoch == 0L) return stringResource(R.string.ago_never)
    val min = (Instant.now().epochSecond - epoch) / 60
    return when {
        min < 1 -> stringResource(R.string.ago_now)
        min < 60 -> stringResource(R.string.ago_min, min.toInt())
        min < 60 * 48 -> stringResource(R.string.ago_hours, (min / 60).toInt())
        else -> stringResource(R.string.ago_days, (min / (60 * 24)).toInt())
    }
}

private val DAY_TIME_FORMAT = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault())

fun dayTime(epoch: Long): String = DAY_TIME_FORMAT.format(Instant.ofEpochSecond(epoch))
