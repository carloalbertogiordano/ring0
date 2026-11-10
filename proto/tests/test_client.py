import asyncio

import pytest

from ringproto.client import RingClient, RingCommandError, RingTimeoutError
from ringproto.packet import Packet
from tests.fakes import FakeTransport, error_response, response

BATTERY_REQ = bytes.fromhex("03000000000000000000000000000003")
BATTERY_RSP = bytes.fromhex("0307000000000000000000000000000a")


async def test_request_sends_packet_and_returns_matching_response() -> None:
    tr = FakeTransport()
    tr.responses[0x03] = [BATTERY_RSP]
    client = RingClient(tr)

    pkt = await client.request(0x03)

    assert tr.sent == [BATTERY_REQ]
    assert pkt == Packet(cmd=0x03, payload=bytes([7]) + bytes(13), is_error=False)


async def test_request_times_out_when_ring_is_silent() -> None:
    client = RingClient(FakeTransport(), default_timeout=0.05)

    with pytest.raises(RingTimeoutError, match="0x03"):
        await client.request(0x03)


async def test_request_raises_on_error_flag() -> None:
    tr = FakeTransport()
    tr.responses[0x03] = [error_response(0x03)]

    with pytest.raises(RingCommandError, match="0x03"):
        await RingClient(tr).request(0x03)


async def test_requests_are_serialized_one_at_a_time() -> None:
    tr = FakeTransport()
    tr.delay = 0.02
    tr.responses[0x03] = [response(0x03, bytes([50]))]
    tr.responses[0x01] = [response(0x01)]
    client = RingClient(tr)

    await asyncio.gather(client.request(0x03), client.request(0x01))

    assert tr.events == [("write", 0x03), ("notify", 0x03), ("write", 0x01), ("notify", 0x01)]


async def test_unsolicited_packets_go_to_callback_and_do_not_resolve_request() -> None:
    tr = FakeTransport()
    seen: list[Packet] = []
    other = response(0x73, bytes([0x0C, 55]))
    tr.responses[0x03] = [other, BATTERY_RSP]

    pkt = await RingClient(tr, on_unsolicited=seen.append).request(0x03)

    assert pkt.cmd == 0x03
    assert [p.cmd for p in seen] == [0x73]


async def test_malformed_notification_is_dropped() -> None:
    tr = FakeTransport()
    bad = BATTERY_RSP[:-1] + b"\x00"
    tr.responses[0x03] = [bad, BATTERY_RSP]

    pkt = await RingClient(tr).request(0x03)

    assert pkt == Packet(cmd=0x03, payload=bytes([7]) + bytes(13), is_error=False)


async def test_client_is_usable_after_a_timeout() -> None:
    tr = FakeTransport()
    client = RingClient(tr, default_timeout=0.05)
    with pytest.raises(RingTimeoutError):
        await client.request(0x03)

    tr.responses[0x03] = [BATTERY_RSP]
    assert (await client.request(0x03)).cmd == 0x03
