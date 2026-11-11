package ring.app.sync

import android.content.pm.ApplicationInfo
import android.util.Log
import ring.app.ble.RingSession
import ring.domain.Ppg
import ring.domain.PpgBeats
import ring.protocol.commands.RawStream
import java.io.File

/** Records the ring's raw optical stream for a chosen time and turns it into beats. */
class PpgRecorder(private val debuggable: Boolean, private val dump: File? = null) {
    /** Null when too few packets arrived (ring not worn, link lost). `onSecond` receives the seconds recorded so far. */
    suspend fun record(session: RingSession, seconds: Int, onSecond: (Int) -> Unit = {}): PpgBeats? {
        var received = 0
        val samples = RawStream.record(session.v1, seconds) {
            received++
            if (received % RawStream.SAMPLE_RATE_HZ == 0) onSecond(received / RawStream.SAMPLE_RATE_HZ)
        }
        if (samples.size < seconds * RawStream.SAMPLE_RATE_HZ * MIN_RECEIVED_SHARE) return null
        val beats = Ppg.analyze(samples)
        // debug builds keep the last recording (seq,status,a,b,x,y,z per line) so a bad result can be analysed offline
        if (debuggable) dump?.writeText(samples.joinToString("\n") { "${it.seq},${it.status},${it.a},${it.b},${it.x},${it.y},${it.z}" })
        // counts only: the intervals themselves are health data and stay out of release logs
        if (debuggable) {
            Log.i(
                TAG,
                "raw samples=${samples.size} beats=${beats.timesSec.size} segments=${beats.segments.map { it.size }} " +
                    "quality=${"%.2f".format(beats.quality)} motion=${"%.2f".format(beats.motionShare)} lost=${"%.2f".format(beats.lostShare)}",
            )
        }
        return beats
    }

    companion object {
        private const val MIN_RECEIVED_SHARE = 0.5
        private const val TAG = "Ppg"

        fun isDebuggable(flags: Int) = flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
