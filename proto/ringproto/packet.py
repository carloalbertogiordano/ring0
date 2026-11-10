"""16-byte frame of the V1 UART channel: [cmd][14 data bytes][crc].

crc = sum of the first 15 bytes & 0xFF. Bit 7 of cmd is the error flag.
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
    """Sum of the bytes truncated to 8 bits."""
    return sum(data) & 0xFF


def build(cmd: int, payload: bytes = b"") -> bytes:
    """Builds a packet: cmd + payload (padded to 14 bytes) + crc."""
    if not 0 <= cmd <= MAX_CMD:
        msg = f"command out of range: {cmd}"
        raise ValueError(msg)
    if len(payload) > PAYLOAD_LEN:
        msg = f"payload too long: {len(payload)} > {PAYLOAD_LEN}"
        raise ValueError(msg)
    body = bytes([cmd]) + payload.ljust(PAYLOAD_LEN, b"\x00")
    return body + bytes([checksum(body)])


def parse(raw: bytes) -> Packet:
    """Validates length and crc, extracts cmd (without the error flag), payload and error flag."""
    if len(raw) != PACKET_LEN:
        msg = f"bad length: {len(raw)} != {PACKET_LEN}"
        raise PacketError(msg)
    if checksum(raw[:-1]) != raw[-1]:
        msg = f"bad checksum: expected {checksum(raw[:-1]):#04x}, got {raw[-1]:#04x}"
        raise PacketError(msg)
    return Packet(cmd=raw[0] & MAX_CMD, payload=raw[1:-1], is_error=bool(raw[0] & ERROR_FLAG))
