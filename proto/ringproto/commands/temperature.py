"""Big-data 0x25: skin temperature history (finger).

Request: 1-byte payload (2 = value used by Gadgetbridge; exact meaning still unclear).
Response: sequence of 50-byte blocks, oldest first up to today:
  [days ago][step minutes (30)][48 values]   -> °C = value / 10 + 20; 0 = no measurement.
A response without history is just `00 1e`.
"""

from dataclasses import dataclass
from datetime import datetime, timedelta

from ringproto.bigdata import BigFrame
from ringproto.bigdata_client import BigDataClient

TYPE_TEMPERATURE = 0x25
SLOTS_PER_DAY = 48
BLOCK_LEN = 2 + SLOTS_PER_DAY
EMPTY_PAYLOAD_LEN = 2
DEFAULT_DAYS = 2
MAX_DAYS = 255
BASE_CELSIUS = 20.0
CELSIUS_PER_UNIT = 0.1


@dataclass(frozen=True)
class TemperatureDay:
    days_ago: int
    interval_minutes: int
    samples: tuple[int, ...]

    def readings(self, day_start: datetime) -> list[tuple[datetime, float]]:
        """(instant, °C) pairs for slots with a measurement, given the (UTC) start of that day."""
        step = timedelta(minutes=self.interval_minutes)
        return [
            (day_start + i * step, raw * CELSIUS_PER_UNIT + BASE_CELSIUS)
            for i, raw in enumerate(self.samples)
            if raw > 0
        ]


def encode_request(days: int = DEFAULT_DAYS) -> bytes:
    """Request payload (1 byte)."""
    if not 1 <= days <= MAX_DAYS:
        msg = f"days must be 1..{MAX_DAYS}: {days}"
        raise ValueError(msg)
    return bytes([days])


def decode(frame: BigFrame) -> list[TemperatureDay]:
    """Decodes the response into daily blocks (empty list if there is no history)."""
    if frame.type_id != TYPE_TEMPERATURE:
        msg = f"not a temperature frame: type {frame.type_id:#04x}"
        raise ValueError(msg)
    payload = frame.payload
    if len(payload) <= EMPTY_PAYLOAD_LEN:
        return []
    if len(payload) % BLOCK_LEN:
        msg = f"truncated temperature payload: {len(payload)} bytes"
        raise ValueError(msg)
    return [
        TemperatureDay(payload[i], payload[i + 1], tuple(payload[i + 2 : i + BLOCK_LEN]))
        for i in range(0, len(payload), BLOCK_LEN)
    ]


async def read_temperature(client: BigDataClient, days: int = DEFAULT_DAYS) -> list[TemperatureDay]:
    """Scarica lo storico temperatura."""
    return decode(await client.request(TYPE_TEMPERATURE, encode_request(days)))
