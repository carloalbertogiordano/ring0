package ring.protocol.commands

import ring.protocol.Packet
import ring.protocol.RingClient
import ring.protocol.u8

data class Battery(val level: Int, val charging: Boolean)

/** CMD 0x03: [0x03][percentuale][in_carica]. */
object BatteryCommand {
    const val CMD = 0x03
    private const val MAX_LEVEL = 100

    fun decode(pkt: Packet): Battery {
        require(pkt.cmd == CMD) { "not a battery packet: cmd %#04x".format(pkt.cmd) }
        val level = pkt.payload.u8(0)
        require(level <= MAX_LEVEL) { "impossible battery level: $level" }
        return Battery(level, pkt.payload.u8(1) != 0)
    }

    suspend fun read(client: RingClient): Battery = decode(client.request(CMD))
}
