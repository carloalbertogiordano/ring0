"""Shared logic of V1 "split array" responses (HR log, stress, HRV).

`payload[0]` is the packet sub-type: 0 = header (`payload[1]` = total number of packets,
`payload[2]` = step in minutes), 1..N-1 = data blocks, 0xFF = no data.
"""

from ringproto.packet import Packet

SUB_HEADER = 0
SUB_NO_DATA = 0xFF
MINUTES_PER_DAY = 24 * 60


def is_complete(packets: list[Packet]) -> bool:
    """True when the response is complete: no data, or the last block announced by the header."""
    sub = packets[-1].payload[0]
    if sub == SUB_NO_DATA:
        return True
    first = packets[0]
    if first.payload[0] != SUB_HEADER:
        return False
    return sub >= first.payload[1] - 1
