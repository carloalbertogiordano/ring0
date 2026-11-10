"""CMD 0x03 batteria. Risposta: [0x03][percentuale][in_carica][...]."""

from dataclasses import dataclass

from ringproto.client import RingClient
from ringproto.packet import Packet

CMD_BATTERY = 0x03
MAX_LEVEL = 100


@dataclass(frozen=True)
class Battery:
    level: int
    charging: bool


def decode(pkt: Packet) -> Battery:
    """Decodifica la risposta batteria."""
    if pkt.cmd != CMD_BATTERY:
        msg = f"not a battery packet: cmd {pkt.cmd:#04x}"
        raise ValueError(msg)
    level = pkt.payload[0]
    if level > MAX_LEVEL:
        msg = f"impossible battery level: {level}"
        raise ValueError(msg)
    return Battery(level=level, charging=bool(pkt.payload[1]))


async def read_battery(client: RingClient) -> Battery:
    """Legge livello e stato di carica dal ring."""
    return decode(await client.request(CMD_BATTERY))
