#!/usr/bin/env python3
"""Fits Krapfen's reverb mapping to strudel.cc measurements.

For each roomsize, finds the dough decay whose RT60 matches strudel.cc's
measured RT60 and the send scale that matches its wet level, by rendering
the calibration blip offline with tools/dough-bench (`msg` mode, which
applies Krapfen's master gain and measures like tools/analyze/reverb.py).

    tools/analyze/fit_reverb.py

The strudel.cc targets come from recording
app/src/main/assets/krapfen/examples/calibration-reverb.strudel on
strudel.cc and running tools/analyze/reverb.py on it.
"""
import math
import os
import re
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BENCH = os.path.join(ROOT, "build", "dough-bench")

# roomsize: (RT60 s, wet dB) measured from strudel.cc (room 1).
STRUDEL = {0.5: (0.48, -49.5), 1: (1.01, -45.0), 2: (2.10, -37.2), 4: (4.20, -36.6), 8: (6.90, -35.9)}


def measure(decay, room=1.0):
    msg = (f"dough/play/time/0/duration/2/s/sine/note/60/decay/0.05/sustain/0/gain/0.8/"
           f"attack/0.001/release/0.01/room/{room:.6f}/roomdecay/{decay:.6f}")
    out = subprocess.run([BENCH, "msg", msg], capture_output=True, text=True, check=True).stdout
    wet, rt = re.search(r"wet\s+(-?[\d.]+) dB\s+RT60\s+([\d.]+)", out).groups()
    return float(wet), float(rt)


def fit_decay(target_rt):
    lo, hi = 0.0, 0.99
    for _ in range(18):
        mid = (lo + hi) / 2
        if measure(mid)[1] < target_rt:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def main():
    subprocess.run([os.path.join(ROOT, "tools", "dough-bench", "run.sh"), "none"], check=False,
                   capture_output=True)
    rows = []
    for size, (rt, wet) in sorted(STRUDEL.items()):
        d = fit_decay(rt)
        k_wet, k_rt = measure(d)
        scale = 10 ** ((wet - k_wet) / 20)  # the send is linear in `room`
        v_wet, v_rt = measure(d, scale)
        rows.append((size, d, scale))
        print(f"roomsize {size:<4} decay {d:.4f} send x{scale:.4f}  ->  wet {v_wet:6.1f} dB (target {wet})"
              f"  RT60 {v_rt:.2f} s (target {rt})")
    print("\n// [roomsize, dough decay, room send scale]")
    print("const REVERB_FIT = [\n" + "\n".join(f"  [{s}, {d:.4f}, {c:.4f}]," for s, d, c in rows) + "\n];")


if __name__ == "__main__":
    main()
