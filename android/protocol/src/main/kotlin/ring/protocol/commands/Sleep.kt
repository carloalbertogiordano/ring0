package ring.protocol.commands

import java.time.Instant
import ring.protocol.BigDataClient
import ring.protocol.BigFrame
import ring.protocol.u16le
import ring.protocol.u8

enum class SleepStageType(val code: Int) {
    LIGHT(2),
    DEEP(3),
    REM(4),
    AWAKE(5),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromCode(code: Int): SleepStageType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

data class SleepStage(val type: SleepStageType, val minutes: Int)

data class SleepSession(val daysAgo: Int, val startMinute: Int, val endMinute: Int, val stages: List<SleepStage>) {
    /** Inizio e fine in UTC; se l'inizio e' dopo la fine l'addormentamento e' avvenuto il giorno prima. */
    fun window(dayStart: Instant): Pair<Instant, Instant> {
        val start = if (startMinute > endMinute) startMinute - MINUTES_PER_DAY else startMinute
        return dayStart.plusMinutes(start) to dayStart.plusMinutes(endMinute)
    }
}

/**
 * Big-data 0x27: [n giorni] poi per giorno [giorni fa][dayBytes][inizio u16][fine u16] + coppie [fase][minuti].
 * Layout da Gadgetbridge: NON ancora verificato con dati reali (serve una notte).
 */
object SleepCommand {
    const val TYPE = 0x27
    private const val REQUEST = 0xFF
    private const val SESSION_HEADER = 4

    fun encodeRequest(): ByteArray = byteArrayOf(REQUEST.toByte())

    fun decode(frame: BigFrame): List<SleepSession> {
        require(frame.typeId == TYPE) { "not a sleep frame: type %#04x".format(frame.typeId) }
        val p = frame.payload
        if (p.size < 2) return emptyList()
        var index = 1
        return (0 until p.u8(0)).map {
            require(index + 2 + SESSION_HEADER <= p.size) { "truncated sleep payload" }
            val daysAgo = p.u8(index)
            val dayBytes = p.u8(index + 1)
            val start = p.u16le(index + 2)
            val end = p.u16le(index + 4)
            val pairs = (dayBytes - SESSION_HEADER) / 2
            val first = index + 2 + SESSION_HEADER
            require(first + 2 * pairs <= p.size) { "truncated sleep stages" }
            val stages = (0 until pairs).map { SleepStage(SleepStageType.fromCode(p.u8(first + 2 * it)), p.u8(first + 2 * it + 1)) }
            index = first + 2 * pairs
            SleepSession(daysAgo, start, end, stages.filter { it.minutes > 0 })
        }
    }

    suspend fun read(client: BigDataClient): List<SleepSession> = decode(client.request(TYPE, encodeRequest()))
}
