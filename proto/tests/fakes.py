"""Fake transport to test the client without BLE."""

import asyncio
from collections.abc import Callable

from ringproto.packet import build


class FakeTransport:
    """Records writes and answers with canned packets per command."""

    def __init__(self) -> None:
        self.events: list[tuple[str, int]] = []
        self.sent: list[bytes] = []
        self.responses: dict[int, list[bytes]] = {}
        self.scripted: dict[int, list[list[bytes]]] = {}
        self.delay: float = 0.0
        self._handler: Callable[[bytes], None] | None = None

    def _key(self, data: bytes) -> int:
        return data[0] & 0x7F

    def set_notify_handler(self, handler: Callable[[bytes], None]) -> None:
        self._handler = handler

    def notify(self, data: bytes) -> None:
        assert self._handler is not None
        self.events.append(("notify", self._key(data)))
        self._handler(data)

    async def write(self, data: bytes) -> None:
        self.sent.append(data)
        cmd = self._key(data)
        self.events.append(("write", cmd))
        scripted = self.scripted.get(cmd)
        batch = scripted.pop(0) if scripted else self.responses.get(cmd, [])
        if batch:
            asyncio.get_running_loop().call_later(self.delay, self._deliver, batch)

    def _deliver(self, batch: list[bytes]) -> None:
        for data in batch:
            self.notify(data)


def response(cmd: int, payload: bytes = b"") -> bytes:
    return build(cmd, payload)


def error_response(cmd: int) -> bytes:
    raw = bytearray(build(cmd))
    raw[0] |= 0x80
    raw[15] = sum(raw[:15]) & 0xFF
    return bytes(raw)


class FakeBigTransport(FakeTransport):
    """Like FakeTransport but for the big-data channel: the key is the type id (byte 1)."""

    def _key(self, data: bytes) -> int:
        return data[1]

    def _deliver(self, batch: list[bytes]) -> None:
        for chunk in batch:
            self.notify(chunk)

    def notify(self, data: bytes) -> None:
        assert self._handler is not None
        self.events.append(("notify", data[1] if data[0] == 0xBC else -1))
        self._handler(data)


def chunks(frame: bytes, size: int = 20) -> list[bytes]:
    return [frame[i : i + size] for i in range(0, len(frame), size)]
