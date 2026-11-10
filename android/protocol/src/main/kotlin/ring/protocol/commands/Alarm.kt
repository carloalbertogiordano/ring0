package ring.protocol.commands

import ring.protocol.BigDataClient
import ring.protocol.BigFrame
import ring.protocol.u16le
import ring.protocol.u8

/**
 * One alarm as the ring stores it. The ring evaluates it on its own clock, which this project sets to UTC, so
 * `minutes` and `weekdays` are in ring (UTC) time. `weekdays`: bit 0 = Sunday ... bit 6 = Saturday; 0 = one-shot.
 */
data class RingAlarm(val enabled: Boolean, val minutes: Int, val weekdays: Int, val name: String = "")

/**
 * Big-data 0x2C. Read: payload [0x01]. Write: [0x02][count][records]; the whole list is sent every time and an
 * alarm is deleted by leaving it out. Record: [length][flags][minutes u16 LE][name UTF-8], flags bit 7 = enabled and
 * bits 0..6 = weekday mask. Layout from Gadgetbridge.
 */
object AlarmCommand {
    const val TYPE = 0x2C
    private const val OP_READ = 1
    private const val OP_WRITE = 2
    private const val FLAG_ENABLED = 0x80
    private const val DAY_MASK = 0x7F
    private const val RECORD_HEADER = 4
    private const val MAX_NAME_BYTES = 28
    private const val MINUTES_PER_DAY = 1440

    fun encodeReadRequest(): ByteArray = byteArrayOf(OP_READ.toByte())

    fun encodeWritePayload(alarms: List<RingAlarm>): ByteArray {
        val records = alarms.map(::encodeRecord)
        return byteArrayOf(OP_WRITE.toByte(), records.size.toByte()) + records.fold(ByteArray(0)) { acc, r -> acc + r }
    }

    private fun encodeRecord(alarm: RingAlarm): ByteArray {
        require(alarm.minutes in 0 until MINUTES_PER_DAY) { "minutes out of range: ${alarm.minutes}" }
        require(alarm.weekdays in 0..DAY_MASK) { "weekday mask out of range: ${alarm.weekdays}" }
        val name = truncateUtf8(alarm.name, MAX_NAME_BYTES)
        val flags = alarm.weekdays or (if (alarm.enabled) FLAG_ENABLED else 0)
        return byteArrayOf(
            (RECORD_HEADER + name.size).toByte(), flags.toByte(), alarm.minutes.toByte(), (alarm.minutes shr 8).toByte(),
        ) + name
    }

    /** The alarm list of a read reply, or null if the frame is just the acknowledgement of a write. */
    fun decode(frame: BigFrame): List<RingAlarm>? {
        require(frame.typeId == TYPE) { "not an alarm frame: type %#04x".format(frame.typeId) }
        val p = frame.payload
        if (p.size < 2 || p.u8(0) != OP_READ) return null
        var index = 2
        return (0 until p.u8(1)).map {
            require(index + RECORD_HEADER <= p.size) { "truncated alarm list" }
            val length = p.u8(index)
            require(length >= RECORD_HEADER && index + length <= p.size) { "bad alarm record length: $length" }
            val flags = p.u8(index + 1)
            val name = String(p, index + RECORD_HEADER, length - RECORD_HEADER, Charsets.UTF_8)
            RingAlarm(flags and FLAG_ENABLED != 0, p.u16le(index + 2), flags and DAY_MASK, name).also { index += length }
        }
    }

    private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        var out = text.toByteArray(Charsets.UTF_8)
        var end = text.length
        while (out.size > maxBytes && end > 0) out = text.substring(0, --end).toByteArray(Charsets.UTF_8)
        return out
    }

    suspend fun read(client: BigDataClient): List<RingAlarm> =
        decode(client.request(TYPE, encodeReadRequest())) ?: emptyList()

    /** Writes the full list and reads it back, returning what the ring now holds. */
    suspend fun write(client: BigDataClient, alarms: List<RingAlarm>): List<RingAlarm> {
        client.request(TYPE, encodeWritePayload(alarms))
        return read(client)
    }
}

/** CMD 0x72 push message: makes the ring vibrate (and, on models with a screen, show the text). */
object PushMessageCommand {
    const val CMD = 0x72
    const val TYPE_SMS = 0x01
    private const val CHUNK = 11
    private const val MAX_CHUNKS = 4

    /** Payloads, one per chunk: [type][total chunks][chunk index, 1-based][up to 11 bytes of text]. */
    fun encode(type: Int, text: String): List<ByteArray> {
        val bytes = text.toByteArray(Charsets.UTF_8).take(CHUNK * MAX_CHUNKS).toByteArray()
        val chunks = maxOf(1, (bytes.size + CHUNK - 1) / CHUNK)
        return (0 until chunks).map { i ->
            byteArrayOf(type.toByte(), chunks.toByte(), (i + 1).toByte()) + bytes.copyOfRange(minOf(i * CHUNK, bytes.size), minOf((i + 1) * CHUNK, bytes.size))
        }
    }

    suspend fun send(client: ring.protocol.RingClient, text: String, type: Int = TYPE_SMS) {
        for (payload in encode(type, text)) client.send(CMD, payload)
    }
}
