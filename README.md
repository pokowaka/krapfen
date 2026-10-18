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
> Strudel's `core`, `mini`, `transpiler` and `tonal` (scales, chords,
> voicings) are bundled.

## Building

Requires JDK 17, the Android SDK with NDK 28.2.13676358 and CMake 3.31.6.

```sh
JAVA_HOME=/path/to/jdk-17 ./gradlew :krapfen:assembleRelease  # library AAR
JAVA_HOME=/path/to/jdk-17 ./gradlew :app:assembleDebug        # demo app
```

The project has two modules: `krapfen` (the library) and `app` (a demo that
uses only the library's public API).

### Releasing

Set `krapfen.version` in `gradle.properties`, add a `CHANGELOG.md` entry,
commit, and push a matching tag (`v0.1.0`). The GitHub workflow builds the
AAR, a Maven repository zip and the demo APK, and attaches them to a release.

The Strudel bundle (`krapfen/src/main/assets/krapfen/strudel.js`) is generated
from `third_party/strudel` and checked in, so the Android build does not need
Node. To regenerate it, and to run the desktop smoke tests with a QuickJS-ng
`qjs` binary:

`js/` dependencies come from the public npm registry (`package-lock.json`):

```sh
cd js && npm install && npm run build
qjs --std -I ../krapfen/src/main/assets/krapfen/strudel.js test/smoke.js
# from the repo root: scheduler -> dough messages, samples (fake host)
qjs --std -I js/test/capture-send.js -I krapfen/src/main/assets/krapfen/strudel.js js/test/schedule.js
qjs --std -I js/test/fake-host.js -I krapfen/src/main/assets/krapfen/strudel.js js/test/samples.js
qjs --std -I krapfen/src/main/assets/krapfen/strudel.js js/test/tonal.js
# dough calibration measurements (level, reverb RT60)
tools/dough-bench/run.sh
```

## Goals

- Run Strudel patterns (`note("c e g").s("sine")`, `stack`, `arrange`, …)
  natively on Android, without a browser or WebView.
- Ship as an embeddable Android library (AAR) so apps and games can play
  pattern music in real time, plus a small player/editor app.

## Using Krapfen in an Android app

Download `krapfen-<version>.aar` from the
[releases](https://github.com/pokowaka/krapfen/releases) into your app's
`libs/` directory and add it as a dependency (Kotlin app, minSdk 26+):

```kotlin
dependencies {
    implementation(files("libs/krapfen-0.1.0.aar"))
}
```

Each release also has `krapfen-<version>-maven-repo.zip`, a Maven
repository with the AAR, sources and POM
(`io.github.pokowaka:krapfen:<version>`), if you prefer to host it.

```kotlin
import dev.krapfen.Krapfen

class MusicActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Krapfen.logListener = Krapfen.LogListener { level, msg -> Log.println(Log.INFO, "strudel", msg) }
        Krapfen.initialize(this) { ready ->
            Krapfen.play("""
                setcpm(30)
                ${'$'}: note("<c3 eb3 g3 bb3>").s("sawtooth").lpf(800).room(.5)
            """.trimIndent()) { result ->
                if (result is Krapfen.Result.Failure) Log.e("music", result.message)
            }
        }
    }

    override fun onStart() { super.onStart(); Krapfen.resume() }
    override fun onStop() { super.onStop(); Krapfen.pause() }
}
```

- `play(code)` evaluates Strudel code and plays it. Calling it again while
  playing swaps the pattern on the running clock, as on strudel.cc.
- `stop()`, `volume` (1.0 = strudel.cc loudness), `status {}`, `outputPeak`.
- `resume()`/`pause()` are counted and the release of the audio device is
  deferred by 500 ms, so rotation and switching activities don't interrupt
  playback. Pair each `resume()` with a `pause()`.
- Callbacks run on the main thread. Krapfen is a process-wide singleton.
- The library adds the `INTERNET` permission (for `samples(...)`) and ships
  the R8/ProGuard rules it needs. It adds about 1-1.5 MB of native code per
  ABI plus a ~1 MB JavaScript bundle.

> [!IMPORTANT]
> Krapfen is AGPL-3.0-or-later (it contains Strudel and dough). An app that
> includes it must be distributed under compatible terms, including making
> its source available to users.

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
