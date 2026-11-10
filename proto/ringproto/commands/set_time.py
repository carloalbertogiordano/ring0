"""CMD 0x01 imposta data/ora; la risposta descrive le capability del ring.

Richiesta: [0x01][anno][mese][giorno][ora][min][sec][lingua], campi in BCD come in
tahnok/colmi_r02_client (colmi-docs dice "binario": discrepanza da chiarire sul ring).
Si imposta sempre UTC.
"""

from dataclasses import dataclass
from datetime import UTC, datetime

from ringproto.client import RingClient
from ringproto.packet import Packet

CMD_SET_TIME = 0x01
LANGUAGE_ENGLISH = 0x01
MIN_YEAR = 2000
MAX_YEAR = 2099
MAX_BCD = 99


def to_bcd(value: int) -> int:
    """Codifica 0..99 in BCD (due cifre decimali in un byte)."""
    if not 0 <= value <= MAX_BCD:
        msg = f"value not encodable as bcd: {value}"
        raise ValueError(msg)
    return ((value // 10) << 4) | (value % 10)


def encode_payload(when: datetime) -> bytes:
    """Payload (7 byte) di set time per un datetime con timezone, convertito in UTC."""
    if when.tzinfo is None:
        msg = "datetime must carry a timezone"
        raise ValueError(msg)
    utc = when.astimezone(UTC)
    if not MIN_YEAR <= utc.year <= MAX_YEAR:
        msg = f"year out of range: {utc.year}"
        raise ValueError(msg)
    fields = (utc.year - MIN_YEAR, utc.month, utc.day, utc.hour, utc.minute, utc.second)
    return bytes([to_bcd(f) for f in fields]) + bytes([LANGUAGE_ENGLISH])


@dataclass(frozen=True)
class Capabilities:
    """Capability dichiarate dal ring nella risposta a set time."""

    temperature: bool
    blood_oxygen: bool
    blood_pressure: bool
    manual_heart_rate: bool
    blood_sugar: bool
    stress: bool
    hrv: bool
    new_sleep_protocol: bool
    raw: bytes


def decode(pkt: Packet) -> Capabilities:
    """Decodifica la risposta di set time (offset nel payload, vedi docs/protocol.md)."""
    if pkt.cmd != CMD_SET_TIME:
        msg = f"not a set time packet: cmd {pkt.cmd:#04x}"
        raise ValueError(msg)
    p = pkt.payload
    return Capabilities(
        temperature=p[0] == 1,
        blood_oxygen=bool(p[3] & 0x02),
        blood_pressure=bool(p[3] & 0x04),
        manual_heart_rate=bool(p[11] & 0x01),
        blood_sugar=bool(p[11] & 0x80),
        stress=bool(p[13] & 0x10),
        hrv=bool(p[13] & 0x20),
        new_sleep_protocol=p[8] == 1,
        raw=p,
    )


async def set_time(client: RingClient, when: datetime | None = None) -> Capabilities:
    """Imposta l'ora del ring (default: adesso, UTC) e restituisce le sue capability."""
    pkt = await client.request(CMD_SET_TIME, encode_payload(when or datetime.now(UTC)))
    return decode(pkt)
