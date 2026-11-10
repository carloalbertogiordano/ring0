"""CMD 0x16: automatic HR monitoring settings.

Read:  request [0x16][0x01], response [0x16][0x01][enabled][interval_min].
Write: request [0x16][0x02][enabled: 1=yes, 2=no][interval_min].
The reply to a write is unreliable (the reference client notes it too):
the state is read back to confirm it.
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
    """Decodes the settings read response."""
    if pkt.cmd != CMD_HR_SETTINGS:
        msg = f"not a heart rate settings packet: cmd {pkt.cmd:#04x}"
        raise ValueError(msg)
    flag = pkt.payload[1]
    if flag not in (FLAG_ENABLED, FLAG_DISABLED):
        msg = f"unexpected enabled flag: {flag}"
        raise ValueError(msg)
    return HeartRateSettings(enabled=flag == FLAG_ENABLED, interval_minutes=pkt.payload[2])


def encode_read() -> bytes:
    """Payload of the read request."""
    return bytes([SUB_READ])


def encode_write(settings: HeartRateSettings) -> bytes:
    """Payload of the write request."""
    if not 0 < settings.interval_minutes <= MAX_INTERVAL:
        msg = f"interval must be 1..{MAX_INTERVAL} minutes: {settings.interval_minutes}"
        raise ValueError(msg)
    flag = FLAG_ENABLED if settings.enabled else FLAG_DISABLED
    return bytes([SUB_WRITE, flag, settings.interval_minutes])


async def read_hr_settings(client: RingClient) -> HeartRateSettings:
    """Reads the current settings."""
    return decode(await client.request(CMD_HR_SETTINGS, encode_read()))


async def write_hr_settings(client: RingClient, settings: HeartRateSettings) -> HeartRateSettings:
    """Writes the settings and returns the state read back from the ring."""
    await client.request(CMD_HR_SETTINGS, encode_write(settings))
    return await read_hr_settings(client)
