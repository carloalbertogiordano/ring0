package ring.protocol.commands

import ring.protocol.Packet
import ring.protocol.RingClient
import ring.protocol.u8

/** Misura automatica configurabile: (id comando, prefisso payload). */
enum class Feature(val cmd: Int, val prefix: ByteArray) {
    SPO2(0x2C, byteArrayOf()),
    STRESS(0x36, byteArrayOf()),
    HRV(0x38, byteArrayOf()),
    TEMPERATURE(0x3A, byteArrayOf(0x03)),
}

/** Lettura [cmd][prefisso][01]; scrittura [cmd][prefisso][02][0|1]. La risposta alla scrittura non e' affidabile. */
object AutoMonitorCommand {
    private const val SUB_READ = 1
    private const val SUB_WRITE = 2

    fun encodeRead(feature: Feature): ByteArray = feature.prefix + byteArrayOf(SUB_READ.toByte())

    fun encodeWrite(feature: Feature, enabled: Boolean): ByteArray =
        feature.prefix + byteArrayOf(SUB_WRITE.toByte(), if (enabled) 1 else 0)

    fun decode(feature: Feature, pkt: Packet): Boolean {
        require(pkt.cmd == feature.cmd) { "not a ${feature.name.lowercase()} preference packet: cmd %#04x".format(pkt.cmd) }
        return pkt.payload.u8(feature.prefix.size + 1) == 1
    }

    suspend fun read(client: RingClient, feature: Feature): Boolean =
        decode(feature, client.request(feature.cmd, encodeRead(feature)))

    suspend fun write(client: RingClient, feature: Feature, enabled: Boolean): Boolean {
        client.request(feature.cmd, encodeWrite(feature, enabled))
        return read(client, feature)
    }
}
