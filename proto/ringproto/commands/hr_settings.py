"""CMD 0x16 impostazioni del monitoraggio HR automatico.

Lettura:  richiesta [0x16][0x01], risposta [0x16][0x01][abilitato][intervallo_min].
Scrittura: richiesta [0x16][0x02][abilitato: 1=si, 2=no][intervallo_min].
La risposta alla scrittura non e' affidabile (lo nota anche il client di riferimento):
si rilegge lo stato per confermarla.
"""

from dataclasses import dataclass

from ringproto.client import RingClient
from ringproto.packet import Packet

CMD_HR_SETTINGS = 0x16
SUB_READ = 1
SUB_WRITE = 2
FLAG_ENABLED = 1
FLAG_DISABLED = 2
MAX_INTERVAL = 255


@dataclass(frozen=True)
class HeartRateSettings:
    enabled: bool
    interval_minutes: int


def decode(pkt: Packet) -> HeartRateSettings:
    """Decodifica la risposta di lettura delle impostazioni."""
    if pkt.cmd != CMD_HR_SETTINGS:
        msg = f"not a heart rate settings packet: cmd {pkt.cmd:#04x}"
        raise ValueError(msg)
    flag = pkt.payload[1]
    if flag not in (FLAG_ENABLED, FLAG_DISABLED):
        msg = f"unexpected enabled flag: {flag}"
        raise ValueError(msg)
    return HeartRateSettings(enabled=flag == FLAG_ENABLED, interval_minutes=pkt.payload[2])


def encode_read() -> bytes:
    """Payload della richiesta di lettura."""
    return bytes([SUB_READ])


def encode_write(settings: HeartRateSettings) -> bytes:
    """Payload della richiesta di scrittura."""
    if not 0 < settings.interval_minutes <= MAX_INTERVAL:
        msg = f"interval must be 1..{MAX_INTERVAL} minutes: {settings.interval_minutes}"
        raise ValueError(msg)
    flag = FLAG_ENABLED if settings.enabled else FLAG_DISABLED
    return bytes([SUB_WRITE, flag, settings.interval_minutes])


async def read_hr_settings(client: RingClient) -> HeartRateSettings:
    """Legge le impostazioni correnti."""
    return decode(await client.request(CMD_HR_SETTINGS, encode_read()))


async def write_hr_settings(client: RingClient, settings: HeartRateSettings) -> HeartRateSettings:
    """Scrive le impostazioni e restituisce lo stato riletto dal ring."""
    await client.request(CMD_HR_SETTINGS, encode_write(settings))
    return await read_hr_settings(client)
