"""Legge Device Information + Device Name. Solo letture; una connessione per characteristic (workaround BlueZ)."""
import asyncio, sys
from bleak import BleakClient, BleakScanner

TARGETS = {
    "device_name": "00002a00-0000-1000-8000-00805f9b34fb",
    "serial":      "00002a25-0000-1000-8000-00805f9b34fb",
    "fw_rev":      "00002a26-0000-1000-8000-00805f9b34fb",
    "system_id":   "00002a23-0000-1000-8000-00805f9b34fb",
    "hw_rev":      "00002a27-0000-1000-8000-00805f9b34fb",
}

async def read_one(dev, uuid):
    async with BleakClient(dev, timeout=30) as c:
        await asyncio.sleep(2)
        return bytes(await c.read_gatt_char(uuid))

async def main(addr):
    dev = await BleakScanner.find_device_by_address(addr, timeout=20)
    if not dev:
        sys.exit("device non trovato")
    for name, uuid in TARGETS.items():
        for attempt in range(3):
            try:
                v = await read_one(dev, uuid)
                print(f"{name:12} {v.hex()}  {v.decode('ascii','replace')!r}")
                break
            except Exception as e:
                err = f"{type(e).__name__}: {e}"
                await asyncio.sleep(2)
        else:
            print(f"{name:12} ERRORE {err}")

asyncio.run(main(sys.argv[1]))
