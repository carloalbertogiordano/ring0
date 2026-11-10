"""Legge Device Information + Device Name (sola lettura). Richiede ring bonded (vedi docs/ring-recon.md)."""
import asyncio, sys
from bleak import BleakClient, BleakScanner

TARGETS = {
    "device_name": "00002a00-0000-1000-8000-00805f9b34fb",
    "serial":      "00002a25-0000-1000-8000-00805f9b34fb",
    "fw_rev":      "00002a26-0000-1000-8000-00805f9b34fb",
    "hw_rev":      "00002a27-0000-1000-8000-00805f9b34fb",
    "system_id":   "00002a23-0000-1000-8000-00805f9b34fb",
}

async def main(addr):
    dev = await BleakScanner.find_device_by_address(addr, timeout=25)
    if not dev:
        sys.exit("device non trovato")
    async with BleakClient(dev, timeout=30) as c:
        await asyncio.sleep(1)
        for name, uuid in TARGETS.items():
            try:
                v = bytes(await asyncio.wait_for(c.read_gatt_char(uuid), 8))
                print(f"{name:12} {v.hex()}  {v.decode('ascii', 'replace')!r}")
            except Exception as e:
                print(f"{name:12} ERR {type(e).__name__} {e}")

asyncio.run(main(sys.argv[1]))
