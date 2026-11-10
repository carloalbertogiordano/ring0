import asyncio, sys, time
from bleak import BleakClient, BleakScanner

RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"


def packet(cmd, payload=b""):
    b = (bytes([cmd]) + payload).ljust(15, b"\0")
    return b + bytes([sum(b) & 0xFF])


async def main(addr):
    dev = await BleakScanner.find_device_by_address(addr, timeout=25)
    t0 = time.time()

    def log(m):
        print(f"[{time.time() - t0:5.2f}s] {m}", flush=True)

    c = BleakClient(dev, disconnected_callback=lambda _: log("DISCONNECTED"), timeout=30)
    await c.connect()
    log(f"connected mtu={c.mtu_size}")
    for i in range(3):
        try:
            await c.start_notify(TX, lambda _, d: log(f"RX {bytes(d).hex()}"))
            log("notify ok")
            break
        except Exception as e:
            log(f"notify err {e}")
            await asyncio.sleep(1)
    if c.is_connected:
        await c.write_gatt_char(RX, packet(3), response=False)
        log("TX " + packet(3).hex())
        await asyncio.sleep(5)
    log(f"still connected={c.is_connected}")
    if c.is_connected:
        await c.disconnect()


asyncio.run(main(sys.argv[1]))
