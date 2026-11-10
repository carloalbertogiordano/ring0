package ring.protocol

import kotlinx.coroutines.delay

/** Fake transport: records writes and answers with canned notifications keyed by cmd or type id. */
class FakeTransport(private val keyIndex: Int = 0, private val delayMs: Long = 0) : Transport {
    val sent = mutableListOf<ByteArray>()
    val events = mutableListOf<String>()
    val responses = mutableMapOf<Int, List<ByteArray>>()
    private var handler: (ByteArray) -> Unit = {}

    override fun setNotifyHandler(handler: (ByteArray) -> Unit) {
        this.handler = handler
    }

    fun notify(data: ByteArray) {
        events += "notify"
        handler(data)
    }

    override suspend fun write(data: ByteArray) {
        sent += data
        events += "write"
        if (delayMs > 0) delay(delayMs)
        responses[data.u8(keyIndex) and 0x7F]?.forEach(::notify)
    }
}

fun chunks(frame: ByteArray, size: Int = 20): List<ByteArray> =
    frame.toList().chunked(size).map { it.toByteArray() }

fun packets(vararg hex: String): List<Packet> = hex.map { Packet.parse(it.hexToBytes()) }
