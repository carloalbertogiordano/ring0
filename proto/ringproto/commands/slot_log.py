"""CMD 0x37 (stress) e 0x39 (HRV): un valore ogni 30 minuti, per giorno.

Richiesta stress: nessun argomento. Richiesta HRV: [giorni fa, u32 LE] (da Gadgetbridge;
colmi-docs parla di un "index": da confermare con dati reali).
Risposta: header, poi blocchi: sub 1 = [?][12 valori], sub >= 2 = [13 valori]; 0 = nessuna misura.
Verificato solo su risposte vuote (0xFF): i valori vanno ancora confrontati con dati reali.
"""

import struct
from dataclasses import dataclass
from datetime import datetime, timedelta

from ringproto.client import RingClient
from ringproto.commands.split_array import MINUTES_PER_DAY, SUB_NO_DATA, is_complete
from ringproto.packet import Packet

CMD_STRESS = 0x37
CMD_HRV = 0x39
DEFAULT_INTERVAL = 30
SUB_FIRST = 1
FIRST_BLOCK_START = 2
NEXT_BLOCK_START = 1
BLOCK_END = 14


@dataclass(frozen=True)
class SlotLog:
    interval_minutes: int
    samples: tuple[int, ...]

    def readings(self, day_start: datetime) -> list[tuple[datetime, int]]:
        """Coppie (istante, valore) per gli slot con una misura, dato l'inizio giorno."""
        step = timedelta(minutes=self.interval_minutes)
        return [(day_start + i * step, v) for i, v in enumerate(self.samples) if v > 0]


def encode_stress_request() -> bytes:
    """Payload della richiesta stress (vuoto)."""
    return b""


def encode_hrv_request(days_ago: int) -> bytes:
    """Payload della richiesta HRV per il giorno `days_ago` (0 = oggi)."""
    if days_ago < 0:
        msg = f"days_ago must be >= 0: {days_ago}"
        raise ValueError(msg)
    return struct.pack("<I", days_ago)


def decode(packets: list[Packet]) -> SlotLog | None:
    """Ricompone i pacchetti; None se il ring non ha dati."""
    if packets[0].payload[0] == SUB_NO_DATA:
        return None
    interval = packets[0].payload[2] or DEFAULT_INTERVAL
    samples: list[int] = []
    for pkt in packets[1:]:
        start = FIRST_BLOCK_START if pkt.payload[0] == SUB_FIRST else NEXT_BLOCK_START
        samples.extend(pkt.payload[start:BLOCK_END])
    if not samples:
        return None
    return SlotLog(interval, tuple(samples[: MINUTES_PER_DAY // interval]))


async def read_stress_log(client: RingClient) -> SlotLog | None:
    """Scarica il log stress."""
    packets = await client.request_many(CMD_STRESS, encode_stress_request(), is_last=is_complete)
    return decode(packets)


async def read_hrv_log(client: RingClient, days_ago: int = 0) -> SlotLog | None:
    """Scarica il log HRV del giorno `days_ago`."""
    packets = await client.request_many(CMD_HRV, encode_hrv_request(days_ago), is_last=is_complete)
    return decode(packets)
