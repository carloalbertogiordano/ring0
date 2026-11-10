from ringproto.ble import RX_CHAR, TX_CHAR, BleakTransport


class FakeBleakClient:
    def __init__(self) -> None:
        self.writes: list[tuple[str, bytes, bool]] = []
        self.notify_cb = None
        self.notify_uuid: str | None = None

    async def start_notify(self, uuid: str, cb) -> None:  # noqa: ANN001
        self.notify_uuid = uuid
        self.notify_cb = cb

    async def write_gatt_char(self, uuid: str, data: bytes, response: bool) -> None:
        self.writes.append((uuid, data, response))


async def test_write_goes_to_rx_characteristic_without_response() -> None:
    fake = FakeBleakClient()
    tr = BleakTransport(fake)  # type: ignore[arg-type]

    await tr.write(b"\x03" + bytes(15))

    assert fake.writes == [(RX_CHAR, b"\x03" + bytes(15), False)]


async def test_start_subscribes_to_tx_and_forwards_notifications() -> None:
    fake = FakeBleakClient()
    tr = BleakTransport(fake)  # type: ignore[arg-type]
    received: list[bytes] = []
    tr.set_notify_handler(received.append)

    await tr.start()
    assert fake.notify_uuid == TX_CHAR
    fake.notify_cb(None, bytearray(b"\x03\x07"))  # type: ignore[misc]

    assert received == [b"\x03\x07"]


async def test_notification_before_handler_is_ignored() -> None:
    fake = FakeBleakClient()
    tr = BleakTransport(fake)  # type: ignore[arg-type]
    await tr.start()

    fake.notify_cb(None, bytearray(b"\x03"))  # type: ignore[misc]


async def test_transport_can_target_other_characteristics() -> None:
    fake = FakeBleakClient()
    tr = BleakTransport(fake, write_char="w-uuid", notify_char="n-uuid")  # type: ignore[arg-type]

    await tr.write(b"\xbc")
    await tr.start()

    assert fake.writes == [("w-uuid", b"\xbc", False)]
    assert fake.notify_uuid == "n-uuid"
