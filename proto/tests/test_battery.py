import pytest

from ringproto.client import RingClient
from ringproto.commands.battery import Battery, decode, read_battery
from ringproto.packet import Packet, parse
from tests.fakes import FakeTransport

# Real vector: R09 FW RT09CR2_3.00.03_260929, battery 7%, not charging.
REAL_RSP = bytes.fromhex("0307000000000000000000000000000a")


def test_decode_real_capture() -> None:
    assert decode(parse(REAL_RSP)) == Battery(level=7, charging=False)


@pytest.mark.parametrize(("raw", "charging"), [(0, False), (1, True)])
def test_decode_charging_flag(raw: int, charging: bool) -> None:
    pkt = Packet(cmd=0x03, payload=bytes([80, raw]) + bytes(12), is_error=False)
    assert decode(pkt) == Battery(level=80, charging=charging)


def test_decode_rejects_other_command() -> None:
    pkt = Packet(cmd=0x01, payload=bytes(14), is_error=False)
    with pytest.raises(ValueError, match="battery"):
        decode(pkt)


@pytest.mark.parametrize("level", [101, 255])
def test_decode_rejects_impossible_level(level: int) -> None:
    pkt = Packet(cmd=0x03, payload=bytes([level]) + bytes(13), is_error=False)
    with pytest.raises(ValueError, match="level"):
        decode(pkt)


async def test_read_battery_roundtrip() -> None:
    tr = FakeTransport()
    tr.responses[0x03] = [REAL_RSP]

    assert await read_battery(RingClient(tr)) == Battery(level=7, charging=False)
