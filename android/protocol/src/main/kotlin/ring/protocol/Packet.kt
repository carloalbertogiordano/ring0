package ring.protocol

/** Frame V1 a 16 byte: [cmd][14 byte dati][crc], crc = somma dei primi 15 byte & 0xFF. */
object Packet {
    const val LENGTH = 16

    fun checksum(data: ByteArray, length: Int = data.size): Int {
        var sum = 0
        for (i in 0 until length) sum += data[i].toInt() and 0xFF
        return sum and 0xFF
    }
}
