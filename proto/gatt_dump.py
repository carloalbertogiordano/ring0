"""Enumera servizi/caratteristiche GATT e legge quelle leggibili. Solo letture, nessuna scrittura."""
import asyncio, sys
from bleak import BleakClient, BleakScanner

async def main(addr):
    dev = await BleakScanner.find_device_by_address(addr, timeout=20)
    if not dev:
        sys.exit("device non trovato")
    async with BleakClient(dev, timeout=30) as c:
        print(f"connected={c.is_connected} mtu={c.mtu_size}")
        for s in c.services:
            print(f"\n[service] {s.uuid}  ({s.description})")
            for ch in s.characteristics:
                line = f"  [char] {ch.uuid}  handle={ch.handle}  props={','.join(ch.properties)}  ({ch.description})"
                if "read" in ch.properties:
                    try:
                        v = await c.read_gatt_char(ch)
                        line += f"\n         value={bytes(v).hex()}  ascii={bytes(v).decode('ascii', 'replace')!r}"
                    except Exception as e:
                        line += f"\n         read error: {e}"
                print(line)
                for d in ch.descriptors:
                    print(f"      [desc] {d.uuid} handle={d.handle}")

asyncio.run(main(sys.argv[1]))
