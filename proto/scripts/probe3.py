import asyncio, sys, time
from bleak import BleakClient, BleakScanner

T0 = time.time()


def log(m):
    print(f"[{time.time() - T0:5.2f}s] {m}", flush=True)


OPS = [
    ("read  2a00 name", "read", "00002a00-0000-1000-8000-00805f9b34fb"),
    ("read  2a26 fw", "read", "00002a26-0000-1000-8000-00805f9b34fb"),
    ("read  2a27 hw", "read", "00002a27-0000-1000-8000-00805f9b34fb"),
    ("notify de5bf729 (big)", "notify", "de5bf729-d711-4e47-af26-65e3012a5dc7"),
    ("notify 6e400003 (uart)", "notify", "6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
]


async def main(addr):
    dev = await BleakScanner.find_device_by_address(addr, timeout=20)
    c = BleakClient(dev, disconnected_callback=lambda _: log("DISCONNECTED"), timeout=20)
    await c.connect()
    log("connected")
    for name, kind, u in OPS:
        if not c.is_connected:
            log("link down, stop")
            break
        try:
            if kind == "read":
                v = bytes(await asyncio.wait_for(c.read_gatt_char(u), 8))
                log(f"{name}: {v.hex()} {v.decode('ascii', 'replace')!r}")
            else:
                await asyncio.wait_for(
                    c.start_notify(u, lambda _, d: log(f"RX {bytes(d).hex()}")), 8
                )
                log(f"{name}: OK")
        except Exception as e:
            log(f"{name}: {type(e).__name__} {e}")
    if c.is_connected:
        await c.disconnect()


asyncio.run(main(sys.argv[1]))
