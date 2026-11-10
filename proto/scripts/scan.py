import asyncio, sys
from bleak import BleakScanner


async def main(t=15):
    seen = await BleakScanner.discover(timeout=t, return_adv=True)
    for addr, (dev, adv) in sorted(seen.items(), key=lambda x: -x[1][1].rssi):
        print(
            f"{addr}  rssi={adv.rssi:4}  name={adv.local_name or dev.name!r}  uuids={adv.service_uuids}  mfr={ {k: v.hex() for k, v in adv.manufacturer_data.items()} }"
        )


asyncio.run(main(float(sys.argv[1]) if len(sys.argv) > 1 else 15))
