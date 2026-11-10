package ring.protocol.commands

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ring.protocol.Packet
import ring.protocol.RingClient
import ring.protocol.RingTimeoutException
import ring.protocol.u8

data class RealTimeReading(val kind: Int, val errorCode: Int, val value: Int)

/** CMD 0x69 start real-time [tipo][1], 0x6A stop [tipo][0][0]; letture: 0x69 [tipo][errore][valore]. */
object RealTimeCommand {
    const val CMD_START = 0x69
    const val CMD_STOP = 0x6A
    const val KIND_HEART_RATE = 1
    const val KIND_SPO2 = 3
    private const val ACTION_START = 1
    private const val DEFAULT_MAX_PACKETS = 40

    fun decode(pkt: Packet): RealTimeReading {
        require(pkt.cmd == CMD_START) { "not a real-time packet: cmd %#04x".format(pkt.cmd) }
        return RealTimeReading(pkt.payload.u8(0), pkt.payload.u8(1), pkt.payload.u8(2))
    }

    /** Misura una volta: attende il primo valore non nullo (i primi secondi sono 0); null se errore o timeout. */
    suspend fun measure(client: RingClient, kind: Int = KIND_HEART_RATE, maxPackets: Int = DEFAULT_MAX_PACKETS): Int? {
        try {
            val readings = client.requestMany(CMD_START, byteArrayOf(kind.toByte(), ACTION_START.toByte()), timeoutMs = 4_000) { packets ->
                val last = decode(packets.last())
                last.errorCode != 0 || last.value > 0 || packets.size >= maxPackets
            }.map(::decode)
            return readings.last().takeIf { it.errorCode == 0 && it.value > 0 }?.value
        } catch (_: RingTimeoutException) {
            return null
        } finally {
            // lo stop e' best effort e deve partire anche se il chiamante viene cancellato
            withContext(NonCancellable) { runCatching { client.send(CMD_STOP, byteArrayOf(kind.toByte(), 0, 0)) } }
        }
    }
}
