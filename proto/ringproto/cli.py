"""CLI minima: `python -m ringproto.cli <comando> <MAC>`."""

import argparse
import asyncio
import logging
from collections.abc import Awaitable, Callable

from ringproto.client import RingClient
from ringproto.commands.battery import read_battery
from ringproto.commands.set_time import set_time
from ringproto.connect import connect_ring

COMMANDS: dict[str, Callable[[RingClient], Awaitable[object]]] = {
    "battery": read_battery,
    "set-time": set_time,
}


async def _run(command: str, address: str) -> None:
    async with connect_ring(address) as ring:
        print(await COMMANDS[command](ring))


def main() -> None:
    """Entry point della CLI."""
    parser = argparse.ArgumentParser(prog="ringproto")
    parser.add_argument("command", choices=sorted(COMMANDS))
    parser.add_argument("address")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    asyncio.run(_run(args.command, args.address))


if __name__ == "__main__":
    main()
