package ring.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PacketTest {
    private val batteryReq = "03000000000000000000000000000003".hexToBytes()
    private val batteryRsp = "0307000000000000000000000000000a".hexToBytes()

    @Test fun `build battery request matches capture`() = assertContentEquals(batteryReq, Packet.build(0x03))

    @Test fun `build pads payload and appends crc`() {
        val raw = Packet.build(0x01, byteArrayOf(26, 11, 10, 15, 30, 0))
        assertEquals(Packet.LENGTH, raw.size)
        assertEquals(Packet.checksum(raw, 15), raw[15].toInt() and 0xFF)
    }

    @Test fun `build rejects oversized payload and bad command`() {
        assertFailsWith<IllegalArgumentException> { Packet.build(0x01, ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { Packet.build(0x80) }
    }

    @Test fun `parse real battery response`() {
        val pkt = Packet.parse(batteryRsp)
        assertEquals(0x03, pkt.cmd)
        assertEquals(7, pkt.payload[0].toInt())
        assertTrue(!pkt.isError)
    }

    @Test fun `parse flags error bit and clears it from cmd`() {
        val body = ByteArray(16).also { it[0] = 0x83.toByte() }
        body[15] = Packet.checksum(body, 15).toByte()
        val pkt = Packet.parse(body)
        assertEquals(0x03, pkt.cmd)
        assertTrue(pkt.isError)
    }

    @Test fun `parse rejects wrong length and bad checksum`() {
        assertFailsWith<PacketException> { Packet.parse(batteryRsp.copyOf(15)) }
        assertFailsWith<PacketException> { Packet.parse(batteryRsp.copyOf().also { it[15] = 0 }) }
    }
}
