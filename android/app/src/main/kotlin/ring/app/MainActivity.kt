package ring.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import ring.app.service.RingService
import ring.app.ui.AppRoot
import ring.app.ui.RingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as RingApp).graph
        setContent { RingTheme { AppRoot(graph, ::startRingService) } }
        // already set up: restart the service if the permissions are still granted
        if (graph.settings.onboarded) startRingService()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_CHECK, false) == true) (application as RingApp).graph.requestCheck()
    }

    companion object {
        const val EXTRA_OPEN_CHECK = "open_check"
    }

    private fun startRingService() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (ok) ContextCompat.startForegroundService(this, Intent(this, RingService::class.java))
    }
}
