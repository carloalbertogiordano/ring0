package ring.protocol.commands

import java.time.Instant
import java.time.ZoneOffset
import ring.protocol.Packet
import ring.protocol.RingClient
import ring.protocol.u8

/** Capabilities declared by the ring in its reply to set time. */
data class Capabilities(
    val temperature: Boolean,
    val bloodOxygen: Boolean,
    val bloodPressure: Boolean,
    val manualHeartRate: Boolean,
    val bloodSugar: Boolean,
    val stress: Boolean,
    val hrv: Boolean,
    val newSleepProtocol: Boolean,
    val raw: List<Int>,
)

/** CMD 0x01: [yy][mm][dd][hh][mi][ss][lingua], campi in BCD, ora UTC. */
object SetTimeCommand {
    const val CMD = 0x01
    private const val LANGUAGE_ENGLISH = 1
    private const val MIN_YEAR = 2000
    private const val MAX_YEAR = 2099
    private const val MAX_BCD = 99

    fun toBcd(value: Int): Int {
        require(value in 0..MAX_BCD) { "value not encodable as bcd: $value" }
        return ((value / 10) shl 4) or (value % 10)
    }

    fun encodePayload(at: Instant): ByteArray {
        val utc = at.atOffset(ZoneOffset.UTC)
        require(utc.year in MIN_YEAR..MAX_YEAR) { "year out of range: ${utc.year}" }
        val fields = intArrayOf(utc.year - MIN_YEAR, utc.monthValue, utc.dayOfMonth, utc.hour, utc.minute, utc.second)
        return ByteArray(fields.size + 1) { i -> (if (i < fields.size) toBcd(fields[i]) else LANGUAGE_ENGLISH).toByte() }
    }

    fun decode(pkt: Packet): Capabilities {
        require(pkt.cmd == CMD) { "not a set time packet: cmd %#04x".format(pkt.cmd) }
        val p = pkt.payload
        return Capabilities(
            temperature = p.u8(0) == 1,
            bloodOxygen = p.u8(3) and 0x02 != 0,
            bloodPressure = p.u8(3) and 0x04 != 0,
            manualHeartRate = p.u8(11) and 0x01 != 0,
            bloodSugar = p.u8(11) and 0x80 != 0,
            stress = p.u8(13) and 0x10 != 0,
            hrv = p.u8(13) and 0x20 != 0,
            newSleepProtocol = p.u8(8) == 1,
            raw = p.map { it.toInt() and 0xFF },
        )
    }

    suspend fun set(client: RingClient, at: Instant = Instant.now()): Capabilities =
        decode(client.request(CMD, encodePayload(at)))
}
