import pytest

from ringproto.client import RingClient
from ringproto.commands.auto_monitor import (
    Feature,
    decode,
    encode_read,
    encode_write,
    read_enabled,
    write_enabled,
)
from ringproto.packet import Packet, build, parse
from tests.fakes import FakeTransport


def pkt(feature: Feature, *payload: int) -> Packet:
    return Packet(cmd=feature.cmd, payload=bytes(payload).ljust(14, b"\x00"), is_error=False)


def test_feature_command_ids() -> None:
    assert [f.cmd for f in (Feature.SPO2, Feature.STRESS, Feature.HRV, Feature.TEMPERATURE)] == [
        0x2C,
        0x36,
        0x38,
        0x3A,
    ]


@pytest.mark.parametrize("feature", [Feature.SPO2, Feature.STRESS, Feature.HRV])
def test_encode_read_plain_features(feature: Feature) -> None:
    assert encode_read(feature) == b"\x01"


def test_encode_read_temperature_has_extra_prefix_byte() -> None:
    assert encode_read(Feature.TEMPERATURE) == b"\x03\x01"


@pytest.mark.parametrize(("enabled", "flag"), [(True, 1), (False, 0)])
def test_encode_write_plain_features(enabled: bool, flag: int) -> None:
    assert encode_write(Feature.STRESS, enabled=enabled) == bytes([2, flag])


@pytest.mark.parametrize(("enabled", "flag"), [(True, 1), (False, 0)])
def test_encode_write_temperature(enabled: bool, flag: int) -> None:
    assert encode_write(Feature.TEMPERATURE, enabled=enabled) == bytes([3, 2, flag])


@pytest.mark.parametrize(("flag", "expected"), [(1, True), (0, False)])
def test_decode_plain_feature_flag_is_payload_byte_1(flag: int, expected: bool) -> None:
    assert decode(Feature.HRV, pkt(Feature.HRV, 1, flag)) is expected


@pytest.mark.parametrize(("flag", "expected"), [(1, True), (0, False)])
def test_decode_temperature_flag_is_payload_byte_2(flag: int, expected: bool) -> None:
    assert decode(Feature.TEMPERATURE, pkt(Feature.TEMPERATURE, 3, 1, flag)) is expected


def test_decode_rejects_packet_of_another_feature() -> None:
    with pytest.raises(ValueError, match="stress"):
        decode(Feature.STRESS, pkt(Feature.HRV, 1, 1))


async def test_read_enabled_roundtrip() -> None:
    tr = FakeTransport()
    tr.responses[0x2C] = [build(0x2C, bytes([1, 1]))]

    assert await read_enabled(RingClient(tr), Feature.SPO2) is True
    assert tr.sent == [build(0x2C, b"\x01")]


async def test_write_enabled_discards_write_reply_and_returns_read_back_state() -> None:
    tr = FakeTransport()
    tr.scripted[0x3A] = [[build(0x3A, bytes([3, 2, 0]))], [build(0x3A, bytes([3, 1, 1]))]]

    result = await write_enabled(RingClient(tr), Feature.TEMPERATURE, enabled=True)

    assert tr.sent == [build(0x3A, bytes([3, 2, 1])), build(0x3A, bytes([3, 1]))]
    assert result is True


# Risposte reali del ring R09 (FW RT09CR2_3.00.03_260929, 2026-11-10): prima/dopo l'abilitazione.
REAL_READS = {
    (Feature.SPO2, False): "2c01001e00000000000000000000004b",
    (Feature.SPO2, True): "2c01010000000000000000000000002e",
    (Feature.STRESS, False): "36010000000000000000000000000037",
    (Feature.STRESS, True): "36010100000000000000000000000038",
    (Feature.HRV, False): "38010000000000000000000000000039",
    (Feature.HRV, True): "3801010000000000000000000000003a",
    (Feature.TEMPERATURE, False): "3a03010000000000000000000000003e",
    (Feature.TEMPERATURE, True): "3a03010100000000000000000000003f",
}


@pytest.mark.parametrize(("key", "raw"), REAL_READS.items(), ids=lambda v: str(v)[:24])
def test_decode_real_r09_reads(key: tuple[Feature, bool], raw: str) -> None:
    feature, expected = key
    assert decode(feature, parse(bytes.fromhex(raw))) is expected
