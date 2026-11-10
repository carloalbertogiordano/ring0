package ring.app.sync

import android.content.pm.ApplicationInfo
import android.os.SystemClock
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

/**
 * Runs a rhythm check: the ring streams RR intervals for only about 30 seconds per session (14 s to lock on, then 17-20
 * beats), so the stream is restarted up to eight times to collect six usable sessions, about three minutes in all.
 */
class RhythmRunner(private val dao: RingDao, private val debuggable: Boolean) {
    /** Null when the ring gave nothing usable. `onSession` receives the number of the session that is starting. */
    suspend fun run(session: RingSession, onSession: (Int) -> Unit): RhythmOutcome? {
        val started = SystemClock.elapsedRealtime()
        val sessions = mutableListOf<List<Int>>()
        var attempts = 0
        while (RhythmCheck.analyze(sessions).segments < TARGET_SEGMENTS && attempts < MAX_ATTEMPTS) {
            attempts++
            onSession(attempts)
            val measurement = RealTimeCommand.measure(
                session.v1,
                maxPackets = MAX_PACKETS,
                targetBeats = MAX_BEATS,
                onPacket = {
                    if (debuggable) {
                        Log.i(
                            TAG,
                            "pkt s=$attempts t=${SystemClock.elapsedRealtime() - started}ms err=${it.errorCode} bpm=${it.value} rr=${it.rrMs}",
                        )
                    }
                },
            )
            measurement?.let { sessions += it.rr }
        }
        // the raw intervals are kept out of release logs: they are health data
        if (debuggable) Log.i(TAG, "sessions=" + sessions.joinToString(";") { it.joinToString(",") })
        val result = RhythmCheck.analyze(sessions)
        val now = Instant.now().epochSecond
        if (result.level != RhythmLevel.INCONCLUSIVE) {
            dao.upsertSamples(listOf(Sample(SampleType.RHYTHM, now, if (result.level == RhythmLevel.IRREGULAR) 1.0 else 0.0)))
        }
        val irregular = dao.range(SampleType.RHYTHM, now - WEEK, now).filter { it.value > 0 }.map { it.ts }
        return RhythmOutcome(result, RhythmHistory.advice(irregular, now))
    }

    companion object {
        const val TARGET_SEGMENTS = 6
        const val MAX_ATTEMPTS = 8
        private const val MAX_BEATS = 60
        private const val MAX_PACKETS = 400
        private const val WEEK = 7 * 86_400L
        private const val TAG = "Rhythm"

        fun isDebuggable(flags: Int) = flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
}
