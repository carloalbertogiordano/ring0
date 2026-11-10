"""Big-data 0x25: storico temperatura cutanea (dito).

Richiesta: payload di 1 byte (2 = valore usato da Gadgetbridge; significato esatto da chiarire).
Risposta: sequenza di blocchi da 50 byte, dal piu' vecchio a oggi:
  [giorni fa][passo minuti (30)][48 valori]   -> °C = valore / 10 + 20; 0 = nessuna misura.
Una risposta senza storico e' solo `00 1e`.
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
        """Coppie (istante, °C) per gli slot con misura, dato l'inizio (UTC) di quel giorno."""
        step = timedelta(minutes=self.interval_minutes)
        return [
            (day_start + i * step, raw * CELSIUS_PER_UNIT + BASE_CELSIUS)
            for i, raw in enumerate(self.samples)
            if raw > 0
        ]


def encode_request(days: int = DEFAULT_DAYS) -> bytes:
    """Payload della richiesta (1 byte)."""
    if not 1 <= days <= MAX_DAYS:
        msg = f"days must be 1..{MAX_DAYS}: {days}"
        raise ValueError(msg)
    return bytes([days])


def decode(frame: BigFrame) -> list[TemperatureDay]:
    """Decodifica la risposta in blocchi giornalieri (lista vuota se non c'e' storico)."""
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
