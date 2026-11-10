"""Preferenze di monitoraggio automatico: SpO2, stress, HRV, temperatura.

Lettura:   [cmd][prefisso][0x01]
Scrittura: [cmd][prefisso][0x02][0|1]
Risposta:  flag "abilitato" subito dopo prefisso e sotto-comando.
Il prefisso e' vuoto salvo che per la temperatura (0x03). Come per l'HR, la risposta alla
scrittura non e' affidabile: si rilegge lo stato.
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
        """Byte che precedono il sotto-comando nel payload."""
        return bytes(self.value[1])


def encode_read(feature: Feature) -> bytes:
    """Payload della richiesta di lettura."""
    return feature.prefix + bytes([SUB_READ])


def encode_write(feature: Feature, *, enabled: bool) -> bytes:
    """Payload della richiesta di scrittura."""
    return feature.prefix + bytes([SUB_WRITE, int(enabled)])


def decode(feature: Feature, pkt: Packet) -> bool:
    """True se la misura automatica e' abilitata."""
    if pkt.cmd != feature.cmd:
        msg = f"not a {feature.name.lower()} preference packet: cmd {pkt.cmd:#04x}"
        raise ValueError(msg)
    return pkt.payload[len(feature.prefix) + 1] == 1


async def read_enabled(client: RingClient, feature: Feature) -> bool:
    """Legge se la misura automatica e' attiva."""
    return decode(feature, await client.request(feature.cmd, encode_read(feature)))


async def write_enabled(client: RingClient, feature: Feature, *, enabled: bool) -> bool:
    """Abilita/disabilita la misura e restituisce lo stato riletto dal ring."""
    await client.request(feature.cmd, encode_write(feature, enabled=enabled))
    return await read_enabled(client, feature)
