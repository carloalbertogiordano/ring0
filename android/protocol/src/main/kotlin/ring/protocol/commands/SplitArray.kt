package ring.protocol.commands

import ring.protocol.Packet
import ring.protocol.u8

/** Logica comune delle risposte V1 "a array spezzato" (log HR, stress, HRV). */
internal object SplitArray {
    const val SUB_HEADER = 0
    const val SUB_NO_DATA = 0xFF

    /** Vero quando la risposta e' completa: nessun dato, o ultimo blocco annunciato dall'header. */
    fun isComplete(packets: List<Packet>): Boolean {
        val sub = packets.last().payload.u8(0)
        if (sub == SUB_NO_DATA) return true
        val first = packets.first()
        if (first.payload.u8(0) != SUB_HEADER) return false
        return sub >= first.payload.u8(1) - 1
    }
}
