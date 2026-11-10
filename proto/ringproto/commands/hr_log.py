"""CMD 0x15: heart rate log of one day (multi-packet response).

Request: [0x15][unix timestamp LE u32 of the day's midnight].
Response, by sub-type (payload[0]):
  0     [size][interval_min]          number of packets and sample step
  1     [timestamp LE u32][9 values]  first block
  >= 2  [13 values]                   following blocks
  0xFF                                no data
Value 0 = no measurement in that slot.
"""

import struct
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from ringproto.client import RingClient
from ringproto.commands.split_array import MINUTES_PER_DAY, SUB_NO_DATA, is_complete
from ringproto.packet import Packet

CMD_HR_LOG = 0x15
SUB_FIRST = 1
FIRST_BLOCK = 9
NEXT_BLOCK = 13


@dataclass(frozen=True)
class HeartRateLog:
    day_start: datetime
    interval_minutes: int
    samples: tuple[int, ...]

    def readings(self) -> list[tuple[datetime, int]]:
        """(instant, bpm) pairs for the slots with a valid measurement."""
        step = timedelta(minutes=self.interval_minutes)
        return [(self.day_start + i * step, bpm) for i, bpm in enumerate(self.samples) if bpm > 0]


def encode_request(day_start: datetime) -> bytes:
    """Request payload: unix timestamp (LE, u32) of the start of the day."""
    if day_start.tzinfo is None:
        msg = "datetime must carry a timezone"
        raise ValueError(msg)
    return struct.pack("<I", int(day_start.timestamp()))


def decode(packets: list[Packet]) -> HeartRateLog | None:
    """Reassembles the packets into a log; None if the ring has no data for that day."""
    if packets[0].payload[0] == SUB_NO_DATA:
        return None
    header = packets[0].payload
    interval = header[2]
    day_start: datetime | None = None
    samples: list[int] = []
    for pkt in packets[1:]:
        sub, body = pkt.payload[0], pkt.payload[1:]
        if sub == SUB_FIRST:
            day_start = datetime.fromtimestamp(struct.unpack("<I", body[:4])[0], UTC)
            samples.extend(body[4 : 4 + FIRST_BLOCK])
        else:
            samples.extend(body[:NEXT_BLOCK])
    if day_start is None:
        return None
    slots = MINUTES_PER_DAY // interval
    return HeartRateLog(day_start, interval, tuple(samples[:slots]))


async def read_hr_log(client: RingClient, day_start: datetime) -> HeartRateLog | None:
    """Downloads the HR log of the day starting at `day_start`."""
    packets = await client.request_many(CMD_HR_LOG, encode_request(day_start), is_last=is_complete)
    return decode(packets)
