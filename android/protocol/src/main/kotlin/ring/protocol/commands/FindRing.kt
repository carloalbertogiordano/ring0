package ring.protocol.commands

import ring.protocol.RingClient

/** CMD 0x50 "find device" (green LED for ~10 s) and CMD 0x10 "blink twice". Both are harmless fire-and-forget commands. */
object FindRingCommand {
    const val CMD_FIND = 0x50
    const val CMD_BLINK = 0x10
    private val FIND_PAYLOAD = byteArrayOf(0x55, 0xAA.toByte())

    fun encodeFindPayload(): ByteArray = FIND_PAYLOAD.copyOf()

    suspend fun find(client: RingClient) = client.send(CMD_FIND, encodeFindPayload())

    suspend fun blink(client: RingClient) = client.send(CMD_BLINK)
}
