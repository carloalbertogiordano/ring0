"""CLI minima: `python -m ringproto.cli [-v] <comando> <MAC> [opzioni]`."""

import argparse
import asyncio
import logging
from collections.abc import Awaitable, Callable
from datetime import UTC, date, datetime, timedelta

from ringproto.client import RingClient
from ringproto.commands.auto_monitor import Feature, read_enabled, write_enabled
from ringproto.commands.battery import read_battery
from ringproto.commands.hr_log import read_hr_log
from ringproto.commands.hr_settings import HeartRateSettings, read_hr_settings, write_hr_settings
from ringproto.commands.set_time import set_time
from ringproto.commands.slot_log import SlotLog, read_hrv_log, read_stress_log
from ringproto.commands.temperature import read_temperature
from ringproto.connect import RingSession, connect_ring

Handler = Callable[[RingClient, argparse.Namespace], Awaitable[object]]


async def _battery(ring: RingClient, _args: argparse.Namespace) -> object:
    return await read_battery(ring)


async def _set_time(ring: RingClient, _args: argparse.Namespace) -> object:
    return await set_time(ring)


async def _hr_settings(ring: RingClient, args: argparse.Namespace) -> object:
    if args.disable:
        return await write_hr_settings(ring, HeartRateSettings(False, args.interval or 5))
    if args.interval:
        return await write_hr_settings(ring, HeartRateSettings(True, args.interval))
    return await read_hr_settings(ring)


async def _monitors(ring: RingClient, args: argparse.Namespace) -> object:
    wanted = {name.upper() for name in args.enable or []}
    for feature in Feature:
        if feature.name in wanted:
            await write_enabled(ring, feature, enabled=True)
    return {f.name.lower(): await read_enabled(ring, f) for f in Feature}


async def _hr_log(ring: RingClient, args: argparse.Namespace) -> object:
    day = args.date or datetime.now(UTC).date()
    log = await read_hr_log(ring, datetime(day.year, day.month, day.day, tzinfo=UTC))
    if log is None:
        return "no data"
    print(f"day_start={log.day_start.isoformat()} interval={log.interval_minutes}min")
    for when, bpm in log.readings():
        print(f"{when.isoformat()} {bpm}")
    return f"{len(log.readings())} readings"


def _show_slots(log: SlotLog | None) -> str:
    if log is None:
        return "no data"
    day = datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0)
    lines = [f"{when.isoformat()} {value}" for when, value in log.readings(day)]
    return "\n".join([f"interval={log.interval_minutes}min", *lines, f"{len(lines)} readings"])


async def _stress_log(ring: RingClient, _args: argparse.Namespace) -> str:
    return _show_slots(await read_stress_log(ring))


async def _hrv_log(ring: RingClient, args: argparse.Namespace) -> str:
    return _show_slots(await read_hrv_log(ring, args.days_ago))


async def _raw(ring: RingClient, args: argparse.Namespace) -> str:
    """Esplora un comando V1: invia `--cmd/--payload` e raccoglie fino a `--count` pacchetti."""
    count = args.count
    packets = await ring.request_many(
        args.cmd,
        bytes.fromhex(args.payload),
        is_last=lambda p: len(p) >= count or p[-1].payload[0] == 0xFF,
        timeout=3,
    )
    return "\n".join(f"{p.cmd:#04x} {p.payload.hex()}" for p in packets)


COMMANDS: dict[str, Handler] = {
    "battery": _battery,
    "set-time": _set_time,
    "hr-settings": _hr_settings,
    "hr-log": _hr_log,
    "monitors": _monitors,
    "stress-log": _stress_log,
    "hrv-log": _hrv_log,
    "raw": _raw,
}


async def _temperature(session: RingSession, args: argparse.Namespace) -> str:
    today = datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0)
    lines: list[str] = []
    for day in await read_temperature(session.bigdata, args.days):
        start = today - timedelta(days=day.days_ago)
        lines += [f"{when.isoformat()} {celsius:.1f}C" for when, celsius in day.readings(start)]
    return "\n".join([*lines, f"{len(lines)} readings"])


async def _run(args: argparse.Namespace) -> None:
    async with connect_ring(args.address) as session:
        if args.command == "temperature":
            print(await _temperature(session, args))
            return
        if args.command == "bigdata":
            frame = await session.bigdata.request(args.type, bytes.fromhex(args.payload))
            print(f"type={frame.type_id:#04x} len={len(frame.payload)}")
            print(frame.payload.hex())
            return
        print(await COMMANDS[args.command](session.v1, args))


def main() -> None:
    """Entry point della CLI."""
    parser = argparse.ArgumentParser(prog="ringproto")
    parser.add_argument("-v", "--verbose", action="store_true", help="log dei pacchetti raw")
    parser.add_argument("command", choices=[*sorted(COMMANDS), "bigdata", "temperature"])
    parser.add_argument("address")
    parser.add_argument("--interval", type=int, help="hr-settings: abilita e imposta i minuti")
    parser.add_argument("--enable", nargs="+", help="monitors: spo2 stress hrv temperature")
    parser.add_argument("--cmd", type=lambda v: int(v, 0), help="raw: id comando V1")
    parser.add_argument("--count", type=int, default=1, help="raw: pacchetti da attendere")
    parser.add_argument("--days-ago", type=int, default=0, help="hrv-log: giorni fa")
    parser.add_argument("--days", type=int, default=2, help="temperature: giorni richiesti")
    parser.add_argument("--disable", action="store_true", help="hr-settings: disabilita")
    parser.add_argument("--date", type=date.fromisoformat, help="hr-log: giorno YYYY-MM-DD (UTC)")
    parser.add_argument("--type", type=lambda v: int(v, 0), help="bigdata: tipo (es. 0x25)")
    parser.add_argument("--payload", default="", help="bigdata: payload hex della richiesta")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    if args.verbose:
        logging.getLogger("ringproto").setLevel(logging.DEBUG)
    asyncio.run(_run(args))


if __name__ == "__main__":
    main()
