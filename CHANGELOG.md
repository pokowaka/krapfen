# Changelog

## 0.2.0

Sound effects: Krapfen can now replace an app's whole audio engine.

- `Krapfen.loadSound(name, bytes)` decodes encoded audio (wav, ogg, mp3, flac,
  aac, ...) into the engine and returns a `Sound`; loaded sounds are also
  playable from patterns as `s("name")`.
- `playSound(sound, volume, pan, speed)` plays a one-shot now;
  `loopSound(...)` loops seamlessly. Both return a `SoundHandle` with live
  `volume`/`pan` and `stop()`.
- `musicVolume` and `effectsVolume`, alongside the overall `volume`.
- `stop()` now only silences the pattern; sound effects keep playing.
- Strudel's `loop` control works on samples.
- Pattern samples now play at superdough's level (they were 3.5 dB louder;
  derived from both engines' code).
- 128 voices (was 64); separate sample slots for pattern samples and app
  sound effects.

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
