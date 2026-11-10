"""BLE transport on top of bleak: RX (write) and TX (notify) of the ring's UART service."""

from collections.abc import Callable

from bleak import BleakClient

SERVICE = "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e"
RX_CHAR = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
TX_CHAR = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
BIG_SERVICE = "de5bf728-d711-4e47-af26-65e3012a5dc7"
BIG_WRITE_CHAR = "de5bf72a-d711-4e47-af26-65e3012a5dc7"
BIG_NOTIFY_CHAR = "de5bf729-d711-4e47-af26-65e3012a5dc7"


class BleakTransport:
    """Adapts a connected BleakClient to the client's `Transport` interface."""

    def __init__(
        self, client: BleakClient, write_char: str = RX_CHAR, notify_char: str = TX_CHAR
    ) -> None:
        self._client = client
        self._write_char = write_char
        self._notify_char = notify_char
        self._handler: Callable[[bytes], None] | None = None

    def set_notify_handler(self, handler: Callable[[bytes], None]) -> None:
        """Registers the callback for incoming packets."""
        self._handler = handler

    async def start(self) -> None:
        """Subscribes to the channel notifications."""
        await self._client.start_notify(self._notify_char, self._on_notify)

    async def write(self, data: bytes) -> None:
        """Scrive su RX (write without response)."""
        await self._client.write_gatt_char(self._write_char, data, response=False)

    def _on_notify(self, _sender: object, data: bytearray) -> None:
        if self._handler is not None:
            self._handler(bytes(data))
