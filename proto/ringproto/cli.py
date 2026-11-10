"""CLI minima: `python -m ringproto.cli <comando> <MAC>`."""

import argparse
import asyncio
import logging

from ringproto.commands.battery import read_battery
from ringproto.connect import connect_ring


async def _battery(address: str) -> None:
    async with connect_ring(address) as ring:
        print(await read_battery(ring))


def main() -> None:
    """Entry point della CLI."""
    parser = argparse.ArgumentParser(prog="ringproto")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("battery").add_argument("address")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    if args.command == "battery":
        asyncio.run(_battery(args.address))


if __name__ == "__main__":
    main()
