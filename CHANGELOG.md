# Changelog

## 0.1.0

First release: Krapfen as an Android library (AAR).

- Plays Strudel code on Android: Strudel's pattern engine (core, mini,
  transpiler, tonal) in QuickJS, scheduled by Strudel's own scheduler on the
  audio clock and rendered by the native dough synth through AAudio.
- Loudness, synth envelopes, panning and reverb length calibrated against
  superdough (strudel.cc's engine).
- Samples via `samples(...)` (maps, URLs, `github:` shorthand), downloaded,
  cached and decoded on the device.
- Public API: `Krapfen.initialize`, `play`, `evaluate`, `stop`,
  `pause`/`resume` (lifecycle-safe), `volume`, `status`, `outputPeak`,
  `logListener`, `shutdown`.
- Known gaps: soundfonts, `supersaw` and other superdough-only sounds and
  effects, sample `cut`/`loop`; dough's reverb colour differs from
  superdough's.
