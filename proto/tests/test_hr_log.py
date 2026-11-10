import struct
from datetime import UTC, datetime, timedelta

import pytest

from ringproto.client import RingClient
from ringproto.commands.hr_log import (
    HeartRateLog,
    decode,
    encode_request,
    is_last,
    read_hr_log,
)
from ringproto.packet import Packet, build, parse
from tests.fakes import FakeTransport

DAY = datetime(2026, 11, 10, tzinfo=UTC)
TS = int(DAY.timestamp())

# Pacchetto sub-tipo 0 reale (repo tahnok, ring R02): 24 pacchetti, intervallo 5 minuti.
REAL_SUB0 = bytes.fromhex("15001805000000000000000000000032")


def sub0(size: int, interval: int = 5) -> bytes:
    return build(0x15, bytes([0, size, interval]))


def sub1(values: list[int], ts: int = TS) -> bytes:
    assert len(values) == 9
    return build(0x15, bytes([1]) + struct.pack("<I", ts) + bytes(values))


def subn(index: int, values: list[int]) -> bytes:
    assert len(values) == 13
    return build(0x15, bytes([index]) + bytes(values))


def no_data() -> bytes:
    return build(0x15, bytes([0xFF]))


def pkts(*raws: bytes) -> list[Packet]:
    return [parse(r) for r in raws]


def test_real_sub0_packet_parses() -> None:
    pkt = parse(REAL_SUB0)
    assert (pkt.payload[0], pkt.payload[1], pkt.payload[2]) == (0, 24, 5)


def test_encode_request_is_little_endian_unix_timestamp() -> None:
    assert encode_request(DAY) == struct.pack("<I", TS)


def test_encode_request_rejects_naive_datetime() -> None:
    with pytest.raises(ValueError, match="timezone"):
        encode_request(datetime(2026, 11, 10))  # noqa: DTZ001


def test_is_last_on_no_data_packet() -> None:
    assert is_last(pkts(no_data())) is True


def test_is_last_waits_for_the_last_indexed_packet() -> None:
    base = [sub0(3), sub1([60] * 9)]
    assert is_last(pkts(*base)) is False
    assert is_last(pkts(*base, subn(2, [70] * 13))) is True


def test_is_last_when_sub0_announces_a_single_packet() -> None:
    assert is_last(pkts(sub0(1))) is True


def test_decode_no_data_returns_none() -> None:
    assert decode(pkts(no_data())) is None


def test_decode_without_samples_returns_none() -> None:
    assert decode(pkts(sub0(1))) is None


def test_decode_assembles_samples_in_order() -> None:
    log = decode(pkts(sub0(3), sub1(list(range(1, 10))), subn(2, list(range(10, 23)))))

    assert log == HeartRateLog(day_start=DAY, interval_minutes=5, samples=tuple(range(1, 23)))


def test_decode_truncates_to_one_day_of_slots() -> None:
    packets = [sub0(24), sub1([60] * 9), *[subn(i, [61] * 13) for i in range(2, 24)]]

    log = decode(pkts(*packets))

    assert log is not None
    assert len(log.samples) == 288


def test_readings_skips_empty_slots_and_computes_times() -> None:
    log = HeartRateLog(day_start=DAY, interval_minutes=5, samples=(0, 62, 0, 71))

    assert log.readings() == [
        (DAY + timedelta(minutes=5), 62),
        (DAY + timedelta(minutes=15), 71),
    ]


async def test_read_hr_log_roundtrip() -> None:
    tr = FakeTransport()
    tr.responses[0x15] = [sub0(3), sub1([60] * 9), subn(2, [65] * 13)]

    log = await read_hr_log(RingClient(tr), DAY)

    assert tr.sent == [build(0x15, encode_request(DAY))]
    assert log is not None
    assert log.samples == (60,) * 9 + (65,) * 13


def test_is_last_is_false_when_first_packet_is_not_a_header() -> None:
    assert is_last(pkts(sub1([60] * 9))) is False


def test_decode_real_r09_no_data_response() -> None:
    # Risposta reale del R09 con auto-HR disattivo (2026-11-10).
    assert decode(pkts(bytes.fromhex("15ff0000000000000000000000000014"))) is None
