import asyncio

import pytest

from ringproto.bigdata import BigFrame, encode_frame
from ringproto.bigdata_client import BigDataClient
from ringproto.client import RingTimeoutError
from tests.fakes import FakeBigTransport, chunks

TEMP = 0x25
SLEEP = 0x27


async def test_request_sends_encoded_frame_and_returns_response() -> None:
    tr = FakeBigTransport()
    tr.responses[TEMP] = [encode_frame(TEMP, b"data")]

    frame = await BigDataClient(tr).request(TEMP, b"\x02")

    assert tr.sent == [encode_frame(TEMP, b"\x02")]
    assert frame == BigFrame(TEMP, b"data")


async def test_request_reassembles_fragmented_response() -> None:
    tr = FakeBigTransport()
    payload = bytes(range(100))
    tr.responses[TEMP] = chunks(encode_frame(TEMP, payload))

    assert (await BigDataClient(tr).request(TEMP)).payload == payload


async def test_request_times_out_when_ring_is_silent() -> None:
    with pytest.raises(RingTimeoutError, match="0x25"):
        await BigDataClient(FakeBigTransport(), default_timeout=0.05).request(TEMP)


async def test_other_types_go_to_callback_and_do_not_resolve_request() -> None:
    tr = FakeBigTransport()
    seen: list[BigFrame] = []
    tr.responses[TEMP] = [encode_frame(SLEEP, b"zz"), encode_frame(TEMP, b"t")]

    frame = await BigDataClient(tr, on_unsolicited=seen.append).request(TEMP)

    assert frame.payload == b"t"
    assert seen == [BigFrame(SLEEP, b"zz")]


async def test_requests_are_serialized() -> None:
    tr = FakeBigTransport()
    tr.delay = 0.02
    tr.responses[TEMP] = [encode_frame(TEMP, b"t")]
    tr.responses[SLEEP] = [encode_frame(SLEEP, b"s")]
    client = BigDataClient(tr)

    await asyncio.gather(client.request(TEMP), client.request(SLEEP))

    assert tr.events == [("write", TEMP), ("notify", TEMP), ("write", SLEEP), ("notify", SLEEP)]
