package ring.protocol.commands

import ring.protocol.Packet
import ring.protocol.u8

/** Shared logic for V1 "split array" responses (HR log, stress, HRV). */
internal object SplitArray {
    const val SUB_HEADER = 0
    const val SUB_NO_DATA = 0xFF

    /** True when the response is complete: no data, or the last block announced by the header. */
    fun isComplete(packets: List<Packet>): Boolean {
        val sub = packets.last().payload.u8(0)
        if (sub == SUB_NO_DATA) return true
        val first = packets.first()
        if (first.payload.u8(0) != SUB_HEADER) return false
        return sub >= first.payload.u8(1) - 1
    }
}
