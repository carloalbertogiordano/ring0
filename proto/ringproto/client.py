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
        self._pending: tuple[int, asyncio.Future[Packet]] | None = None
        transport.set_notify_handler(self._handle_notification)

    async def request(self, cmd: int, payload: bytes = b"", timeout: float | None = None) -> Packet:
        """Invia un comando e attende la risposta con lo stesso id."""
        async with self._lock:
            future: asyncio.Future[Packet] = asyncio.get_running_loop().create_future()
            self._pending = (cmd, future)
            try:
                await self._transport.write(build(cmd, payload))
                async with asyncio.timeout(timeout or self._default_timeout):
                    pkt = await future
            except TimeoutError as exc:
                msg = f"no response to command {cmd:#04x}"
                raise RingTimeoutError(msg) from exc
            finally:
                self._pending = None
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
        if self._pending and self._pending[0] == pkt.cmd and not self._pending[1].done():
            self._pending[1].set_result(pkt)
        elif self._on_unsolicited:
            self._on_unsolicited(pkt)
