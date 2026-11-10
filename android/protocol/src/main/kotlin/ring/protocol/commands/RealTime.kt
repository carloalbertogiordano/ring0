package ring.protocol.commands

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import ring.protocol.Packet
import ring.protocol.RingClient
import ring.protocol.RingTimeoutException
import ring.protocol.u16le
import ring.protocol.u8

/** Pacchetto real-time: bpm (0 finche' il sensore non si e' agganciato) e, dopo ~30 s, intervallo RR in ms (0 se assente). */
data class RealTimeReading(val kind: Int, val errorCode: Int, val value: Int, val rrMs: Int = 0)

/** Esito di una misura live: bpm e battiti RR raccolti (consecutivi duplicati scartati). */
data class LiveMeasurement(val bpm: Int?, val rr: List<Int>)

/**
 * CMD 0x69 start real-time [tipo][1], 0x6A stop [tipo][0][0]; pacchetti: 0x69 [tipo][errore][bpm][?][?][RR u16 LE].
 * Osservato sul R09: i primi ~30 pacchetti hanno tutto a 0, poi compare l'RR in ms (byte 5-6 del payload).
 */
object RealTimeCommand {
    const val CMD_START = 0x69
    const val CMD_STOP = 0x6A
    const val KIND_HEART_RATE = 1
    const val KIND_SPO2 = 3
    private const val ACTION_START = 1
    private const val DEFAULT_MAX_PACKETS = 150
    private const val DEFAULT_TARGET_BEATS = 12
    private const val RR_OFFSET = 5
    private const val STABLE_PACKETS = 10
    private const val MIN_PARTIAL_BEATS = 3
    private const val PACKET_TIMEOUT_MS = 4_000L

    fun decode(pkt: Packet): RealTimeReading {
        require(pkt.cmd == CMD_START) { "not a real-time packet: cmd %#04x".format(pkt.cmd) }
        return RealTimeReading(pkt.payload.u8(0), pkt.payload.u8(1), pkt.payload.u8(2), pkt.payload.u16le(RR_OFFSET))
    }

    /** RR distinti consecutivi: lo stesso valore ripetuto in pacchetti vicini e' lo stesso battito. */
    fun beats(readings: List<RealTimeReading>): List<Int> {
        val out = mutableListOf<Int>()
        for (r in readings) if (r.rrMs > 0 && r.rrMs != out.lastOrNull()) out += r.rrMs
        return out
    }

    /**
     * Misura live finche' ci sono `targetBeats` battiti RR (o errore / `maxPackets`). `onProgress` riceve il numero
     * di battiti raccolti. Restituisce null in caso di errore del ring o timeout.
     */
    suspend fun measure(
        client: RingClient,
        kind: Int = KIND_HEART_RATE,
        maxPackets: Int = DEFAULT_MAX_PACKETS,
        targetBeats: Int = DEFAULT_TARGET_BEATS,
        onProgress: (Int) -> Unit = {},
    ): LiveMeasurement? {
        var latest: List<Packet> = emptyList()
        try {
            val packets = client.requestMany(
                CMD_START, byteArrayOf(kind.toByte(), ACTION_START.toByte()), timeoutMs = PACKET_TIMEOUT_MS,
            ) { received ->
                latest = received
                val decoded = received.map(::decode)
                val count = beats(decoded).size
                onProgress(count)
                // il bpm del ring da solo ferma la misura solo se non arrivano RR (altrimenti servono i battiti)
                val firstBpm = decoded.indexOfFirst { it.value > 0 }
                val bpmOnly = count == 0 && firstBpm >= 0 && decoded.size - firstBpm >= STABLE_PACKETS
                decoded.last().errorCode != 0 || count >= targetBeats || bpmOnly || received.size >= maxPackets
            }
            return summarize(packets.map(::decode))
        } catch (_: RingTimeoutException) {
            // flusso interrotto: usa quanto raccolto se basta per un bpm/HRV, altrimenti niente
            return summarize(latest.map(::decode))?.takeIf { it.rr.size >= MIN_PARTIAL_BEATS }
        } finally {
            // lo stop e' best effort e deve partire anche se il chiamante viene cancellato
            withContext(NonCancellable) { runCatching { client.send(CMD_STOP, byteArrayOf(kind.toByte(), 0, 0)) } }
        }
    }

    private fun summarize(readings: List<RealTimeReading>): LiveMeasurement? {
        if (readings.isEmpty() || readings.last().errorCode != 0) return null
        val rr = beats(readings)
        val bpm = readings.lastOrNull { it.value > 0 }?.value ?: Hrv.meanBpm(Hrv.clean(rr))
        return LiveMeasurement(bpm, rr)
    }
}
