"""Minimal probe: connects, subscribes to the UART notifications, sends CMD 0x03 (battery, read-only) and prints the replies.
Then tries the Device Information reads on the same connection."""

import asyncio, sys, time
from bleak import BleakClient, BleakScanner

RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  # write
TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  # notify
INFO = {
    "name": "00002a00-0000-1000-8000-00805f9b34fb",
    "serial": "00002a25-0000-1000-8000-00805f9b34fb",
    "fw": "00002a26-0000-1000-8000-00805f9b34fb",
    "hw": "00002a27-0000-1000-8000-00805f9b34fb",
}


def packet(cmd, payload=b""):
    body = bytes([cmd]) + payload
    body = body.ljust(15, b"\x00")
    return body + bytes([sum(body) & 0xFF])


async def main(addr):
    dev = await BleakScanner.find_device_by_address(addr, timeout=20)
    if not dev:
        sys.exit("not found")
    t0 = time.time()

    def on_notify(_, data):
        print(f"[{time.time() - t0:5.2f}s] RX {bytes(data).hex()}", flush=True)

    async with BleakClient(dev, timeout=30) as c:
        print("connected", c.is_connected, flush=True)
        await asyncio.sleep(2)
        await c.start_notify(TX, on_notify)
        pkt = packet(0x03)
        print("TX", pkt.hex(), flush=True)
        await c.write_gatt_char(RX, pkt, response=False)
        await asyncio.sleep(4)
        for k, u in INFO.items():
            try:
                v = bytes(await asyncio.wait_for(c.read_gatt_char(u), 10))
                print(f"{k}: {v.hex()} {v.decode('ascii', 'replace')!r}", flush=True)
            except Exception as e:
                print(f"{k}: ERR {type(e).__name__} {e}", flush=True)


asyncio.run(main(sys.argv[1]))
