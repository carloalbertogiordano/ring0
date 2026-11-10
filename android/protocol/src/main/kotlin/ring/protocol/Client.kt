package ring.protocol

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Low-level channel to the ring (real BLE, or a fake in tests). */
interface Transport {
    suspend fun write(data: ByteArray)

    fun setNotifyHandler(handler: (ByteArray) -> Unit)
}

class RingTimeoutException(message: String) : RuntimeException(message)

class RingCommandException(message: String) : RuntimeException(message)

const val DEFAULT_TIMEOUT_MS = 5_000L

/** V1 channel client: one command at a time, with timeouts and notification routing. */
class RingClient(
    private val transport: Transport,
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onUnsolicited: (Packet) -> Unit = {},
) {
    private val mutex = Mutex()

    @Volatile private var pending: Pair<Int, Channel<Packet>>? = null

    init {
        transport.setNotifyHandler(::handleNotification)
    }

    suspend fun request(cmd: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long? = null): Packet =
        requestMany(cmd, payload, timeoutMs) { true }.first()

    /** Collects responses until `isLast(packetsSoFar)` is true; the timeout applies to each packet. */
    suspend fun requestMany(
        cmd: Int,
        payload: ByteArray = ByteArray(0),
        timeoutMs: Long? = null,
        isLast: (List<Packet>) -> Boolean,
    ): List<Packet> = mutex.withLock {
        val queue = Channel<Packet>(Channel.UNLIMITED)
        pending = cmd to queue
        try {
            transport.write(Packet.build(cmd, payload))
            val packets = mutableListOf<Packet>()
            do {
                packets += next(queue, cmd, timeoutMs ?: defaultTimeoutMs)
            } while (!isLast(packets))
            packets
        } finally {
            pending = null
        }
    }

    /** Writes a command without waiting for a reply (e.g. real-time stop). */
    suspend fun send(cmd: Int, payload: ByteArray = ByteArray(0)) {
        mutex.withLock { transport.write(Packet.build(cmd, payload)) }
    }

    private suspend fun next(queue: Channel<Packet>, cmd: Int, timeoutMs: Long): Packet {
        val pkt = try {
            withTimeout(timeoutMs) { queue.receive() }
        } catch (e: TimeoutCancellationException) {
            throw RingTimeoutException("no response to command %#04x".format(cmd)).apply { initCause(e) }
        }
        if (pkt.isError) throw RingCommandException("ring reported error for command %#04x".format(cmd))
        return pkt
    }

    private fun handleNotification(data: ByteArray) {
        val pkt = try {
            Packet.parse(data)
        } catch (_: PacketException) {
            return
        }
        val current = pending
        if (current != null && current.first == pkt.cmd) current.second.trySend(pkt) else onUnsolicited(pkt)
    }
}

/** Big-data V2 channel client: one request at a time, reassembled and validated response. */
class BigDataClient(
    private val transport: Transport,
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val onUnsolicited: (BigFrame) -> Unit = {},
) {
    private val mutex = Mutex()
    private var assembler = FrameAssembler()

    @Volatile private var pending: Pair<Int, Channel<BigFrame>>? = null

    init {
        transport.setNotifyHandler(::handleNotification)
    }

    suspend fun request(typeId: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long? = null): BigFrame =
        mutex.withLock {
            synchronized(this) { assembler = FrameAssembler() }
            val queue = Channel<BigFrame>(Channel.UNLIMITED)
            pending = typeId to queue
            try {
                transport.write(BigData.encodeFrame(typeId, payload))
                try {
                    withTimeout(timeoutMs ?: defaultTimeoutMs) { queue.receive() }
                } catch (e: TimeoutCancellationException) {
                    throw RingTimeoutException("no big-data response for type %#04x".format(typeId)).apply { initCause(e) }
                }
            } finally {
                pending = null
            }
        }

    private fun handleNotification(data: ByteArray) {
        val frames = synchronized(this) { assembler.feed(data) }
        for (frame in frames) {
            val current = pending
            if (current != null && current.first == frame.typeId) current.second.trySend(frame) else onUnsolicited(frame)
        }
    }
}
