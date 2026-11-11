package ring.app.sync

import ring.app.LiveResult
import ring.app.ble.RingSession
import ring.domain.Ppg
import ring.domain.PpgBeats
import ring.domain.RespirationRate
import ring.protocol.commands.Hrv
import ring.protocol.commands.RealTimeCommand
import kotlin.math.roundToInt

/**
 * Live heart measurement. The ring's raw optical stream is the first choice (about 25 samples per second for a chosen time,
 * beats found in the app); when it gives no usable pulse the ring's own 30-second RR stream is used instead.
 */
class HeartMeter(private val recorder: PpgRecorder) {
    /** `onProgress(done, target)` counts seconds of raw recording (0 while the fallback runs). */
    suspend fun measure(session: RingSession, withRespiration: Boolean, onProgress: (Int, Int) -> Unit): LiveResult? {
        val seconds = if (withRespiration) FULL_SECONDS else QUICK_SECONDS
        onProgress(0, seconds)
        val beats = recorder.record(session, seconds) { onProgress(it, seconds) }
        return beats?.takeIf(::usable)?.let(::fromRaw) ?: fromRingRr(session, withRespiration)
    }

    private fun usable(beats: PpgBeats) =
        beats.rrMs.count(Ppg::plausible) >= MIN_BEATS && beats.quality >= MIN_QUALITY && beats.motionShare <= MAX_MOTION

    private fun fromRaw(beats: PpgBeats): LiveResult {
        val breath = RespirationRate.fromRr(beats.longestSegment.map { it.roundToInt() })
        return LiveResult(
            bpm = beats.bpm?.roundToInt(),
            rmssd = beats.rmssd,
            sdnn = beats.sdnn,
            beats = beats.rrMs.size,
            breathsPerMinute = breath?.breathsPerMinute,
            breathConfidence = breath?.confidence ?: 0.0,
        )
    }

    private suspend fun fromRingRr(session: RingSession, withRespiration: Boolean): LiveResult? {
        val target = if (withRespiration) FALLBACK_BEATS_FULL else FALLBACK_BEATS_QUICK
        val m = RealTimeCommand.measure(session.v1, maxPackets = target * PACKETS_PER_BEAT_BUDGET, targetBeats = target) ?: return null
        val rr = Hrv.clean(m.rr)
        val breath = if (withRespiration) RespirationRate.fromRr(m.rr) else null
        return LiveResult(m.bpm, Hrv.rmssd(rr), Hrv.sdnn(rr), rr.size, breath?.breathsPerMinute, breath?.confidence ?: 0.0)
    }

    companion object {
        const val QUICK_SECONDS = 30
        const val FULL_SECONDS = 90
        private const val MIN_BEATS = 12
        private const val MIN_QUALITY = 0.5
        private const val MAX_MOTION = 0.3
        private const val FALLBACK_BEATS_QUICK = 12
        private const val FALLBACK_BEATS_FULL = 70
        private const val PACKETS_PER_BEAT_BUDGET = 4
    }
}
