package ring.protocol

/** Frame big-data V2: [0xBC][id][len LE u16][crc16 LE u16][payload]; crc = CRC-16/MODBUS of the payload. */
class BigFrame(val typeId: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is BigFrame && typeId == other.typeId && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * typeId + payload.contentHashCode()

    override fun toString(): String = "BigFrame(type=0x%02x, payload=%s)".format(typeId, payload.toHex())
}

object BigData {
    const val MAGIC = 0xBC
    const val HEADER_LENGTH = 6
    const val MAX_PAYLOAD = 8192
    private const val POLY = 0xA001
    private const val INIT = 0xFFFF

    fun crc16Modbus(data: ByteArray): Int {
        var crc = INIT
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor POLY else crc ushr 1 }
        }
        return crc
    }

    fun encodeFrame(typeId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val crc = crc16Modbus(payload)
        val header = byteArrayOf(
            MAGIC.toByte(),
            typeId.toByte(),
            payload.size.toByte(),
            (payload.size shr 8).toByte(),
            crc.toByte(),
            (crc shr 8).toByte(),
        )
        return header + payload
    }
}

/** Ricompone frame big-data da notifiche frammentate, scartando quelli corrotti. */
class FrameAssembler {
    private var buffer = ByteArray(0)

    fun feed(data: ByteArray): List<BigFrame> {
        buffer += data
        val frames = mutableListOf<BigFrame>()
        while (true) {
            val step = step() ?: break
            step.frame?.let(frames::add)
        }
        return frames
    }

    private class Step(val frame: BigFrame?)

    /** null = more data needed; otherwise one parsing step (with a valid frame, or a discarded one). */
    private fun step(): Step? {
        val start = buffer.indexOfFirst { it.toInt() and 0xFF == BigData.MAGIC }
        buffer = if (start < 0) ByteArray(0) else buffer.copyOfRange(start, buffer.size)
        if (buffer.size < BigData.HEADER_LENGTH) return null
        val length = buffer.u16le(2)
        val total = BigData.HEADER_LENGTH + length
        return when {
            length > BigData.MAX_PAYLOAD -> skipByte()
            buffer.size < total -> null
            else -> takeFrame(total)
        }
    }

    private fun skipByte(): Step {
        buffer = buffer.copyOfRange(1, buffer.size)
        return Step(null)
    }

    private fun takeFrame(total: Int): Step {
        val payload = buffer.copyOfRange(BigData.HEADER_LENGTH, total)
        val type = buffer.u8(1)
        val crc = buffer.u16le(4)
        buffer = buffer.copyOfRange(total, buffer.size)
        return Step(if (BigData.crc16Modbus(payload) == crc) BigFrame(type, payload) else null)
    }
}
