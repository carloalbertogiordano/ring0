from datetime import UTC, datetime, timedelta

import pytest

from ringproto.bigdata import BigFrame, encode_frame
from ringproto.bigdata_client import BigDataClient
from ringproto.commands.temperature import (
    TYPE_TEMPERATURE,
    TemperatureDay,
    decode,
    encode_request,
    read_temperature,
)
from tests.fakes import FakeBigTransport, chunks

DAY = datetime(2026, 11, 10, tzinfo=UTC)

# Payload reale del R09 (2026-11-10 ~15:32 CET): oggi, passo 30 min, una sola misura (slot 29 = 14:30 UTC).
REAL_PAYLOAD = bytes.fromhex("001e" + "00" * 29 + "a9" + "00" * 18)


def block(days_ago: int, values: dict[int, int] | None = None, step: int = 30) -> bytes:
    raw = bytearray(48)
    for slot, value in (values or {}).items():
        raw[slot] = value
    return bytes([days_ago, step]) + bytes(raw)


def test_encode_request_is_a_single_byte() -> None:
    assert encode_request() == b"\x02"
    assert encode_request(7) == b"\x07"


@pytest.mark.parametrize("days", [0, 256])
def test_encode_request_rejects_invalid_days(days: int) -> None:
    with pytest.raises(ValueError, match="days"):
        encode_request(days)


def test_decode_real_payload() -> None:
    assert len(REAL_PAYLOAD) == 50
    days = decode(BigFrame(TYPE_TEMPERATURE, REAL_PAYLOAD))

    assert len(days) == 1
    assert days[0].days_ago == 0
    assert days[0].interval_minutes == 30
    assert days[0].readings(DAY) == [(DAY + timedelta(hours=14, minutes=30), pytest.approx(36.9))]


def test_decode_empty_response_has_no_days() -> None:
    assert decode(BigFrame(TYPE_TEMPERATURE, bytes.fromhex("001e"))) == []
    assert decode(BigFrame(TYPE_TEMPERATURE, b"")) == []


def test_decode_multiple_day_blocks_in_order() -> None:
    payload = block(2, {0: 150}) + block(1, {1: 160}) + block(0, {2: 170})

    days = decode(BigFrame(TYPE_TEMPERATURE, payload))

    assert [d.days_ago for d in days] == [2, 1, 0]
    assert days[2].samples[2] == 170


def test_decode_rejects_truncated_block() -> None:
    with pytest.raises(ValueError, match="truncated"):
        decode(BigFrame(TYPE_TEMPERATURE, block(0)[:-1]))


def test_decode_rejects_other_frame_type() -> None:
    with pytest.raises(ValueError, match="temperature"):
        decode(BigFrame(0x27, block(0)))


def test_readings_convert_raw_to_celsius_and_skip_empty_slots() -> None:
    day = TemperatureDay(days_ago=0, interval_minutes=30, samples=(0, 150, 0, 255))

    assert day.readings(DAY) == [
        (DAY + timedelta(minutes=30), pytest.approx(35.0)),
        (DAY + timedelta(minutes=90), pytest.approx(45.5)),
    ]


async def test_read_temperature_roundtrip_with_fragmented_frame() -> None:
    tr = FakeBigTransport()
    tr.responses[TYPE_TEMPERATURE] = chunks(encode_frame(TYPE_TEMPERATURE, REAL_PAYLOAD))

    days = await read_temperature(BigDataClient(tr), days=2)

    assert tr.sent == [encode_frame(TYPE_TEMPERATURE, b"\x02")]
    assert days[0].samples[29] == 0xA9
