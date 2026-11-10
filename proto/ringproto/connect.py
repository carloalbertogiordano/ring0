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
from dataclasses import dataclass

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError

from ringproto.bigdata_client import BigDataClient
from ringproto.ble import BIG_NOTIFY_CHAR, BIG_WRITE_CHAR, BleakTransport
from ringproto.client import RingClient

log = logging.getLogger(__name__)

RELEASE_WAIT = 3.0
SCAN_TIMEOUT = 25.0
CONNECT_TIMEOUT = 30.0


@dataclass(frozen=True)
class RingSession:
    """Client dei due canali del ring: comandi V1 (16 byte) e big-data V2."""

    v1: RingClient
    bigdata: BigDataClient


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
async def connect_ring(address: str, retries: int = 4) -> AsyncIterator[RingSession]:
    """Connette al ring e restituisce i client dei due canali; disconnette all'uscita."""
    bleak_client = await _connect(address, retries)
    try:
        v1_transport = BleakTransport(bleak_client)
        v2_transport = BleakTransport(
            bleak_client, write_char=BIG_WRITE_CHAR, notify_char=BIG_NOTIFY_CHAR
        )
        session = RingSession(RingClient(v1_transport), BigDataClient(v2_transport))
        await v1_transport.start()
        await v2_transport.start()
        yield session
    finally:
        await bleak_client.disconnect()
