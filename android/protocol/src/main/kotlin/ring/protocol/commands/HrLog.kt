package ring.protocol.commands

import java.time.Instant
import ring.protocol.Packet
import ring.protocol.RingClient
import ring.protocol.u32le
import ring.protocol.u8

data class HeartRateLog(val dayStart: Instant, val intervalMinutes: Int, val samples: List<Int>) {
    /** Slots with a valid measurement (0 = no measurement). */
    fun readings(): List<Reading> =
        samples.mapIndexedNotNull { i, bpm -> if (bpm > 0) Reading(dayStart.plusMinutes(i * intervalMinutes), bpm.toDouble()) else null }
}

/** CMD 0x15: request [unix ts LE u32, UTC midnight]; multi-packet response (header, 9 values, then 13 per block). */
object HrLogCommand {
    const val CMD = 0x15
    private const val SUB_FIRST = 1
    private const val FIRST_BLOCK = 9
    private const val NEXT_BLOCK = 13

    fun encodeRequest(dayStart: Instant): ByteArray {
        val ts = dayStart.epochSecond
        return ByteArray(4) { (ts shr (8 * it)).toByte() }
    }

    fun decode(packets: List<Packet>): HeartRateLog? {
        if (packets.first().payload.u8(0) == SplitArray.SUB_NO_DATA) return null
        val interval = packets.first().payload.u8(2)
        var dayStart: Instant? = null
        val samples = mutableListOf<Int>()
        for (pkt in packets.drop(1)) {
            val p = pkt.payload
            if (p.u8(0) == SUB_FIRST) {
                dayStart = Instant.ofEpochSecond(p.u32le(1))
                for (i in 0 until FIRST_BLOCK) samples += p.u8(5 + i)
            } else {
                for (i in 0 until NEXT_BLOCK) samples += p.u8(1 + i)
            }
        }
        val start = dayStart ?: return null
        return HeartRateLog(start, interval, samples.take(MINUTES_PER_DAY / interval))
    }

    suspend fun read(client: RingClient, dayStart: Instant): HeartRateLog? =
        decode(client.requestMany(CMD, encodeRequest(dayStart), isLast = SplitArray::isComplete))
}
