"""Connessione robusta al ring su Linux/BlueZ (verificata col ring reale).

Vedi docs/ring-recon.md.

Con il ring bonded, bluetoothd lo tiene connesso e il ring non si pubblicizza piu': prima si
rilascia la connessione, si attende, poi scan + connect con retry.
"""

import asyncio
import logging
import shutil
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError

from ringproto.ble import BleakTransport
from ringproto.client import RingClient

log = logging.getLogger(__name__)

RELEASE_WAIT = 3.0
SCAN_TIMEOUT = 25.0
CONNECT_TIMEOUT = 30.0


class RingNotReachableError(ConnectionError):
    """Il ring non e' stato trovato o la connessione e' fallita dopo tutti i tentativi."""


async def _release_from_bluez(address: str) -> None:
    exe = shutil.which("bluetoothctl")
    if exe is None:
        return
    proc = await asyncio.create_subprocess_exec(
        exe,
        "disconnect",
        address,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.DEVNULL,
    )
    await proc.wait()
    await asyncio.sleep(RELEASE_WAIT)


async def _connect(address: str, retries: int) -> BleakClient:
    last: Exception | None = None
    for attempt in range(1, retries + 1):
        await _release_from_bluez(address)
        device = await BleakScanner.find_device_by_address(address, timeout=SCAN_TIMEOUT)
        if device is None:
            log.warning("attempt %d/%d: ring not advertising", attempt, retries)
            continue
        client = BleakClient(device, timeout=CONNECT_TIMEOUT)
        try:
            await client.connect()
        except (BleakError, TimeoutError) as exc:
            log.warning("attempt %d/%d: connect failed: %s", attempt, retries, exc)
            last = exc
            continue
        return client
    msg = f"ring {address} not reachable after {retries} attempts"
    raise RingNotReachableError(msg) from last


@asynccontextmanager
async def connect_ring(address: str, retries: int = 4) -> AsyncIterator[RingClient]:
    """Connette al ring e restituisce un `RingClient` pronto; disconnette all'uscita."""
    bleak_client = await _connect(address, retries)
    try:
        transport = BleakTransport(bleak_client)
        ring = RingClient(transport)
        await transport.start()
        yield ring
    finally:
        await bleak_client.disconnect()
