#!/usr/bin/env python3
"""Measures recordings of examples/calibration-reverb.strudel.

    tools/analyze/reverb.py krapfen.wav [strudel.wav]

Sections are 4 s each, aligned to the first onset: blips into reverb at
roomsize .5/1/2/4/8 (room 1), roomsize 4 at room .5, then bare sawtooth and
square notes. For the blips it reports the wet level (RMS 0.1-0.6 s after
the blip, when the dry sound is over) and the RT60 (Schroeder, T20); for the
waveforms the RMS while the note holds.
"""
import sys

import numpy as np

from calibration import db, find_onset, read_wav

SECTION = 4.0
BLIPS = [(0, ".5"), (1, "1"), (2, "2"), (3, "4"), (4, "8"), (5, "4 @ room .5")]
NOTES = [(6, "sawtooth"), (7, "square")]


def schroeder_rt60(m, rate):
    e = np.cumsum((m ** 2)[::-1])[::-1]
    d = 10 * np.log10(e / e[0] + 1e-30)
    t5, t25 = np.argmax(d <= -5) / rate, np.argmax(d <= -25) / rate
    return (t25 - t5) * 3 if t25 > t5 else float("nan")


def analyze(path):
    x, rate = read_wav(path)
    x = x[find_onset(x, rate):]
    out = {"blips": [], "notes": []}
    for i, name in BLIPS:
        a = int(i * SECTION * rate)
        m = x[a:a + int(SECTION * rate)].mean(axis=1)
        wet = m[int(0.1 * rate):int(0.6 * rate)]
        out["blips"].append((name, np.sqrt((wet ** 2).mean()), schroeder_rt60(m[int(0.1 * rate):], rate)))
    for i, name in NOTES:
        a = int((i * SECTION + 0.1) * rate)
        seg = x[a:a + int(0.8 * rate)]
        out["notes"].append((name, np.sqrt((seg ** 2).mean())))
    return out


def main(paths):
    results = [analyze(p) for p in paths]
    print(f"{'roomsize':<14}" + "".join(f"{'wet dB':>9}{'RT60 s':>9}" for _ in results))
    for row in zip(*[r["blips"] for r in results]):
        print(f"{row[0][0]:<14}" + "".join(f"{db(w):9.1f}{t:9.2f}" for _, w, t in row))
    print(f"{'note':<14}" + "".join(f"{'rms dB':>9}{'':>9}" for _ in results))
    for row in zip(*[r["notes"] for r in results]):
        print(f"{row[0][0]:<14}" + "".join(f"{db(v):9.1f}{'':>9}" for _, v in row))


if __name__ == "__main__":
    main(sys.argv[1:3])
