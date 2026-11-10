package ring.protocol.commands

import ring.protocol.Packet
import ring.protocol.RingClient
import ring.protocol.u16le
import ring.protocol.u8
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Activity of one 15-minute slot (the ring clock is UTC). */
data class StepSlot(val time: Instant, val steps: Int, val calories: Int, val distanceM: Int)

/**
 * CMD 0x43: activity per 15-minute slot for a day. Request: [dayOffset][0x0F][0x00][0x5F][0x01] (0 = today).
 * Response: an optional header [0xF0][packets][newCalorieProtocol], then one packet per slot:
 * [year BCD][month BCD][day BCD][slot index 0..95][packet index][packet total][calories u16][steps u16][distance u16].
 * `0xFF` as first byte means no data. Layout from tahnok/colmi_r02_client (real R02 packets in the tests).
 */
object StepsCommand {
    const val CMD = 0x43
    private const val NO_DATA = 0xFF
    private const val HEADER = 0xF0
    private const val SLOT_MINUTES = 15L
    private const val CALORIE_SCALE_NEW = 10
    private const val MAX_DIGIT = 9
    private const val NIBBLE = 4
    private const val BASE_YEAR = 2000

    fun encodeRequest(dayOffset: Int): ByteArray {
        require(dayOffset in 0..0xFF) { "dayOffset must be 0..255: $dayOffset" }
        return byteArrayOf(dayOffset.toByte(), 0x0F, 0x00, 0x5F, 0x01)
    }

    fun isComplete(packets: List<Packet>): Boolean {
        val p = packets.last().payload
        return when (p.u8(0)) {
            NO_DATA -> true
            HEADER -> p.u8(1) == 0
            else -> p.u8(4) == p.u8(5) - 1
        }
    }

    fun decode(packets: List<Packet>): List<StepSlot> {
        val first = packets.first().payload
        if (first.u8(0) == NO_DATA) return emptyList()
        val scale = if (first.u8(0) == HEADER && first.u8(2) == 1) CALORIE_SCALE_NEW else 1
        return packets.map { it.payload }.filter { it.u8(0) != HEADER && it.u8(0) != NO_DATA }.map { p ->
            val date = LocalDate.of(BASE_YEAR + fromBcd(p.u8(0)), fromBcd(p.u8(1)), fromBcd(p.u8(2)))
            val time = date.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(p.u8(3) * SLOT_MINUTES * 60)
            StepSlot(time, steps = p.u16le(8), calories = p.u16le(6) * scale, distanceM = p.u16le(10))
        }
    }

    private fun fromBcd(b: Int): Int {
        val hi = (b shr NIBBLE) and 0xF
        val lo = b and 0xF
        require(hi <= MAX_DIGIT && lo <= MAX_DIGIT) { "invalid BCD byte: %#04x".format(b) }
        return hi * 10 + lo
    }

    suspend fun read(client: RingClient, dayOffset: Int = 0): List<StepSlot> =
        decode(client.requestMany(CMD, encodeRequest(dayOffset), isLast = ::isComplete))
}

/** Daily goals configured on the ring. */
data class Goals(val steps: Int, val calories: Int)

/** CMD 0x21: read [0x01]; reply [0x21][sub][steps u24 LE][calories u24 LE]. Layout from Gadgetbridge, verified live. */
object GoalsCommand {
    const val CMD = 0x21
    private const val SUB_READ = 1

    fun encodeRead(): ByteArray = byteArrayOf(SUB_READ.toByte())

    fun decode(pkt: Packet): Goals {
        require(pkt.cmd == CMD) { "not a goals packet: cmd %#04x".format(pkt.cmd) }
        val p = pkt.payload
        return Goals(steps = u24(p, 1), calories = u24(p, 4))
    }

    private fun u24(p: ByteArray, i: Int) = p.u8(i) or (p.u8(i + 1) shl 8) or (p.u8(i + 2) shl 16)

    suspend fun read(client: RingClient): Goals = decode(client.request(CMD, encodeRead()))
}
