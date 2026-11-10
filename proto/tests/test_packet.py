import pytest

from ringproto.packet import PACKET_LEN, Packet, PacketError, build, checksum, parse

# Vettori reali catturati dal ring R09 (FW RT09CR2_3.00.03_260929), CMD 0x03 batteria.
BATTERY_REQ = bytes.fromhex("03000000000000000000000000000003")
BATTERY_RSP = bytes.fromhex("0307000000000000000000000000000a")


def test_checksum_is_sum_of_bytes_masked() -> None:
    assert checksum(bytes([0x03])) == 0x03
    assert checksum(bytes([0xFF, 0x02])) == 0x01


def test_build_battery_request_matches_capture() -> None:
    assert build(0x03) == BATTERY_REQ


def test_build_pads_payload_to_14_bytes_and_appends_crc() -> None:
    raw = build(0x01, bytes([26, 11, 10, 15, 30, 0]))
    assert len(raw) == PACKET_LEN
    assert raw[:7] == bytes([0x01, 26, 11, 10, 15, 30, 0])
    assert raw[7:15] == bytes(8)
    assert raw[15] == sum(raw[:15]) & 0xFF


def test_build_rejects_oversized_payload() -> None:
    with pytest.raises(ValueError, match="payload"):
        build(0x01, bytes(15))


@pytest.mark.parametrize("cmd", [-1, 0x80, 0x100])
def test_build_rejects_out_of_range_command(cmd: int) -> None:
    with pytest.raises(ValueError, match="command"):
        build(cmd)


def test_parse_battery_response_matches_capture() -> None:
    pkt = parse(BATTERY_RSP)
    assert pkt == Packet(cmd=0x03, payload=bytes([0x07]) + bytes(13), is_error=False)


def test_parse_flags_error_bit_and_clears_it_from_cmd() -> None:
    body = bytes([0x83]) + bytes(14)
    pkt = parse(body + bytes([checksum(body)]))
    assert pkt.cmd == 0x03
    assert pkt.is_error is True


def test_parse_rejects_wrong_length() -> None:
    with pytest.raises(PacketError, match="length"):
        parse(BATTERY_RSP[:-1])


def test_parse_rejects_bad_checksum() -> None:
    bad = BATTERY_RSP[:-1] + bytes([0x00])
    with pytest.raises(PacketError, match="checksum"):
        parse(bad)
