"""V1 UART channel client: one command at a time, with timeouts and notification routing."""

import asyncio
import logging
from collections.abc import Callable
from typing import Protocol

from ringproto.packet import Packet, PacketError, build, parse

log = logging.getLogger(__name__)

DEFAULT_TIMEOUT = 5.0


class Transport(Protocol):
    """Low-level channel to the ring (real BLE, or a fake in tests)."""

    def set_notify_handler(self, handler: Callable[[bytes], None]) -> None:  # noqa: V107
        """Registers the callback invoked for every notification received."""

    async def write(self, data: bytes) -> None:
        """Writes a packet to the ring's RX channel."""


class RingTimeoutError(TimeoutError):
    """The ring did not answer within the timeout."""


class RingCommandError(RuntimeError):
    """The ring answered with the error flag."""


class RingClient:
    """Sends commands in series and matches each response to the expected command."""

    def __init__(
        self,
        transport: Transport,
        default_timeout: float = DEFAULT_TIMEOUT,
        on_unsolicited: Callable[[Packet], None] | None = None,
    ) -> None:
        self._transport = transport
        self._default_timeout = default_timeout
        self._on_unsolicited = on_unsolicited
        self._lock = asyncio.Lock()
        self._pending: tuple[int, asyncio.Queue[Packet]] | None = None
        transport.set_notify_handler(self._handle_notification)

    async def request(self, cmd: int, payload: bytes = b"", timeout: float | None = None) -> Packet:
        """Sends a command and waits for a single response with the same id."""
        packets = await self.request_many(cmd, payload, is_last=lambda _: True, timeout=timeout)
        return packets[0]

    async def request_many(
        self,
        cmd: int,
        payload: bytes = b"",
        *,
        is_last: Callable[[list[Packet]], bool],
        timeout: float | None = None,
    ) -> list[Packet]:
        """Sends a command and collects responses until `is_last(packets_so_far)` is true.

        The timeout applies to each single expected packet.
        """
        wait = timeout or self._default_timeout
        packets: list[Packet] = []
        async with self._lock:
            queue: asyncio.Queue[Packet] = asyncio.Queue()
            self._pending = (cmd, queue)
            try:
                frame = build(cmd, payload)
                log.debug("tx %s", frame.hex())
                await self._transport.write(frame)
                while True:
                    packets.append(await self._next(queue, cmd, wait))
                    if is_last(packets):
                        return packets
            finally:
                self._pending = None

    @staticmethod
    async def _next(queue: asyncio.Queue[Packet], cmd: int, wait: float) -> Packet:
        try:
            async with asyncio.timeout(wait):
                pkt = await queue.get()
        except TimeoutError as exc:
            msg = f"no response to command {cmd:#04x}"
            raise RingTimeoutError(msg) from exc
        if pkt.is_error:
            msg = f"ring reported error for command {cmd:#04x}"
            raise RingCommandError(msg)
        return pkt

    def _handle_notification(self, data: bytes) -> None:
        log.debug("rx %s", data.hex())
        try:
            pkt = parse(data)
        except PacketError as exc:
            log.warning("dropping malformed notification %s: %s", data.hex(), exc)
            return
        if self._pending and self._pending[0] == pkt.cmd:
            self._pending[1].put_nowait(pkt)
        elif self._on_unsolicited:
            self._on_unsolicited(pkt)
