import struct
from datetime import UTC, datetime, timedelta

import pytest

from ringproto.client import RingClient
from ringproto.commands.slot_log import (
    CMD_HRV,
    CMD_STRESS,
    SlotLog,
    decode,
    encode_hrv_request,
    encode_stress_request,
    read_hrv_log,
    read_stress_log,
)
from ringproto.commands.split_array import is_complete as is_last
from ringproto.packet import Packet, build, parse
from tests.fakes import FakeTransport

DAY = datetime(2026, 11, 10, tzinfo=UTC)


def header(cmd: int, total: int = 5, interval: int = 30) -> bytes:
    return build(cmd, bytes([0, total, interval]))


def first(cmd: int, values: list[int], extra: int = 0) -> bytes:
    assert len(values) == 12
    return build(cmd, bytes([1, extra, *values]))


def nth(cmd: int, index: int, values: list[int]) -> bytes:
    assert len(values) == 13
    return build(cmd, bytes([index, *values]))


def empty(cmd: int) -> bytes:
    return build(cmd, bytes([0xFF]))


def pkts(*raws: bytes) -> list[Packet]:
    return [parse(r) for r in raws]


def full_day(cmd: int) -> list[bytes]:
    return [
        header(cmd),
        first(cmd, list(range(1, 13))),
        nth(cmd, 2, list(range(13, 26))),
        nth(cmd, 3, list(range(26, 39))),
        nth(cmd, 4, list(range(39, 52))),
    ]


def test_encode_stress_request_has_no_arguments() -> None:
    assert encode_stress_request() == b""


def test_encode_hrv_request_is_days_ago_little_endian() -> None:
    assert encode_hrv_request(0) == struct.pack("<I", 0)
    assert encode_hrv_request(3) == struct.pack("<I", 3)


def test_encode_hrv_request_rejects_negative_days() -> None:
    with pytest.raises(ValueError, match="days_ago"):
        encode_hrv_request(-1)


def test_is_last_on_empty_response() -> None:
    assert is_last(pkts(empty(CMD_STRESS))) is True


def test_is_last_waits_for_last_announced_packet() -> None:
    raws = full_day(CMD_STRESS)
    assert [is_last(pkts(*raws[: i + 1])) for i in range(5)] == [False, False, False, False, True]


def test_decode_empty_returns_none() -> None:
    assert decode(pkts(empty(CMD_HRV))) is None


def test_decode_collects_values_in_order_and_truncates_to_one_day() -> None:
    log = decode(pkts(*full_day(CMD_STRESS)))

    assert log == SlotLog(interval_minutes=30, samples=tuple(range(1, 49)))


def test_decode_uses_default_interval_if_header_has_none() -> None:
    raws = full_day(CMD_STRESS)
    raws[0] = build(CMD_STRESS, bytes([0, 5, 0]))
    log = decode(pkts(*raws))
    assert log is not None
    assert log.interval_minutes == 30


def test_readings_skip_empty_slots_and_compute_times() -> None:
    log = SlotLog(interval_minutes=30, samples=(0, 40, 0, 55))

    assert log.readings(DAY) == [
        (DAY + timedelta(minutes=30), 40),
        (DAY + timedelta(minutes=90), 55),
    ]


async def test_read_stress_log_roundtrip() -> None:
    tr = FakeTransport()
    tr.responses[CMD_STRESS] = full_day(CMD_STRESS)

    log = await read_stress_log(RingClient(tr))

    assert tr.sent == [build(CMD_STRESS)]
    assert log is not None
    assert log.samples[:3] == (1, 2, 3)


async def test_read_hrv_log_sends_days_ago() -> None:
    tr = FakeTransport()
    tr.responses[CMD_HRV] = [empty(CMD_HRV)]

    assert await read_hrv_log(RingClient(tr), days_ago=2) is None
    assert tr.sent == [build(CMD_HRV, struct.pack("<I", 2))]


def test_decode_header_only_returns_none() -> None:
    assert decode(pkts(header(CMD_STRESS, total=1))) is None
