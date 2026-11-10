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

# Real response from the tahnok repo (R02 ring): auto HR on, every 60 minutes.
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


async def test_write_hr_settings_discards_write_reply_and_returns_read_back_state() -> None:
    tr = FakeTransport()
    junk_reply = build(0x16, bytes([2, 0, 0]))  # the ring answers a write unreliably
    confirmed = build(0x16, bytes([1, 1, 5]))
    tr.scripted[0x16] = [[junk_reply], [confirmed]]

    result = await write_hr_settings(RingClient(tr), HeartRateSettings(True, 5))

    assert tr.sent == [build(0x16, bytes([2, 1, 5])), build(0x16, b"\x01")]
    assert result == HeartRateSettings(True, 5)


# Risposte reali del ring R09 (FW RT09CR2_3.00.03_260929), 2026-11-10.
R09_DISABLED_30MIN = bytes.fromhex("1601021e05000000000000000000003c")
R09_ENABLED_5MIN = bytes.fromhex("16010105050000000000000000000022")


def test_decode_real_r09_disabled_every_30_minutes() -> None:
    assert decode(parse(R09_DISABLED_30MIN)) == HeartRateSettings(False, 30)


def test_decode_real_r09_enabled_every_5_minutes() -> None:
    assert decode(parse(R09_ENABLED_5MIN)) == HeartRateSettings(True, 5)
