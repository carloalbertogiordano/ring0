"""Big-data V2 channel client: one request at a time, reassembled and validated response."""

import asyncio
import logging
from collections.abc import Callable

from ringproto.bigdata import BigFrame, FrameAssembler, encode_frame
from ringproto.client import DEFAULT_TIMEOUT, RingTimeoutError, Transport

log = logging.getLogger(__name__)


class BigDataClient:
    """Sends big-data requests in series and waits for the frame of the same type."""

    def __init__(
        self,
        transport: Transport,
        default_timeout: float = DEFAULT_TIMEOUT,
        on_unsolicited: Callable[[BigFrame], None] | None = None,
    ) -> None:
        self._transport = transport
        self._default_timeout = default_timeout
        self._on_unsolicited = on_unsolicited
        self._lock = asyncio.Lock()
        self._assembler = FrameAssembler()
        self._pending: tuple[int, asyncio.Future[BigFrame]] | None = None
        transport.set_notify_handler(self._handle_notification)

    async def request(
        self, type_id: int, payload: bytes = b"", timeout: float | None = None
    ) -> BigFrame:
        """Sends a request and returns the response frame of the same type."""
        async with self._lock:
            self._assembler = FrameAssembler()  # scarta frammenti residui di risposte precedenti
            future: asyncio.Future[BigFrame] = asyncio.get_running_loop().create_future()
            self._pending = (type_id, future)
            try:
                frame = encode_frame(type_id, payload)
                log.debug("tx %s", frame.hex())
                await self._transport.write(frame)
                async with asyncio.timeout(timeout or self._default_timeout):
                    return await future
            except TimeoutError as exc:
                msg = f"no big-data response for type {type_id:#04x}"
                raise RingTimeoutError(msg) from exc
            finally:
                self._pending = None

    def _handle_notification(self, data: bytes) -> None:
        log.debug("rx %s", data.hex())
        for frame in self._assembler.feed(data):
            if self._pending and self._pending[0] == frame.type_id and not self._pending[1].done():
                self._pending[1].set_result(frame)
            elif self._on_unsolicited:
                self._on_unsolicited(frame)
