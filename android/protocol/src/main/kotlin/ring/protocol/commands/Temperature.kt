package ring.protocol.commands

import ring.protocol.BigDataClient
import ring.protocol.BigFrame
import ring.protocol.u8
import java.time.Instant

data class TemperatureDay(val daysAgo: Int, val intervalMinutes: Int, val samples: List<Int>) {
    /** °C = raw / 10 + 20; raw 0 = no measurement. `dayStart` = UTC midnight of that day. */
    fun readings(dayStart: Instant): List<Reading> =
        samples.mapIndexedNotNull { i, raw ->
            if (raw > 0) Reading(dayStart.plusMinutes(i * intervalMinutes), raw * CELSIUS_PER_UNIT + BASE_CELSIUS) else null
        }

    companion object {
        const val CELSIUS_PER_UNIT = 0.1
        const val BASE_CELSIUS = 20.0
    }
}

/** Big-data 0x25: 50-byte blocks [days ago][step min][48 values], oldest first up to today. */
object TemperatureCommand {
    const val TYPE = 0x25
    private const val SLOTS = 48
    private const val BLOCK = 2 + SLOTS
    private const val EMPTY_LENGTH = 2
    private const val DEFAULT_DAYS = 2
    private const val MAX_DAYS = 255

    fun encodeRequest(days: Int = DEFAULT_DAYS): ByteArray {
        require(days in 1..MAX_DAYS) { "days must be 1..$MAX_DAYS: $days" }
        return byteArrayOf(days.toByte())
    }

    fun decode(frame: BigFrame): List<TemperatureDay> {
        require(frame.typeId == TYPE) { "not a temperature frame: type %#04x".format(frame.typeId) }
        val p = frame.payload
        if (p.size <= EMPTY_LENGTH) return emptyList()
        require(p.size % BLOCK == 0) { "truncated temperature payload: ${p.size} bytes" }
        return (p.indices step BLOCK).map { i ->
            TemperatureDay(p.u8(i), p.u8(i + 1), (0 until SLOTS).map { p.u8(i + 2 + it) })
        }
    }

    suspend fun read(client: BigDataClient, days: Int = DEFAULT_DAYS): List<TemperatureDay> =
        decode(client.request(TYPE, encodeRequest(days)))
}
