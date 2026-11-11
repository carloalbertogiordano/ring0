package ring.app.sync

import ring.app.ble.RingSession
import ring.app.data.RingDao
import ring.app.data.Sample
import ring.app.data.SampleType
import ring.domain.RhythmAdvice
import ring.domain.RhythmCheck
import ring.domain.RhythmHistory
import ring.domain.RhythmLevel
import ring.domain.RhythmResult
import java.time.Instant

data class RhythmOutcome(val result: RhythmResult, val advice: RhythmAdvice)

/**
 * Runs a rhythm check on one raw optical recording of about three minutes: long enough for several contiguous stretches of
 * 32 or more beats between the signal dropouts.
 */
class RhythmRunner(private val dao: RingDao, private val recorder: PpgRecorder) {
    /** Null when the ring gave nothing usable. `onSecond` receives the seconds recorded so far. */
    suspend fun run(session: RingSession, onSecond: (Int) -> Unit): RhythmOutcome? {
        val recording = recorder.record(session, DURATION_SECONDS, onSecond) ?: return null
        val result = RhythmCheck.analyze(recording)
        val now = Instant.now().epochSecond
        if (result.level != RhythmLevel.INCONCLUSIVE) {
            dao.upsertSamples(listOf(Sample(SampleType.RHYTHM, now, if (result.level == RhythmLevel.IRREGULAR) 1.0 else 0.0)))
        }
        val irregular = dao.range(SampleType.RHYTHM, now - WEEK, now).filter { it.value > 0 }.map { it.ts }
        return RhythmOutcome(result, RhythmHistory.advice(irregular, now))
    }

    companion object {
        const val DURATION_SECONDS = 180
        private const val WEEK = 7 * 86_400L
    }
}
