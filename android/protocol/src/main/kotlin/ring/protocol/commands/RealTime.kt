package ring.protocol.commands

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ring.protocol.Packet
import ring.protocol.RingClient
import ring.protocol.RingTimeoutException
import ring.protocol.u16le
import ring.protocol.u8

/** Real-time packet: bpm (0 until the sensor locks on) and, after ~30 s, the RR interval in ms (0 if absent). */
data class RealTimeReading(val kind: Int, val errorCode: Int, val value: Int, val rrMs: Int = 0)

/** Result of a live measurement: bpm and collected RR beats (consecutive duplicates dropped). */
data class LiveMeasurement(val bpm: Int?, val rr: List<Int>)

/**
 * CMD 0x69 start real-time [kind][1], 0x6A stop [kind][0][0]; packets: 0x69 [kind][error][bpm][?][?][RR u16 LE].
 * Observed on the R09: the first ~30 packets are all zero, then the RR in ms appears (payload bytes 5-6).
 */
object RealTimeCommand {
    const val CMD_START = 0x69
    const val CMD_STOP = 0x6A
    const val KIND_HEART_RATE = 1
    const val KIND_SPO2 = 3
    private const val KIND_REALTIME_HEART = 6
    private const val KIND_RAW_SPO2 = 14
    private const val ACTION_STOP_SESSION = 4

    /** "Pressure" in the vendor protocol, i.e. stress. Support on the R09 is not verified. */
    const val KIND_STRESS = 8
    private const val ACTION_START = 1
    private const val DEFAULT_MAX_PACKETS = 150
    private const val DEFAULT_TARGET_BEATS = 12
    private const val RR_OFFSET = 5
    private const val STABLE_PACKETS = 10
    private const val MIN_PARTIAL_BEATS = 3
    private const val DEFAULT_VALUE_PACKETS = 160
    private const val STABLE_READINGS = 5
    private const val MAX_SPREAD = 2
    private const val PACKET_TIMEOUT_MS = 4_000L

    fun decode(pkt: Packet): RealTimeReading {
        require(pkt.cmd == CMD_START) { "not a real-time packet: cmd %#04x".format(pkt.cmd) }
        return RealTimeReading(pkt.payload.u8(0), pkt.payload.u8(1), pkt.payload.u8(2), pkt.payload.u16le(RR_OFFSET))
    }

    /** Distinct consecutive RR values: the same value repeated in neighbouring packets is the same beat. */
    fun beats(readings: List<RealTimeReading>): List<Int> {
        val out = mutableListOf<Int>()
        for (r in readings) if (r.rrMs > 0 && r.rrMs != out.lastOrNull()) out += r.rrMs
        return out
    }

    /**
     * Live measurement until `targetBeats` RR beats are collected (or error / `maxPackets`). `onProgress` receives
     * the number of beats so far and `onPacket` every packet as it arrives (for diagnostics). Returns null on a ring error or timeout without enough beats.
     */
    suspend fun measure(
        client: RingClient,
        kind: Int = KIND_HEART_RATE,
        maxPackets: Int = DEFAULT_MAX_PACKETS,
        targetBeats: Int = DEFAULT_TARGET_BEATS,
        onPacket: (RealTimeReading) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): LiveMeasurement? {
        var latest: List<Packet> = emptyList()
        try {
            val packets = client.requestMany(
                CMD_START,
                byteArrayOf(kind.toByte(), ACTION_START.toByte()),
                timeoutMs = PACKET_TIMEOUT_MS,
            ) { received ->
                latest = received
                val decoded = received.map(::decode)
                onPacket(decoded.last())
                val count = beats(decoded).size
                onProgress(count)
                // the ring bpm alone stops the measurement only when no RR arrives (otherwise we need the beats)
                val firstBpm = decoded.indexOfFirst { it.value > 0 }
                val bpmOnly = count == 0 && firstBpm >= 0 && decoded.size - firstBpm >= STABLE_PACKETS
                decoded.last().errorCode != 0 || count >= targetBeats || bpmOnly || received.size >= maxPackets
            }
            return summarize(packets.map(::decode))
        } catch (_: RingTimeoutException) {
            // stream interrupted: use what was collected if enough for a bpm/HRV, otherwise nothing
            return summarize(latest.map(::decode))?.takeIf { it.rr.size >= MIN_PARTIAL_BEATS }
        } finally {
            // the stop is best effort and must go out even if the caller is cancelled
            withContext(NonCancellable) { runCatching { client.send(CMD_STOP, byteArrayOf(kind.toByte(), 0, 0)) } }
        }
    }

