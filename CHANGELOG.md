# Changelog

## 0.1.0

First release: Krapfen as an Android library (AAR).

- Plays Strudel code on Android: Strudel's pattern engine (core, mini,
  transpiler, tonal) in QuickJS, scheduled by Strudel's own scheduler on the
  audio clock and rendered by the native dough synth through AAudio.
- Sound calibrated against recordings of strudel.cc (examples/calibration*.strudel,
  tools/analyze): synth levels, linear envelopes, exact equal-power panning,
  filter response (exact biquad coefficients, resonance default 1) and reverb
  length and level per roomsize match within about 1 dB / 6%.
- Samples via `samples(...)` (maps, URLs, `github:` shorthand), downloaded,
  cached and decoded on the device.
- Public API: `Krapfen.initialize`, `play`, `evaluate`, `stop`,
  `pause`/`resume` (lifecycle-safe), `volume`, `status`, `outputPeak`,
  `logListener`, `shutdown`, and `startRecording`/`stopRecording` (WAV
  capture of the output, for analysis).
- Known gaps: soundfonts, `supersaw` and other superdough-only sounds and
  effects, sample `cut`/`loop`; dough's reverb colour differs from
  superdough's.
