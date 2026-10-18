#!/usr/bin/env python3
"""Measures recordings of examples/calibration.strudel, to compare Krapfen
with strudel.cc objectively.

    tools/analyze/calibration.py krapfen.wav [strudel.wav]

The pattern runs at 2 s per cycle: 0 sine, 1 saw+lpf, 2 triangle left,
3 square right, 4 plucked arpeggio, 5-6 blip into reverb, 7-8 blip into
delay, 9 silence. The first onset is aligned to t=0; sections are measured
in fixed windows from there. Needs only numpy; reads PCM16/24/32 and float
WAV files.
"""
import struct
import sys

import numpy as np

CYCLE = 2.0
SECTIONS = [
    (0, 1, "sine c4"),
    (1, 1, "sawtooth c4 lpf 1000"),
    (2, 1, "triangle c4 pan 0"),
    (3, 1, "square c4 pan 1"),
    (4, 1, "sine arpeggio pluck"),
    (5, 2, "blip -> reverb (roomsize 4)"),
    (7, 2, "blip -> delay .25 s fb .5"),
]


def read_wav(path):
    data = open(path, "rb").read()
    assert data[:4] == b"RIFF" and data[8:12] == b"WAVE", "not a WAV file"
    pos, fmt, raw = 12, None, None
    while pos + 8 <= len(data):
        cid, size = data[pos:pos + 4], struct.unpack("<I", data[pos + 4:pos + 8])[0]
        body = data[pos + 8:pos + 8 + size]
        if cid == b"fmt ":
            fmt = struct.unpack("<HHIIHH", body[:16])
        elif cid == b"data":
            raw = body
        pos += 8 + size + (size & 1)
    tag, ch, rate, _, _, bits = fmt
    if tag == 0xFFFE:  # WAVE_FORMAT_EXTENSIBLE: guess float vs PCM from the data
        tag = 3 if bits == 32 and _is_float(raw) else 1
    if tag == 3:
        x = np.frombuffer(raw, dtype="<f4" if bits == 32 else "<f8").astype(np.float64)
    elif bits == 16:
        x = np.frombuffer(raw, dtype="<i2") / 32768.0
    elif bits == 24:
        b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3).astype(np.int32)
        v = b[:, 0] | (b[:, 1] << 8) | (b[:, 2] << 16)
        x = np.where(v >= 1 << 23, v - (1 << 24), v) / float(1 << 23)
    elif bits == 32:
        x = np.frombuffer(raw, dtype="<i4") / float(1 << 31)
    else:
        raise ValueError(f"unsupported WAV: tag {tag}, {bits} bits")
    x = x.reshape(-1, ch)
    if ch == 1:
        x = np.repeat(x, 2, axis=1)
    return x[:, :2], rate


def _is_float(raw):
    # Heuristic for extensible files: float data has small magnitudes.
    v = np.frombuffer(raw[: 4096 * 4], dtype="<f4")
    return np.all(np.isfinite(v)) and np.max(np.abs(v)) <= 4.0


def db(v):
    return 20 * np.log10(max(v, 1e-12))


def fundamental(x, rate):
    m = x.mean(axis=1)
    m = m * np.hanning(len(m))
    spec = np.abs(np.fft.rfft(m, n=1 << 18))
    freqs = np.fft.rfftfreq(1 << 18, 1 / rate)
    band = (freqs > 60) & (freqs < 2000)
    return freqs[band][np.argmax(spec[band])]


def centroid(x, rate):
    m = x.mean(axis=1)
    spec = np.abs(np.fft.rfft(m * np.hanning(len(m))))
    freqs = np.fft.rfftfreq(len(m), 1 / rate)
    return float((spec * freqs).sum() / max(spec.sum(), 1e-12))


def rt60(x, rate, start):
    """T20-based RT60 from the Schroeder integral after `start` seconds."""
    m = x[int(start * rate):].mean(axis=1)
    e = np.cumsum((m ** 2)[::-1])[::-1]
    if e[0] <= 0:
        return float("nan")
    d = 10 * np.log10(e / e[0] + 1e-30)
    try:
        t5 = np.argmax(d <= -5) / rate
        t25 = np.argmax(d <= -25) / rate
    except ValueError:
        return float("nan")
    return (t25 - t5) * 3 if t25 > t5 else float("nan")


