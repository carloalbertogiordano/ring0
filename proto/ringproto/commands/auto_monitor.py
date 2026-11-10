"""Automatic monitoring preferences: SpO2, stress, HRV, temperature.

Read:     [cmd][prefix][0x01]
Write:    [cmd][prefix][0x02][0|1]
Response: "enabled" flag right after the prefix and sub-command.
The prefix is empty except for temperature (0x03). As with HR, the reply to a write is
unreliable: the state is read back instead.
"""

from enum import Enum

from ringproto.client import RingClient
from ringproto.packet import Packet

SUB_READ = 1
SUB_WRITE = 2


class Feature(Enum):
    """Misura automatica configurabile: (id comando, prefisso payload)."""

    SPO2 = (0x2C, b"")
    STRESS = (0x36, b"")
    HRV = (0x38, b"")
    TEMPERATURE = (0x3A, b"\x03")

    @property
    def cmd(self) -> int:
        """Id del comando V1."""
        return int(self.value[0])

    @property
    def prefix(self) -> bytes:
        """Bytes preceding the sub-command in the payload."""
        return bytes(self.value[1])


def encode_read(feature: Feature) -> bytes:
    """Payload of the read request."""
    return feature.prefix + bytes([SUB_READ])


def encode_write(feature: Feature, *, enabled: bool) -> bytes:
    """Payload of the write request."""
    return feature.prefix + bytes([SUB_WRITE, int(enabled)])


def decode(feature: Feature, pkt: Packet) -> bool:
    """True if the automatic measurement is enabled."""
    if pkt.cmd != feature.cmd:
        msg = f"not a {feature.name.lower()} preference packet: cmd {pkt.cmd:#04x}"
        raise ValueError(msg)
    return pkt.payload[len(feature.prefix) + 1] == 1


async def read_enabled(client: RingClient, feature: Feature) -> bool:
    """Reads whether the automatic measurement is on."""
    return decode(feature, await client.request(feature.cmd, encode_read(feature)))


async def write_enabled(client: RingClient, feature: Feature, *, enabled: bool) -> bool:
    """Enables/disables the measurement and returns the state read back from the ring."""
    await client.request(feature.cmd, encode_write(feature, enabled=enabled))
    return await read_enabled(client, feature)
