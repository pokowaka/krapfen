# Krapfen 🍩

Live-coding pattern music on Android — an **unofficial**, independent port of
the [Strudel](https://codeberg.org/uzu/strudel) pattern language and the
[dough](https://codeberg.org/uzu/dough) synth engine to native Android.

> **Status:** early proof of concept. Strudel code runs in an embedded
> QuickJS engine and plays through the native dough synth on Android, using
> Strudel's own scheduler on dough's audio clock.
>
> Loudness, synth envelopes, panning and reverb length are calibrated
> against superdough (see `tools/dough-bench`). Samples load at runtime via
> `samples(...)` as on strudel.cc (no banks are preloaded).
>
> Known gaps: `tonal` (scales, chords, voicings) is not bundled yet.

## Building

Requires JDK 17, the Android SDK with NDK 28.2.13676358 and CMake 3.31.6.

```sh
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

The Strudel bundle (`app/src/main/assets/krapfen/strudel.js`) is generated
from `third_party/strudel` and checked in, so the Android build does not need
Node. To regenerate it, and to run the desktop smoke tests with a QuickJS-ng
`qjs` binary:

```sh
cd js && npm install && npm run build
qjs --std -I ../app/src/main/assets/krapfen/strudel.js test/smoke.js
# from the repo root: scheduler -> dough messages, samples (fake host)
qjs --std -I js/test/capture-send.js -I app/src/main/assets/krapfen/strudel.js js/test/schedule.js
qjs --std -I js/test/fake-host.js -I app/src/main/assets/krapfen/strudel.js js/test/samples.js
# dough calibration measurements (level, reverb RT60)
tools/dough-bench/run.sh
```

## Goals

- Run Strudel patterns (`note("c e g").s("sine")`, `stack`, `arrange`, …)
  natively on Android, without a browser or WebView.
- Ship as an embeddable Android library (AAR) so apps and games can play
  pattern music in real time, plus a small player/editor app.

## Architecture (planned)

```
Strudel code ──► QuickJS (core + mini + transpiler) ──► scheduler queries haps
                                                              │ hap2dough
                                                              ▼
                                          dough.h (C, NDK) ──► miniaudio ──► AAudio
```

## Relationship to Strudel

Krapfen is **not affiliated with or endorsed by** the Strudel / uzu project.
It is a separate project and does not send contributions upstream. Please do
not report Krapfen issues to the Strudel or dough trackers.

## AI disclosure

This project is developed with substantial assistance from AI coding tools.

## Credits & licence

- [Strudel](https://codeberg.org/uzu/strudel) — Felix Roos and the Strudel
  contributors (AGPL-3.0-or-later)
- [dough](https://codeberg.org/uzu/dough) — Felix Roos and contributors
  (AGPL-3.0-or-later)
- [miniaudio](https://miniaud.io/) — David Reid (public domain / MIT-0)
- [QuickJS-ng](https://github.com/quickjs-ng/quickjs) — QuickJS authors (MIT)

Krapfen is free software, licensed under the
[GNU Affero General Public License v3.0](LICENSE) or (at your option) any
later version.
