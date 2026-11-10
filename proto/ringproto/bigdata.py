"""Frame del canale big-data V2: [0xBC][id][len LE u16][crc16 LE u16][payload].

crc16 = CRC-16/MODBUS (poly 0xA001 riflesso, init 0xFFFF) del solo payload. Le risposte piu' lunghe
della MTU arrivano spezzate su piu' notifiche e vanno ricomposte.
"""

import logging
import struct
from dataclasses import dataclass

log = logging.getLogger(__name__)

MAGIC = 0xBC
HEADER_LEN = 6
MAX_PAYLOAD = 8192
_CRC_POLY = 0xA001
_CRC_INIT = 0xFFFF


@dataclass(frozen=True)
class BigFrame:
    type_id: int
    payload: bytes


def crc16_modbus(data: bytes) -> int:
    """CRC-16/MODBUS."""
    crc = _CRC_INIT
    for byte in data:
        crc ^= byte
        for _ in range(8):
            crc = (crc >> 1) ^ _CRC_POLY if crc & 1 else crc >> 1
    return crc


def encode_frame(type_id: int, payload: bytes = b"") -> bytes:
    """Costruisce un frame big-data (richiesta o, nei test, risposta)."""
    return struct.pack("<BBHH", MAGIC, type_id, len(payload), crc16_modbus(payload)) + payload


class FrameAssembler:
    """Ricompone frame big-data da notifiche frammentate, scartando quelli corrotti."""

    def __init__(self) -> None:
        self._buf = bytearray()

    def feed(self, data: bytes) -> list[BigFrame]:
        """Aggiunge una notifica e restituisce i frame ora completi."""
        self._buf += data
        frames: list[BigFrame] = []
        while True:
            progressed, frame = self._step()
            if not progressed:
                return frames
            if frame is not None:
                frames.append(frame)

    def _step(self) -> tuple[bool, BigFrame | None]:
        """Un passo di parsing: (ha_consumato_byte, frame_valido_o_None)."""
        start = self._buf.find(MAGIC)
        if start < 0:
            self._buf.clear()
            return False, None
        del self._buf[:start]
        if len(self._buf) < HEADER_LEN:
            return False, None
        _, type_id, length, crc = struct.unpack_from("<BBHH", self._buf)
        if length > MAX_PAYLOAD:
            log.warning("implausible big-data length %d, resyncing", length)
            del self._buf[:1]
            return True, None
        total = HEADER_LEN + length
        if len(self._buf) < total:
            return False, None
        payload = bytes(self._buf[HEADER_LEN:total])
        del self._buf[:total]
        if crc16_modbus(payload) != crc:
            log.warning("dropping big-data frame %#04x with bad crc", type_id)
            return True, None
        return True, BigFrame(type_id, payload)
