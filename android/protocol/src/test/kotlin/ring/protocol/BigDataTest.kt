package ring.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BigDataTest {
    private fun frame(payload: ByteArray, type: Int = 0x25) = BigData.encodeFrame(type, payload)

    @Test fun `crc16 modbus standard check value`() = assertEquals(0x4B37, BigData.crc16Modbus("123456789".toByteArray()))

    @Test fun `crc16 of empty is initial value`() = assertEquals(0xFFFF, BigData.crc16Modbus(ByteArray(0)))

    @Test fun `encode matches known requests`() {
        assertContentEquals("bc2a0100ff00ff".hexToBytes(), BigData.encodeFrame(0x2A, byteArrayOf(0xFF.toByte())))
        assertContentEquals("bc270100ff00ff".hexToBytes(), BigData.encodeFrame(0x27, byteArrayOf(0xFF.toByte())))
        assertContentEquals("bc2501003e8102".hexToBytes(), BigData.encodeFrame(0x25, byteArrayOf(2)))
    }

    @Test fun `assembler joins fragments`() {
        val payload = ByteArray(60) { it.toByte() }
        val asm = FrameAssembler()
        val results = chunks(frame(payload)).map { asm.feed(it) }
        assertEquals(List(results.size - 1) { emptyList() }, results.dropLast(1))
        assertEquals(listOf(BigFrame(0x25, payload)), results.last())
    }

    @Test fun `assembler handles back to back frames garbage and bad crc`() {
        val asm = FrameAssembler()
        assertEquals(
            listOf(BigFrame(0x25, byteArrayOf(1)), BigFrame(0x27, byteArrayOf(2, 2))),
            asm.feed(frame(byteArrayOf(1)) + frame(byteArrayOf(2, 2), 0x27)),
        )
        val bad = frame("corrupt".toByteArray()).also { it[it.size - 1] = (it.last().toInt() xor 0xFF).toByte() }
        assertEquals(emptyList(), asm.feed(bad))
        assertEquals(listOf(BigFrame(0x25, "ok".toByteArray())), asm.feed(byteArrayOf(0, 0x11) + frame("ok".toByteArray())))
    }

    @Test fun `assembler resyncs on implausible length`() {
        val bogus = byteArrayOf(0xBC.toByte(), 0x25, 0xFF.toByte(), 0xFF.toByte(), 0, 0)
        assertEquals(listOf(BigFrame(0x25, "after".toByteArray())), FrameAssembler().feed(bogus + frame("after".toByteArray())))
    }

    @Test fun `assembler accepts real ring frames`() {
        val asm = FrameAssembler()
        assertEquals(listOf(BigFrame(0x25, "001e".hexToBytes())), asm.feed("bc25020081b8001e".hexToBytes()))
        assertEquals(listOf(BigFrame(0x27, byteArrayOf(0))), asm.feed("bc270100bf4000".hexToBytes()))
        assertEquals(listOf(BigFrame(0x2A, ByteArray(0))), asm.feed("bc2a0000ffff".hexToBytes()))
    }
}
