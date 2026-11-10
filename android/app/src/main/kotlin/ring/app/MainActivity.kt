package ring.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import ring.app.service.RingService

import ring.app.ui.RingTheme

class MainActivity : ComponentActivity() {
    private val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startRingService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as RingApp).graph
        setContent { RingTheme { ring.app.ui.RingApp(graph) } }
        if (permissions.all { granted(it) }) startRingService() else requestPermissions.launch(permissions)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun startRingService() {
        if (granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            ContextCompat.startForegroundService(this, Intent(this, RingService::class.java))
        }
    }
}
