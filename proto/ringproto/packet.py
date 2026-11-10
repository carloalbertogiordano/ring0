"""Frame a 16 byte del canale UART V1: [cmd][14 byte dati][crc].

crc = somma dei primi 15 byte & 0xFF. Il bit 7 di cmd e' il flag di errore.
"""

from dataclasses import dataclass

PACKET_LEN = 16
PAYLOAD_LEN = PACKET_LEN - 2
ERROR_FLAG = 0x80
MAX_CMD = 0x7F


class PacketError(ValueError):
    """Pacchetto malformato (lunghezza o checksum errati)."""


@dataclass(frozen=True)
class Packet:
    cmd: int
    payload: bytes
    is_error: bool


def checksum(data: bytes) -> int:
    """Somma dei byte troncata a 8 bit."""
    return sum(data) & 0xFF


def build(cmd: int, payload: bytes = b"") -> bytes:
    """Costruisce un pacchetto: cmd + payload (padding a 14 byte) + crc."""
    if not 0 <= cmd <= MAX_CMD:
        msg = f"command out of range: {cmd}"
        raise ValueError(msg)
    if len(payload) > PAYLOAD_LEN:
        msg = f"payload too long: {len(payload)} > {PAYLOAD_LEN}"
        raise ValueError(msg)
    body = bytes([cmd]) + payload.ljust(PAYLOAD_LEN, b"\x00")
    return body + bytes([checksum(body)])


def parse(raw: bytes) -> Packet:
    """Valida lunghezza e crc, estrae cmd (senza flag errore), payload e flag errore."""
    if len(raw) != PACKET_LEN:
        msg = f"bad length: {len(raw)} != {PACKET_LEN}"
        raise PacketError(msg)
    if checksum(raw[:-1]) != raw[-1]:
        msg = f"bad checksum: expected {checksum(raw[:-1]):#04x}, got {raw[-1]:#04x}"
        raise PacketError(msg)
    return Packet(cmd=raw[0] & MAX_CMD, payload=raw[1:-1], is_error=bool(raw[0] & ERROR_FLAG))
