# Krapfen 🍩

Live-coding pattern music on Android — an **unofficial**, independent port of
the [Strudel](https://codeberg.org/uzu/strudel) pattern language and the
[dough](https://codeberg.org/uzu/dough) synth engine to native Android.

> **Status:** early proof of concept. Nothing works yet.

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
