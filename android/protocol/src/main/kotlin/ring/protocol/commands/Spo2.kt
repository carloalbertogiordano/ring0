package ring.protocol.commands

import ring.protocol.BigDataClient
import ring.protocol.BigFrame
import ring.protocol.u8
import java.time.Instant

data class Spo2Hour(val hour: Int, val min: Int, val max: Int)

data class Spo2Day(val daysAgo: Int, val hours: List<Spo2Hour>) {
    /** One reading per hour with valid values: the mean of min and max. */
    fun readings(dayStart: Instant): List<Reading> =
        hours.map { Reading(dayStart.plusMinutes(it.hour * MINUTES_PER_HOUR), (it.min + it.max) / 2.0) }

    private companion object {
        const val MINUTES_PER_HOUR = 60
    }
}

/**
 * Big-data 0x2A: 49-byte blocks [days ago][24 x (min,max)] hourly. Layout from Gadgetbridge; verified
 * on the R09 with real data (96% SpO2 received).
 */
object Spo2Command {
    const val TYPE = 0x2A
    private const val HOURS = 24
    private const val BLOCK = 1 + 2 * HOURS
    private const val REQUEST = 0xFF

    fun encodeRequest(): ByteArray = byteArrayOf(REQUEST.toByte())

    fun decode(frame: BigFrame): List<Spo2Day> {
        require(frame.typeId == TYPE) { "not an spo2 frame: type %#04x".format(frame.typeId) }
        val p = frame.payload
        if (p.isEmpty()) return emptyList()
        require(p.size % BLOCK == 0) { "truncated spo2 payload: ${p.size} bytes" }
        return (p.indices step BLOCK).map { i ->
            val hours = (0 until HOURS).mapNotNull { h ->
                val min = p.u8(i + 1 + 2 * h)
                val max = p.u8(i + 2 + 2 * h)
                if (min > 0 && max > 0) Spo2Hour(h, min, max) else null
            }
            Spo2Day(p.u8(i), hours)
        }
    }

    suspend fun read(client: BigDataClient): List<Spo2Day> = decode(client.request(TYPE, encodeRequest()))
}
