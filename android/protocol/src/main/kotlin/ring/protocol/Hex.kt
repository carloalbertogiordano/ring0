package ring.protocol

fun String.hexToBytes(): ByteArray {
    val clean = filterNot { it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

internal fun ByteArray.u32le(i: Int): Long =
    u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)
