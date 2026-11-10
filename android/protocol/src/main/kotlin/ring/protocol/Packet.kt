package ring.protocol

/** Pacchetto malformato (lunghezza o checksum errati). */
class PacketException(message: String) : IllegalArgumentException(message)

/** Frame V1 a 16 byte: [cmd][14 byte dati][crc]; crc = somma dei primi 15 byte & 0xFF; bit 7 di cmd = errore. */
class Packet(val cmd: Int, val payload: ByteArray, val isError: Boolean = false) {
    override fun equals(other: Any?): Boolean =
        other is Packet && cmd == other.cmd && isError == other.isError && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * (31 * cmd + isError.hashCode()) + payload.contentHashCode()

    override fun toString(): String = "Packet(cmd=0x%02x, payload=%s, error=%s)".format(cmd, payload.toHex(), isError)

    companion object {
        const val LENGTH = 16
        const val PAYLOAD_LENGTH = LENGTH - 2
        const val MAX_CMD = 0x7F
        private const val ERROR_FLAG = 0x80

        fun checksum(data: ByteArray, length: Int = data.size): Int {
            var sum = 0
            for (i in 0 until length) sum += data.u8(i)
            return sum and 0xFF
        }

        fun build(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
            require(cmd in 0..MAX_CMD) { "command out of range: $cmd" }
            require(payload.size <= PAYLOAD_LENGTH) { "payload too long: ${payload.size} > $PAYLOAD_LENGTH" }
            val out = ByteArray(LENGTH)
            out[0] = cmd.toByte()
            payload.copyInto(out, 1)
            out[LENGTH - 1] = checksum(out, LENGTH - 1).toByte()
            return out
        }

        fun parse(raw: ByteArray): Packet {
            if (raw.size != LENGTH) throw PacketException("bad length: ${raw.size} != $LENGTH")
            val expected = checksum(raw, LENGTH - 1)
            if (expected != raw.u8(LENGTH - 1)) {
                throw PacketException("bad checksum: expected %#04x, got %#04x".format(expected, raw.u8(LENGTH - 1)))
            }
            return Packet(raw.u8(0) and MAX_CMD, raw.copyOfRange(1, LENGTH - 1), raw.u8(0) and ERROR_FLAG != 0)
        }
    }
}