    /**
     * Streams a real-time type: sends `start` as the raw payload of CMD 0x69 (for example `[13, durationLo, durationHi]` for a kind with a
     * custom duration), collects up to `maxPackets` packets or until the ring stops or goes silent, then sends the stop.
     */
    suspend fun stream(
        client: RingClient,
        start: ByteArray,
        maxPackets: Int,
        silenceMs: Long = PACKET_TIMEOUT_MS,
        onPacket: (Packet) -> Unit = {},
    ): List<Packet> {
        var latest: List<Packet> = emptyList()
        try {
            return client.requestMany(CMD_START, start, timeoutMs = silenceMs) { received ->
                latest = received
                onPacket(received.last())
                decode(received.last()).errorCode != 0 || received.size >= maxPackets
            }
        } catch (_: RingTimeoutException) {
            return latest
        } finally {
            withContext(NonCancellable) {
                runCatching { client.send(CMD_STOP, byteArrayOf(start[0], 0, 0)) }
                // the raw types ignore the stop: starting type 6 with action 4 shuts the previous session's sensor and timer
                runCatching { client.send(CMD_START, byteArrayOf(KIND_REALTIME_HEART.toByte(), ACTION_STOP_SESSION.toByte())) }
                if (start[0].toInt() == KIND_RAW_SPO2) closeSpo2Sensor(client)
            }
        }
    }

    /** A normal spo2 session ends with the stop that switches the red sensor off (the raw spo2 type leaves it on). */
    private suspend fun closeSpo2Sensor(client: RingClient) {
        runCatching {
            client.send(CMD_START, byteArrayOf(KIND_SPO2.toByte(), ACTION_START.toByte()))
            client.send(CMD_STOP, byteArrayOf(KIND_SPO2.toByte(), 0, 0))
        }
    }

    private fun summarize(readings: List<RealTimeReading>): LiveMeasurement? {
        if (readings.isEmpty() || readings.last().errorCode != 0) return null
        val rr = beats(readings)
        val bpm = readings.lastOrNull { it.value > 0 }?.value ?: Hrv.meanBpm(Hrv.clean(rr))
        return LiveMeasurement(bpm, rr)
    }

    /**
     * Measures a single-value kind (e.g. [KIND_SPO2]): waits for a stable non-zero value in `payload[2]` and returns
     * the median of the last readings, or null on a ring error / timeout without a value. The PPG sensor is shared,
     * so kinds must be measured one after the other.
     */
    suspend fun measureValue(
        client: RingClient,
        kind: Int,
        maxPackets: Int = DEFAULT_VALUE_PACKETS,
        onProgress: (Int) -> Unit = {},
    ): Int? {
        var latest: List<Packet> = emptyList()

        fun valueOf(readings: List<RealTimeReading>): Int? =
            readings.map { it.value }.filter { it > 0 }.takeLast(STABLE_READINGS)
                .takeIf { it.size >= STABLE_READINGS && it.max() - it.min() <= MAX_SPREAD }
                ?.sorted()?.let { it[it.size / 2] }
        try {
            val packets = client.requestMany(
                CMD_START,
                byteArrayOf(kind.toByte(), ACTION_START.toByte()),
                timeoutMs = PACKET_TIMEOUT_MS,
            ) { received ->
                latest = received
                val decoded = received.map(::decode)
                onProgress(decoded.count { it.value > 0 })
                decoded.last().errorCode != 0 || valueOf(decoded) != null || received.size >= maxPackets
            }
            val readings = packets.map(::decode)
            return if (readings.last().errorCode != 0) null else valueOf(readings)
        } catch (_: RingTimeoutException) {
            return valueOf(latest.map(::decode))
        } finally {
            withContext(NonCancellable) { runCatching { client.send(CMD_STOP, byteArrayOf(kind.toByte(), 0, 0)) } }
        }
    }
}
