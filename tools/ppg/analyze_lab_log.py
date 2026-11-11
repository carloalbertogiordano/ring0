"""Analyse a raw type-13 recording taken with the debug lab receiver (adb logcat -s Lab:I > log.txt).

  uv run --with numpy --with scipy python tools/ppg/analyze_lab_log.py log.txt

Packets are [type][error][seq][status][u16 A][u16 B][s16 x][s16 y][s16 z]. The time axis comes from the sequence number (about 4.5% of the
notifications are lost), lost samples are filled linearly, the signal is band-passed (0.6-3.5 Hz), upsampled 8x and peaks give beat times.
Raw recordings are health data: keep them out of the repository.
"""
import re
import struct
import sys

import numpy as np
from scipy.interpolate import CubicSpline
from scipy.signal import butter, filtfilt, find_peaks

FS = 25.0
rows = []
for line in open(sys.argv[1]):
    m = re.search(r"t=(\d+)ms ([0-9a-f]{28})", line)
    if m:
        rows.append(bytes.fromhex(m.group(2)))
packets = rows[3:]  # the first packets are zeros while the sensor starts
seq = np.array([p[2] for p in packets])
index = np.zeros(len(seq))
for i in range(1, len(seq)):
    index[i] = index[i - 1] + ((seq[i] - seq[i - 1]) % 256 or 256)
print(f"{len(packets)} packets over {index[-1] / FS:.1f} s, loss {100 * (1 - len(packets) / (index[-1] + 1)):.1f}%")


def beats(offset):
    x = np.interp(np.arange(int(index[-1]) + 1), index, [struct.unpack_from("<H", p, offset)[0] for p in packets])
    b, a = butter(3, [0.6 / (FS / 2), 3.5 / (FS / 2)], btype="band")
    f = filtfilt(b, a, x - x.mean())
    t = np.arange(len(f)) / FS
    tu = np.arange(0, t[-1], 1 / (FS * 8))
    fu = CubicSpline(t, f)(tu)
    best = max(((find_peaks(s * fu, distance=int(0.4 * FS * 8), prominence=0.35 * np.std(fu))[0]) for s in (1, -1)), key=len)
    return tu[best]


for name, off in (("A", 4), ("B", 6)):
    rr = np.diff(beats(off)) * 1000
    good = rr[(rr > 400) & (rr < 1500)]
    d = np.diff(good)
    print(f"{name}: {len(rr) + 1} beats, mean RR {good.mean():.0f} ms, RMSSD {np.sqrt(np.mean(d * d)):.1f} ms, "
          f"|dRR|>200 ms: {100 * np.mean(np.abs(np.diff(rr)) > 200):.0f}% (artefacts of the naive detector)")
