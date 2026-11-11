package ring.protocol.commands

import ring.protocol.Packet
import ring.protocol.RingClient
import ring.protocol.s16le
import ring.protocol.u16le
import ring.protocol.u8

/**
 * One packet of the raw stream: two optical values (u16) and three accelerometer axes (s16), about 25 per second. `status` is a byte whose
 * low bits change every few dozen packets (probably the sensor gain); `seq` is a counter that wraps at 256 (about 4.5% of the packets are lost).
 */
data class RawSample(val seq: Int, val status: Int, val a: Int, val b: Int, val x: Int, val y: Int, val z: Int)

/**
 * Real-time type 13 of the R09: a raw stream with a duration chosen by the app (`69 0D <seconds u16 LE>`, at least 10 s). The ring stops by itself
 * when the time is over; to end it earlier a new start of another type is needed (see [RealTimeCommand.stream]).
 */
object RawStream {
    const val TYPE_HEART = 13
    const val SAMPLE_RATE_HZ = 25
    private const val MIN_SECONDS = 10
    private const val MAX_SECONDS = 65_535
    private const val SLACK_PACKETS = 10
    private const val SILENCE_MS = 2_500L
    private const val SEQ = 2
    private const val STATUS = 3
    private const val A = 4
    private const val B = 6
    private const val X = 8
    private const val Y = 10
    private const val Z = 12

    fun encodeStart(seconds: Int): ByteArray {
        require(seconds in MIN_SECONDS..MAX_SECONDS) { "duration must be $MIN_SECONDS..$MAX_SECONDS seconds: $seconds" }
        return byteArrayOf(TYPE_HEART.toByte(), (seconds and 0xFF).toByte(), (seconds shr 8).toByte())
    }

    /** Null when the packet is of another type or is the all-zero packet the ring sends before the first sample. */
    fun decode(pkt: Packet): RawSample? {
        if (pkt.cmd != RealTimeCommand.CMD_START || pkt.payload.u8(0) != TYPE_HEART) return null
        val p = pkt.payload
        val sample = RawSample(p.u8(SEQ), p.u8(STATUS), p.u16le(A), p.u16le(B), p.s16le(X), p.s16le(Y), p.s16le(Z))
        return sample.takeUnless { it.a == 0 && it.b == 0 && it.x == 0 && it.y == 0 && it.z == 0 }
    }

    /** Records `seconds` of raw data (or less if the stream stops), reporting each sample as it arrives. */
    suspend fun record(client: RingClient, seconds: Int, onSample: (RawSample) -> Unit = {}): List<RawSample> {
        val samples = mutableListOf<RawSample>()
        RealTimeCommand.stream(client, encodeStart(seconds), maxPackets = seconds * SAMPLE_RATE_HZ + SLACK_PACKETS, silenceMs = SILENCE_MS) { pkt ->
            decode(pkt)?.let {
                samples += it
                onSample(it)
            }
        }
        return samples
    }
}
