package ring.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class PacketTest {
    @Test
    fun `checksum of real battery response`() {
        val raw = "0307000000000000000000000000000a".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(raw[15].toInt() and 0xFF, Packet.checksum(raw, 15))
    }
}
