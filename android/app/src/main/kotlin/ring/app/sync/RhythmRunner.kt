package ring.app.sync

import android.content.pm.ApplicationInfo
import android.util.Log
import ring.app.ble.RingSession
import ring.app.data.RingDao
import ring.app.data.Sample
import ring.app.data.SampleType
import ring.domain.RhythmAdvice
import ring.domain.RhythmCheck
import ring.domain.RhythmHistory
import ring.domain.RhythmLevel
import ring.domain.RhythmResult
import ring.protocol.commands.RealTimeCommand
import java.time.Instant

data class RhythmOutcome(val result: RhythmResult, val advice: RhythmAdvice)

/** Runs a rhythm check: about two minutes of live RR intervals, analysed and stored (regular/irregular only). */
class RhythmRunner(private val dao: RingDao, private val debuggable: Boolean) {
    /** Null when the ring gave no usable measurement. `onProgress` receives the number of beats so far. */
    suspend fun run(session: RingSession, onProgress: (Int) -> Unit): RhythmOutcome? {
        val measurement = RealTimeCommand.measure(session.v1, maxPackets = TARGET_BEATS * PACKETS_PER_BEAT, targetBeats = TARGET_BEATS, onProgress = onProgress)
            ?: return null
        // the raw intervals are kept out of release logs: they are health data
        if (debuggable) Log.i(TAG, "rr=" + measurement.rr.joinToString(","))
        val result = RhythmCheck.analyze(measurement.rr)
        val now = Instant.now().epochSecond
        if (result.level != RhythmLevel.INCONCLUSIVE) {
            dao.upsertSamples(listOf(Sample(SampleType.RHYTHM, now, if (result.level == RhythmLevel.IRREGULAR) 1.0 else 0.0)))
        }
        val irregular = dao.range(SampleType.RHYTHM, now - WEEK, now).filter { it.value > 0 }.map { it.ts }
        return RhythmOutcome(result, RhythmHistory.advice(irregular, now))
    }

    companion object {
        const val TARGET_BEATS = 130
        private const val PACKETS_PER_BEAT = 4
        private const val WEEK = 7 * 86_400L
        private const val TAG = "Rhythm"

        fun isDebuggable(flags: Int) = flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
