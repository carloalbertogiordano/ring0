"""Trasporto finto per testare il client senza BLE."""

import asyncio
from collections.abc import Callable

from ringproto.packet import build


class FakeTransport:
    """Registra le scritture e risponde con pacchetti predefiniti per comando."""

    def __init__(self) -> None:
        self.events: list[tuple[str, int]] = []
        self.sent: list[bytes] = []
        self.responses: dict[int, list[bytes]] = {}
        self.delay: float = 0.0
        self._handler: Callable[[bytes], None] | None = None

    def set_notify_handler(self, handler: Callable[[bytes], None]) -> None:
        self._handler = handler

    def notify(self, data: bytes) -> None:
        assert self._handler is not None
        self.events.append(("notify", data[0] & 0x7F))
        self._handler(data)

    async def write(self, data: bytes) -> None:
        self.sent.append(data)
        cmd = data[0]
        self.events.append(("write", cmd))
        for resp in self.responses.get(cmd, []):
            asyncio.get_running_loop().call_later(self.delay, self.notify, resp)


def response(cmd: int, payload: bytes = b"") -> bytes:
    return build(cmd, payload)


def error_response(cmd: int) -> bytes:
    raw = bytearray(build(cmd))
    raw[0] |= 0x80
    raw[15] = sum(raw[:15]) & 0xFF
    return bytes(raw)

