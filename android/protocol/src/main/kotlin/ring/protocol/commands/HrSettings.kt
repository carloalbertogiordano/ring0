package ring.protocol.commands

import ring.protocol.Packet
import ring.protocol.RingClient
import ring.protocol.u8

data class HeartRateSettings(val enabled: Boolean, val intervalMinutes: Int)

/** CMD 0x16: read [01]; write [02][1=yes|2=no][min]. The reply to a write is unreliable. */
object HrSettingsCommand {
    const val CMD = 0x16
    private const val SUB_READ = 1
    private const val SUB_WRITE = 2
    private const val FLAG_ENABLED = 1
    private const val FLAG_DISABLED = 2
    private const val MAX_INTERVAL = 255

    fun decode(pkt: Packet): HeartRateSettings {
        require(pkt.cmd == CMD) { "not a heart rate settings packet: cmd %#04x".format(pkt.cmd) }
        val flag = pkt.payload.u8(1)
        require(flag == FLAG_ENABLED || flag == FLAG_DISABLED) { "unexpected enabled flag: $flag" }
        return HeartRateSettings(flag == FLAG_ENABLED, pkt.payload.u8(2))
    }

    fun encodeRead(): ByteArray = byteArrayOf(SUB_READ.toByte())

    fun encodeWrite(settings: HeartRateSettings): ByteArray {
        require(settings.intervalMinutes in 1..MAX_INTERVAL) { "interval must be 1..$MAX_INTERVAL: ${settings.intervalMinutes}" }
        val flag = if (settings.enabled) FLAG_ENABLED else FLAG_DISABLED
        return byteArrayOf(SUB_WRITE.toByte(), flag.toByte(), settings.intervalMinutes.toByte())
    }

    suspend fun read(client: RingClient): HeartRateSettings = decode(client.request(CMD, encodeRead()))

    suspend fun write(client: RingClient, settings: HeartRateSettings): HeartRateSettings {
        client.request(CMD, encodeWrite(settings))
        return read(client)
    }
}
