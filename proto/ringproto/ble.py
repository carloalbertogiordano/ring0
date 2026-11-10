"""Trasporto BLE sopra bleak: RX (write) e TX (notify) del servizio UART del ring."""

from collections.abc import Callable

from bleak import BleakClient

SERVICE = "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e"
RX_CHAR = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
TX_CHAR = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"


class BleakTransport:
    """Adatta un BleakClient connesso all'interfaccia `Transport` del client."""

    def __init__(self, client: BleakClient) -> None:
        self._client = client
        self._handler: Callable[[bytes], None] | None = None

    def set_notify_handler(self, handler: Callable[[bytes], None]) -> None:
        """Registra il callback per i pacchetti in arrivo."""
        self._handler = handler

    async def start(self) -> None:
        """Si sottoscrive alle notifiche TX."""
        await self._client.start_notify(TX_CHAR, self._on_notify)

    async def write(self, data: bytes) -> None:
        """Scrive un pacchetto su RX (write without response)."""
        await self._client.write_gatt_char(RX_CHAR, data, response=False)

    def _on_notify(self, _sender: object, data: bytearray) -> None:
        if self._handler is not None:
            self._handler(bytes(data))
