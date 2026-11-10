"""Logica comune delle risposte V1 "a array spezzato" (log HR, stress, HRV).

`payload[0]` e' il sotto-tipo del pacchetto: 0 = header (`payload[1]` = numero totale di pacchetti,
`payload[2]` = passo in minuti), 1..N-1 = blocchi di dati, 0xFF = nessun dato.
"""

from ringproto.packet import Packet

SUB_HEADER = 0
SUB_NO_DATA = 0xFF
MINUTES_PER_DAY = 24 * 60


def is_complete(packets: list[Packet]) -> bool:
    """Vero quando la risposta e' completa: nessun dato, o ultimo blocco annunciato dall'header."""
    sub = packets[-1].payload[0]
    if sub == SUB_NO_DATA:
        return True
    first = packets[0]
    if first.payload[0] != SUB_HEADER:
        return False
    return sub >= first.payload[1] - 1
