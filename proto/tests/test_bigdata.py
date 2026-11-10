import pytest

from ringproto.bigdata import BigFrame, FrameAssembler, crc16_modbus, encode_frame


def test_crc16_modbus_standard_check_value() -> None:
    assert crc16_modbus(b"123456789") == 0x4B37


def test_crc16_modbus_of_empty_is_initial_value() -> None:
    assert crc16_modbus(b"") == 0xFFFF


# Richieste di client noti (Gadgetbridge): confermano CRC e layout [BC][id][len][crc][payload].
@pytest.mark.parametrize(
    ("type_id", "payload", "hex_frame"),
    [
        (0x2A, b"\xff", "bc2a0100ff00ff"),
        (0x27, b"\xff", "bc270100ff00ff"),
        (0x25, b"\x02", "bc250100" + "3e81" + "02"),
    ],
)
def test_encode_frame_matches_known_requests(type_id: int, payload: bytes, hex_frame: str) -> None:
    assert encode_frame(type_id, payload) == bytes.fromhex(hex_frame)


def test_encode_frame_with_empty_payload() -> None:
    assert encode_frame(0x25) == bytes.fromhex("bc2500" + "00" + "ffff")


def _frame(payload: bytes, type_id: int = 0x25) -> bytes:
    return encode_frame(type_id, payload)


def test_assembler_returns_complete_frame_from_single_notification() -> None:
    asm = FrameAssembler()
    assert asm.feed(_frame(b"hello")) == [BigFrame(type_id=0x25, payload=b"hello")]


def test_assembler_joins_fragments_across_notifications() -> None:
    frame = _frame(bytes(range(60)))
    chunks = [frame[i : i + 20] for i in range(0, len(frame), 20)]
    asm = FrameAssembler()

    results = [asm.feed(c) for c in chunks]

    assert results[:-1] == [[] for _ in chunks[:-1]]
    assert results[-1] == [BigFrame(type_id=0x25, payload=bytes(range(60)))]


def test_assembler_waits_for_incomplete_header() -> None:
    frame = _frame(b"abc")
    asm = FrameAssembler()
    assert asm.feed(frame[:3]) == []
    assert asm.feed(frame[3:]) == [BigFrame(0x25, b"abc")]


def test_assembler_returns_two_back_to_back_frames() -> None:
    asm = FrameAssembler()
    out = asm.feed(_frame(b"a", 0x25) + _frame(b"bb", 0x27))
    assert out == [BigFrame(0x25, b"a"), BigFrame(0x27, b"bb")]


def test_assembler_drops_frame_with_bad_crc_and_recovers() -> None:
    bad = bytearray(_frame(b"corrupt"))
    bad[-1] ^= 0xFF
    asm = FrameAssembler()
    assert asm.feed(bytes(bad)) == []
    assert asm.feed(_frame(b"ok")) == [BigFrame(0x25, b"ok")]


def test_assembler_skips_garbage_before_magic_byte() -> None:
    asm = FrameAssembler()
    assert asm.feed(b"\x00\x11" + _frame(b"x")) == [BigFrame(0x25, b"x")]


def test_assembler_ignores_non_bigdata_bytes_without_magic() -> None:
    asm = FrameAssembler()
    assert asm.feed(b"\x01\x02\x03") == []
    assert asm.feed(_frame(b"y")) == [BigFrame(0x25, b"y")]


def test_assembler_resyncs_when_length_field_is_implausible() -> None:
    bogus = bytes([0xBC, 0x25, 0xFF, 0xFF, 0x00, 0x00])
    asm = FrameAssembler()
    assert asm.feed(bogus + _frame(b"after")) == [BigFrame(0x25, b"after")]


# Frame reali del ring R09 (FW RT09CR2_3.00.03_260929, 2026-11-10) a ring appena configurato, senza storico.
REAL_EMPTY_TEMP = bytes.fromhex("bc25020081b8001e")
REAL_EMPTY_SLEEP = bytes.fromhex("bc270100bf4000")
REAL_EMPTY_SPO2 = bytes.fromhex("bc2a0000ffff")


def test_assembler_accepts_real_ring_frames() -> None:
    asm = FrameAssembler()
    assert asm.feed(REAL_EMPTY_TEMP) == [BigFrame(0x25, bytes.fromhex("001e"))]
    assert asm.feed(REAL_EMPTY_SLEEP) == [BigFrame(0x27, b"\x00")]
    assert asm.feed(REAL_EMPTY_SPO2) == [BigFrame(0x2A, b"")]