def echoes(x, rate, start, length):
    """Peak level of the dry blip and the following echoes (250 ms apart)."""
    m = np.abs(x[int(start * rate):int((start + length) * rate)]).max(axis=1)
    out = []
    for k in range(6):
        a, b = int(k * 0.25 * rate), int((k * 0.25 + 0.12) * rate)
        out.append(m[a:b].max() if b <= len(m) else 0.0)
    return out


def find_onset(x, rate, threshold_db=-34.0, window_s=0.01):
    """Start of the first 10 ms window whose RMS exceeds threshold_db.

    Requiring sustained energy skips short clicks before the music, such as
    Android's touch sound when a recording is captured from an emulator.
    The first section (a sine at gain 0.8) is about -15 dB RMS.
    """
    m = x.mean(axis=1)
    w = int(window_s * rate)
    n = len(m) // w
    rms = np.sqrt((m[: n * w].reshape(n, w) ** 2).mean(axis=1))
    above = np.nonzero(rms > 10 ** (threshold_db / 20))[0]
    if len(above) == 0:
        raise ValueError("no onset found")
    i = above[0] * w
    # refine to the first sample above 1% of full scale in that window
    seg = np.abs(m[max(0, i - w): i + w])
    return max(0, i - w) + int(np.argmax(seg > 0.01))


def analyze(path):
    x, rate = read_wav(path)
    onset = find_onset(x, rate)
    x = x[onset:]
    res = {"file": path, "rate": rate, "onset_s": onset / rate, "sections": []}
    for cyc, n, name in SECTIONS:
        a, b = int(cyc * CYCLE * rate), int((cyc + n) * CYCLE * rate)
        seg = x[a:b]
        s = {
            "name": name,
            "peakL": np.abs(seg[:, 0]).max(),
            "peakR": np.abs(seg[:, 1]).max(),
            "rmsL": np.sqrt((seg[:, 0] ** 2).mean()),
            "rmsR": np.sqrt((seg[:, 1] ** 2).mean()),
            "f0": fundamental(seg[: int(0.5 * rate)], rate),
            "centroid": centroid(seg[: int(0.5 * rate)], rate),
        }
        if "reverb" in name:
            s["rt60"] = rt60(x[a:b], rate, 0.1)
        if "delay" in name:
            s["echoes"] = echoes(x, rate, cyc * CYCLE, n * CYCLE)
        res["sections"].append(s)
    return res


def show(r):
    print(f"\n{r['file']}  ({r['rate']} Hz, first onset at {r['onset_s']:.3f} s)")
    for s in r["sections"]:
        line = (f"  {s['name']:<30} peak L/R {db(s['peakL']):6.1f}/{db(s['peakR']):6.1f} dB"
                f"  rms L/R {db(s['rmsL']):6.1f}/{db(s['rmsR']):6.1f} dB"
                f"  f0 {s['f0']:6.1f} Hz  centroid {s['centroid']:6.0f} Hz")
        if "rt60" in s:
            line += f"  RT60 {s['rt60']:.2f} s"
        if "echoes" in s:
            line += "  echoes(dB) " + " ".join(f"{db(e):.1f}" for e in s["echoes"])
        print(line)


def compare(a, b):
    print("\ndifference (second - first)")
    for sa, sb in zip(a["sections"], b["sections"]):
        line = (f"  {sa['name']:<30} peak L/R {db(sb['peakL']) - db(sa['peakL']):+5.1f}/"
                f"{db(sb['peakR']) - db(sa['peakR']):+5.1f} dB"
                f"  rms L/R {db(sb['rmsL']) - db(sa['rmsL']):+5.1f}/{db(sb['rmsR']) - db(sa['rmsR']):+5.1f} dB"
                f"  f0 {sb['f0'] - sa['f0']:+5.1f} Hz  centroid {sb['centroid'] - sa['centroid']:+6.0f} Hz")
        if "rt60" in sa:
            line += f"  RT60 {sb['rt60'] - sa['rt60']:+.2f} s"
        print(line)


if __name__ == "__main__":
    results = [analyze(p) for p in sys.argv[1:3]]
    for r in results:
        show(r)
    if len(results) == 2:
        compare(*results)
