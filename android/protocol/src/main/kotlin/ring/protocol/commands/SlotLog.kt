package ring.protocol.commands

import ring.protocol.Packet
import ring.protocol.RingClient
import ring.protocol.u8
import java.time.Instant

data class SlotLog(val intervalMinutes: Int, val samples: List<Int>) {
    fun readings(dayStart: Instant): List<Reading> =
        samples.mapIndexedNotNull { i, v -> if (v > 0) Reading(dayStart.plusMinutes(i * intervalMinutes), v.toDouble()) else null }
}

/** CMD 0x37 (stress, no arguments) and 0x39 (HRV, [days ago u32 LE]): one value every 30 minutes. */
object SlotLogCommand {
    const val CMD_STRESS = 0x37
    const val CMD_HRV = 0x39
    private const val DEFAULT_INTERVAL = 30
    private const val SUB_FIRST = 1
    private const val FIRST_BLOCK_START = 2
    private const val NEXT_BLOCK_START = 1
    private const val BLOCK_END = 14

    fun encodeHrvRequest(daysAgo: Int): ByteArray {
        require(daysAgo >= 0) { "daysAgo must be >= 0: $daysAgo" }
        return ByteArray(4) { (daysAgo shr (8 * it)).toByte() }
    }

    fun decode(packets: List<Packet>): SlotLog? {
        if (packets.first().payload.u8(0) == SplitArray.SUB_NO_DATA) return null
        val interval = packets.first().payload.u8(2).takeIf { it != 0 } ?: DEFAULT_INTERVAL
        val samples = mutableListOf<Int>()
        for (pkt in packets.drop(1)) {
            val start = if (pkt.payload.u8(0) == SUB_FIRST) FIRST_BLOCK_START else NEXT_BLOCK_START
            for (i in start until BLOCK_END) samples += pkt.payload.u8(i)
        }
        if (samples.isEmpty()) return null
        return SlotLog(interval, samples.take(MINUTES_PER_DAY / interval))
    }

    suspend fun readStress(client: RingClient): SlotLog? =
        decode(client.requestMany(CMD_STRESS, isLast = SplitArray::isComplete))

    suspend fun readHrv(client: RingClient, daysAgo: Int = 0): SlotLog? =
        decode(client.requestMany(CMD_HRV, encodeHrvRequest(daysAgo), isLast = SplitArray::isComplete))
}
