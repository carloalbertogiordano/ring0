package ring.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClientTest {
    private val batteryRsp = "0307000000000000000000000000000a".hexToBytes()

    @Test fun `request returns matching response`() = runTest {
        val tr = FakeTransport().apply { responses[0x03] = listOf(batteryRsp) }
        val pkt = RingClient(tr).request(0x03)
        assertContentEquals(Packet.build(0x03), tr.sent.single())
        assertEquals(7, pkt.payload[0].toInt())
    }

    @Test fun `request times out when ring is silent`() = runTest {
        assertFailsWith<RingTimeoutException> { RingClient(FakeTransport(), defaultTimeoutMs = 50).request(0x03) }
    }

    @Test fun `request raises on error flag`() = runTest {
        val err = Packet.build(0x03).also {
            it[0] = 0x83.toByte()
            it[15] = (Packet.checksum(it, 15)).toByte()
        }
        val tr = FakeTransport().apply { responses[0x03] = listOf(err) }
        assertFailsWith<RingCommandException> { RingClient(tr).request(0x03) }
    }

    @Test fun `unsolicited and malformed notifications do not resolve the request`() = runTest {
        val seen = mutableListOf<Packet>()
        val bad = batteryRsp.copyOf().also { it[15] = 0 }
        val tr = FakeTransport().apply { responses[0x03] = listOf(Packet.build(0x73, byteArrayOf(0x0C, 55)), bad, batteryRsp) }
        val pkt = RingClient(tr, onUnsolicited = { seen += it }).request(0x03)
        assertEquals(0x03, pkt.cmd)
        assertEquals(listOf(0x73), seen.map { it.cmd })
    }

    @Test fun `requestMany collects until predicate`() = runTest {
        val tr = FakeTransport().apply { responses[0x15] = (0..3).map { Packet.build(0x15, byteArrayOf(it.toByte())) } }
        val packets = RingClient(tr).requestMany(0x15) { it.size >= 4 }
        assertEquals(listOf(0, 1, 2, 3), packets.map { it.payload[0].toInt() })
    }

    @Test fun `requests are serialized`() = runTest {
        val tr = FakeTransport(delayMs = 20).apply {
            responses[0x03] = listOf(batteryRsp)
            responses[0x01] = listOf(Packet.build(0x01))
        }
        val client = RingClient(tr)
        val a = async { client.request(0x03) }
        val b = async { client.request(0x01) }
        a.await()
        b.await()
        assertEquals(listOf("write", "notify", "write", "notify"), tr.events)
    }

    @Test fun `big data client reassembles fragmented response`() = runTest {
        val payload = ByteArray(100) { it.toByte() }
        val tr = FakeTransport(keyIndex = 1).apply { responses[0x25] = chunks(BigData.encodeFrame(0x25, payload)) }
        val frame = BigDataClient(tr).request(0x25)
        assertContentEquals(payload, frame.payload)
        assertContentEquals(BigData.encodeFrame(0x25), tr.sent.single())
    }

    @Test fun `big data client times out and forwards other types`() = runTest {
        assertFailsWith<RingTimeoutException> { BigDataClient(FakeTransport(keyIndex = 1), defaultTimeoutMs = 50).request(0x25) }
        val seen = mutableListOf<BigFrame>()
        val tr = FakeTransport(keyIndex = 1).apply {
            responses[0x25] = listOf(BigData.encodeFrame(0x27, byteArrayOf(9)), BigData.encodeFrame(0x25, byteArrayOf(1)))
        }
        assertEquals(0x25, BigDataClient(tr, onUnsolicited = { seen += it }).request(0x25).typeId)
        assertEquals(listOf(0x27), seen.map { it.typeId })
    }
}
