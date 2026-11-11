package ring.protocol

import kotlinx.coroutines.test.runTest
import ring.protocol.commands.RawSample
import ring.protocol.commands.RawStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RawStreamTest {
    private fun frame(seq: Int, a: Int, b: Int, x: Int = 0, y: Int = 0, z: Int = 0, status: Int = 0x60, type: Int = 13): ByteArray {
        fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        return Packet.build(0x69, byteArrayOf(type.toByte(), 0, seq.toByte(), status.toByte()) + le(a) + le(b) + le(x) + le(y) + le(z))
    }

    private fun packet(seq: Int, a: Int, b: Int, x: Int = 0, y: Int = 0, z: Int = 0, status: Int = 0x60, type: Int = 13): Packet =
        Packet.parse(frame(seq, a, b, x, y, z, status, type))

    @Test fun `start payload carries the duration in seconds, little endian`() {
        assertContentEquals(byteArrayOf(13, 0x78, 0), RawStream.encodeStart(120))
        assertContentEquals(byteArrayOf(13, 0x2C, 1), RawStream.encodeStart(300))
        assertFailsWith<IllegalArgumentException> { RawStream.encodeStart(9) }
        assertFailsWith<IllegalArgumentException> { RawStream.encodeStart(70_000) }
    }

    @Test fun `a packet decodes into the two optical values and the three accelerometer axes`() {
        val s = RawStream.decode(packet(seq = 5, a = 0xA105, b = 0x95B4, x = -245, y = -46, z = -70, status = 0x61))
        assertEquals(RawSample(seq = 5, status = 0x61, a = 0xA105, b = 0x95B4, x = -245, y = -46, z = -70), s)
    }

    @Test fun `other types and the all zero start packet are not samples`() {
        assertEquals(null, RawStream.decode(packet(0, 1, 2, type = 14)))
        assertEquals(null, RawStream.decode(packet(0, 0, 0, x = 0, y = 0, z = 0, status = 0)))
    }

    @Test fun `record collects samples, drops the empty start packet and ends the session properly`() = runTest {
        val packets = listOf(frame(0, 0, 0, status = 0)) + (1..30).map { frame(it, 40_000 + it, 30_000 + it, x = -3, y = 4, z = 5) }
        val tr = FakeTransport().apply { responses[0x69] = packets }
        val seen = mutableListOf<RawSample>()
        val samples = RawStream.record(RingClient(tr), seconds = 10) { seen += it }
        assertEquals(30, samples.size)
        assertEquals((1..30).toList(), samples.map { it.seq })
        assertEquals(samples, seen)
        assertContentEquals(byteArrayOf(0x69, 13, 10, 0), tr.sent.first().copyOfRange(0, 4))
        assertContentEquals(byteArrayOf(0x69, 6, 4), tr.sent.last().copyOfRange(0, 3))
    }

    @Test fun `record stops by itself once the expected number of packets has arrived`() = runTest {
        val packets = (1..400).map { frame(it % 256, 40_000, 30_000, x = 1) }
        val tr = FakeTransport().apply { responses[0x69] = packets }
        val samples = RawStream.record(RingClient(tr), seconds = 10)
        // 10 s at 25 Hz plus a little slack, never the whole 400
        assertEquals(true, samples.size in 250..300)
    }
}
