"""Client del canale UART V1: un comando alla volta, con timeout e routing delle notifiche."""

import asyncio
import logging
from collections.abc import Callable
from typing import Protocol

from ringproto.packet import Packet, PacketError, build, parse

log = logging.getLogger(__name__)

DEFAULT_TIMEOUT = 5.0


class Transport(Protocol):
    """Canale di basso livello verso il ring (BLE reale o finto nei test)."""

    def set_notify_handler(self, handler: Callable[[bytes], None]) -> None:  # noqa: V107
        """Registra il callback invocato per ogni notifica ricevuta."""

    async def write(self, data: bytes) -> None:
        """Scrive un pacchetto sul canale RX del ring."""


class RingTimeoutError(TimeoutError):
    """Il ring non ha risposto entro il timeout."""


class RingCommandError(RuntimeError):
    """Il ring ha risposto con il flag di errore."""


class RingClient:
    """Invia comandi in serie e associa ogni risposta al comando atteso."""

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
        """Invia un comando e attende una sola risposta con lo stesso id."""
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
        """Invia un comando e raccoglie le risposte finche' `is_last(pacchetti_finora)` e' vero.

        Il timeout vale per ogni singolo pacchetto atteso.
        """
        wait = timeout or self._default_timeout
        packets: list[Packet] = []
        async with self._lock:
            queue: asyncio.Queue[Packet] = asyncio.Queue()
            self._pending = (cmd, queue)
            try:
                await self._transport.write(build(cmd, payload))
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
        try:
            pkt = parse(data)
        except PacketError as exc:
            log.warning("dropping malformed notification %s: %s", data.hex(), exc)
            return
        if self._pending and self._pending[0] == pkt.cmd:
            self._pending[1].put_nowait(pkt)
        elif self._on_unsolicited:
            self._on_unsolicited(pkt)
