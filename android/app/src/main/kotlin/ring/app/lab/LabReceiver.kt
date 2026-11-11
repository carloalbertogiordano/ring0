package ring.app.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ring.protocol.Packet
import ring.protocol.hexToBytes
import ring.protocol.toHex

/**
 * Developer tool, registered only in debuggable builds: `adb shell am broadcast -a ring.app.LAB --es hex 0d7800 --ei max 400`
 * sends the hex bytes as the payload of the real-time command (0x69) and logs every packet under the tag "Lab".
 * `adb shell am broadcast -a ring.app.LAB_DO --es cmd rhythm` runs an app function (quick, full, spo2, stress, rhythm, sync, status)
 * and logs its outcome.
 */
class LabReceiver(
    private val scope: CoroutineScope,
    private val probe: suspend (ByteArray, Int, Long, (Packet) -> Unit) -> Unit,
    private val command: suspend (String) -> String,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DO) runCommand(intent) else runProbe(intent)
    }

    private fun runCommand(intent: Intent) {
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: return
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            Log.i(TAG, "do $cmd")
            val outcome = runCatching { command(cmd) }.getOrElse { "failed: $it" }
            Log.i(TAG, "done $cmd after ${SystemClock.elapsedRealtime() - t0}ms: $outcome")
        }
    }

    private fun runProbe(intent: Intent) {
        val hex = intent.getStringExtra(EXTRA_HEX) ?: return
        val start = runCatching { hex.hexToBytes() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        val max = intent.getIntExtra(EXTRA_MAX, DEFAULT_MAX_PACKETS)
        val silence = intent.getLongExtra(EXTRA_SILENCE, DEFAULT_SILENCE_MS)
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            Log.i(TAG, "probe start payload=$hex max=$max silence=$silence")
            runCatching {
                probe(start, max, silence) { p -> Log.i(TAG, "t=${SystemClock.elapsedRealtime() - t0}ms ${p.payload.toHex()}") }
            }.onFailure { Log.w(TAG, "probe failed", it) }
            Log.i(TAG, "probe end after ${SystemClock.elapsedRealtime() - t0}ms")
        }
    }

    companion object {
        const val ACTION_PROBE = "ring.app.LAB"
        const val ACTION_DO = "ring.app.LAB_DO"
        private const val EXTRA_CMD = "cmd"
        private const val EXTRA_HEX = "hex"
        private const val EXTRA_MAX = "max"
        private const val EXTRA_SILENCE = "silence"
        private const val DEFAULT_SILENCE_MS = 4_000L
        private const val DEFAULT_MAX_PACKETS = 400
        private const val TAG = "Lab"
    }
}
