import pytest

from ringproto.client import RingClient
from ringproto.commands.hr_settings import (
    HeartRateSettings,
    decode,
    encode_read,
    encode_write,
    read_hr_settings,
    write_hr_settings,
)
from ringproto.packet import Packet, build, parse
from tests.fakes import FakeTransport

# Risposta reale dal repo tahnok (ring R02): auto-HR attivo, ogni 60 minuti.
REAL_RSP = bytes.fromhex("1601013c0000000000000000000000" + "54")


def test_decode_real_capture() -> None:
    assert decode(parse(REAL_RSP)) == HeartRateSettings(enabled=True, interval_minutes=60)


@pytest.mark.parametrize(("flag", "enabled"), [(1, True), (2, False)])
def test_decode_enabled_flag(flag: int, enabled: bool) -> None:
    pkt = Packet(cmd=0x16, payload=bytes([1, flag, 5]) + bytes(11), is_error=False)
    assert decode(pkt) == HeartRateSettings(enabled=enabled, interval_minutes=5)


def test_decode_rejects_unknown_enabled_flag() -> None:
    pkt = Packet(cmd=0x16, payload=bytes([1, 7, 5]) + bytes(11), is_error=False)
    with pytest.raises(ValueError, match="enabled"):
        decode(pkt)


def test_decode_rejects_other_command() -> None:
    with pytest.raises(ValueError, match="settings"):
        decode(Packet(cmd=0x15, payload=bytes(14), is_error=False))


def test_encode_read_is_subcommand_1() -> None:
    assert encode_read() == b"\x01"


@pytest.mark.parametrize(("enabled", "flag"), [(True, 1), (False, 2)])
def test_encode_write(enabled: bool, flag: int) -> None:
    settings = HeartRateSettings(enabled=enabled, interval_minutes=10)
    assert encode_write(settings) == bytes([2, flag, 10])


@pytest.mark.parametrize("interval", [0, 256])
def test_encode_write_rejects_invalid_interval(interval: int) -> None:
    with pytest.raises(ValueError, match="interval"):
        encode_write(HeartRateSettings(enabled=True, interval_minutes=interval))


async def test_read_hr_settings_roundtrip() -> None:
    tr = FakeTransport()
    tr.responses[0x16] = [REAL_RSP]

    assert await read_hr_settings(RingClient(tr)) == HeartRateSettings(True, 60)
    assert tr.sent == [build(0x16, b"\x01")]


async def test_write_hr_settings_sends_write_subcommand_and_returns_confirmed_state() -> None:
    tr = FakeTransport()
    tr.responses[0x16] = [build(0x16, bytes([2, 1, 5]))]

    result = await write_hr_settings(RingClient(tr), HeartRateSettings(True, 5))

    assert tr.sent == [build(0x16, bytes([2, 1, 5]))]
    assert result == HeartRateSettings(True, 5)
