from datetime import UTC, datetime, timedelta, timezone

import pytest

from ringproto.client import RingClient
from ringproto.commands.set_time import (
    Capabilities,
    decode,
    encode_payload,
    set_time,
    to_bcd,
)
from ringproto.packet import Packet, build, parse
from tests.fakes import FakeTransport

WHEN = datetime(2026, 11, 10, 15, 7, 9, tzinfo=UTC)
BIT_FLAGS = ("blood_oxygen", "blood_pressure", "manual_heart_rate", "blood_sugar", "stress", "hrv")


@pytest.mark.parametrize(
    ("value", "bcd"), [(0, 0x00), (9, 0x09), (26, 0x26), (59, 0x59), (99, 0x99)]
)
def test_to_bcd(value: int, bcd: int) -> None:
    assert to_bcd(value) == bcd


@pytest.mark.parametrize("value", [-1, 100])
def test_to_bcd_rejects_out_of_range(value: int) -> None:
    with pytest.raises(ValueError, match="bcd"):
        to_bcd(value)


def test_encode_payload_is_bcd_utc_with_language_byte() -> None:
    assert encode_payload(WHEN) == bytes([0x26, 0x11, 0x10, 0x15, 0x07, 0x09, 0x01])


def test_encode_payload_converts_aware_datetime_to_utc() -> None:
    local = WHEN.astimezone(timezone(timedelta(hours=1)))
    assert encode_payload(local) == encode_payload(WHEN)


def test_encode_payload_rejects_naive_datetime() -> None:
    with pytest.raises(ValueError, match="timezone"):
        encode_payload(datetime(2026, 11, 10, 15, 7, 9))  # noqa: DTZ001


@pytest.mark.parametrize("year", [1999, 2100])
def test_encode_payload_rejects_year_outside_2000_2099(year: int) -> None:
    with pytest.raises(ValueError, match="year"):
        encode_payload(WHEN.replace(year=year))


def _caps_packet(**offsets: int) -> Packet:
    payload = bytearray(14)
    for index, value in offsets.items():
        payload[int(index[1:])] = value
    return Packet(cmd=0x01, payload=bytes(payload), is_error=False)


def test_decode_all_clear() -> None:
    caps = decode(_caps_packet())
    assert caps == Capabilities(
        temperature=False,
        blood_oxygen=False,
        blood_pressure=False,
        manual_heart_rate=False,
        blood_sugar=False,
        stress=False,
        hrv=False,
        new_sleep_protocol=False,
        raw=bytes(14),
    )


def test_decode_temperature_flag_is_payload_byte_0() -> None:
    assert decode(_caps_packet(p0=1)).temperature is True


@pytest.mark.parametrize(
    ("offset", "mask", "field"),
    [
        (3, 0x02, "blood_oxygen"),
        (3, 0x04, "blood_pressure"),
        (11, 0x01, "manual_heart_rate"),
        (11, 0x80, "blood_sugar"),
        (13, 0x10, "stress"),
        (13, 0x20, "hrv"),
    ],
)
def test_decode_bit_flags(offset: int, mask: int, field: str) -> None:
    caps = decode(_caps_packet(**{f"p{offset}": mask}))
    set_flags = {name for name in BIT_FLAGS if getattr(caps, name)}
    assert set_flags == {field}


def test_decode_new_sleep_protocol_is_payload_byte_8_equal_1() -> None:
    assert decode(_caps_packet(p8=1)).new_sleep_protocol is True
    assert decode(_caps_packet(p8=2)).new_sleep_protocol is False


def test_decode_rejects_other_command() -> None:
    with pytest.raises(ValueError, match="set time"):
        decode(Packet(cmd=0x03, payload=bytes(14), is_error=False))


async def test_set_time_sends_bcd_packet_and_returns_capabilities() -> None:
    tr = FakeTransport()
    tr.responses[0x01] = [build(0x01, bytes([1, 0, 1, 0x02]))]

    caps = await set_time(RingClient(tr), WHEN)

    assert tr.sent == [build(0x01, bytes([0x26, 0x11, 0x10, 0x15, 0x07, 0x09, 0x01]))]
    assert caps.temperature is True
    assert caps.blood_oxygen is True


# Risposta reale del ring R09 (FW RT09CR2_3.00.03_260929) a set time, catturata il 2026-11-10.
REAL_RSP = bytes.fromhex("01010000020000000001002000003055")


def test_decode_real_r09_capabilities() -> None:
    caps = decode(parse(REAL_RSP))

    assert caps == Capabilities(
        temperature=True,
        blood_oxygen=True,
        blood_pressure=False,
        manual_heart_rate=False,
        blood_sugar=False,
        stress=True,
        hrv=True,
        new_sleep_protocol=True,
        raw=REAL_RSP[1:-1],
    )
