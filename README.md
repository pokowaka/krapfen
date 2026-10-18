# Krapfen 🍩

**Play [Strudel](https://strudel.cc) patterns natively on Android.**

Krapfen is an Android library (AAR) that runs Strudel code — the same code
you write on strudel.cc — inside your app, with no browser or WebView. Use it
for live-coded music, adaptive game soundtracks, or anything that wants
pattern-based audio.

```kotlin
Krapfen.initialize(context) {
    Krapfen.play("""note("<c3 eb3 g3 bb3>").s("sawtooth").lpf(800).room(.5)""")
}
```

> [!NOTE]
> Krapfen is an **unofficial**, independent project. It is not affiliated
> with or endorsed by the Strudel / uzu project. Please don't report Krapfen
> issues to the Strudel or dough trackers.

## How it works

```
Strudel code ─► QuickJS ─► Strudel's own pattern engine + scheduler ─► dough synth (C) ─► AAudio
                (embedded JS engine)  (core, mini, transpiler, tonal)    (native, NDK)
```

- Your code is evaluated by **Strudel's real pattern engine**
  ([`@strudel/core`, `mini`, `transpiler`, `tonal`](https://codeberg.org/uzu/strudel))
  running in an embedded [QuickJS-ng](https://github.com/quickjs-ng/quickjs)
  engine, so the language behaves exactly like strudel.cc: mini-notation,
  `stack`, `arrange`, `$:`, `setcpm`, `jux`, scales, chords, voicings, …
- Events are scheduled by **Strudel's own scheduler** against the audio clock
  and rendered sample-accurately by **[dough](https://codeberg.org/uzu/dough)**,
  a C synth engine by Strudel's author, through
  [miniaudio](https://miniaud.io/) on AAudio (OpenSL ES fallback).
- The sound is **calibrated against recordings of strudel.cc**: synth levels,
  envelopes, panning, filters and reverb match within about 1 dB.

## Installation

Requirements: **minSdk 26** (Android 8.0), Kotlin (or Java with the Kotlin
standard library), arm64-v8a / armeabi-v7a / x86 / x86_64.

### Option A: drop in the AAR

1. Download `krapfen-<version>.aar` from the
   [latest release](https://github.com/pokowaka/krapfen/releases/latest).
2. Put it in your app module's `libs/` directory.
3. Add the dependency:

   ```kotlin
   // app/build.gradle.kts
   dependencies {
       implementation(files("libs/krapfen-0.1.0.aar"))
   }
   ```

   A plain `files()` dependency carries no transitive dependencies. Kotlin
   apps already have the Kotlin standard library; Java-only apps also need
   `implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.10")`.

### Option B: use the Maven repository from the release

Each release also contains `krapfen-<version>-maven-repo.zip`: a Maven
repository with the AAR, a sources jar and a POM
(`io.github.pokowaka:krapfen:<version>`). Unzip it somewhere in your project
(or host it), then:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("third_party/krapfen-maven") } // the unzipped repo
    }
}

// app/build.gradle.kts
dependencies {
    implementation("io.github.pokowaka:krapfen:0.1.0")
}
```

This gives you sources in the IDE and the Kotlin standard library as a
transitive dependency.

## Quick start

```kotlin
import dev.krapfen.Krapfen

class MusicActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Optional: see Strudel's console output (evaluation errors, missing sounds, ...)
        Krapfen.logListener = Krapfen.LogListener { level, message ->
            Log.println(if (level >= Krapfen.LogLevel.WARN) Log.WARN else Log.INFO, "strudel", message)
        }

        Krapfen.initialize(this) { ready ->
            if (ready is Krapfen.Result.Failure) {
                Log.e("music", "audio failed: ${ready.message}")
                return@initialize
            }
            Krapfen.play(
                """
                setcpm(90/4)
                ${'$'}: chord("<Am7 Dm9 G13 C^7>").voicing().s("sawtooth").lpf(1200).gain(.3).room(.4)
                ${'$'}: n("0 2 4 <6 7>").scale("<A:minor D:dorian G:mixolydian C:major>").s("triangle")
                """.trimIndent()
            ) { result ->
                when (result) {
                    is Krapfen.Result.Success -> Log.i("music", "playing at ${result.value} cps")
                    is Krapfen.Result.Failure -> Log.e("music", "error: ${result.message}")
                }
            }
        }
    }

    // Release audio while in the background, continue where it left off.
    override fun onStart() { super.onStart(); Krapfen.resume() }
    override fun onStop() { super.onStop(); Krapfen.pause() }
}
```

(`${'$'}` is how to write Strudel's `$:` inside a Kotlin raw string; loading
patterns from `assets/` avoids that.)

## API

Everything is on the `dev.krapfen.Krapfen` singleton. Methods are
asynchronous and may be called from any thread; they run in order, and
callbacks are delivered on the **main thread**.

| Member | Description |
|---|---|
| `initialize(context, onReady?)` | Opens audio output and loads the Strudel engine (a few hundred ms). Safe to call again. |
| `play(code, onResult?)` | Evaluates Strudel code and plays it. While playing, the new pattern takes over on the running clock (like re-evaluating on strudel.cc). Result: tempo in cycles/s, or the error message. |
| `evaluate(code, onResult?)` | Evaluates without playing (e.g. to validate code). |
| `stop()` | Stops playback and silences everything. |
| `resume(onResult?)` / `pause()` | Lifecycle: `resume()` in `onStart`, `pause()` in `onStop`. Counted, and the audio release is deferred 500 ms, so rotation or switching activities doesn't interrupt playback. Pair each `resume()` with a `pause()`. |
| `volume` | Output volume, `1.0` = strudel.cc's loudness. |
| `status { }` | `Status(playing, cycle, cps, audioTime, scheduledEvents, lateEvents, samples)`. |
| `outputPeak` | Peak level (0..1) of the latest audio block, e.g. for a meter or visuals. |
| `logListener` | Receives Strudel's console output with a `LogLevel`. |
| `startRecording(maxSeconds)` / `stopRecording(file, onResult?)` | Records the output to a 32-bit float WAV (debugging, analysis). |
| `shutdown()` | Stops playback and closes audio output; `initialize` starts it again. |
| `isInitialized`, `VERSION` | State and library version. |

Results are `Krapfen.Result.Success(value)` or `Krapfen.Result.Failure(message)`.

### Game music tips

- **Switch tracks or intensity** by calling `play()` with new code: the
  pattern changes on the running clock, without a restart or gap.
- **Keep patterns in `assets/`** (`assets.open("music/level1.strudel")`) so
  musicians can edit them like on strudel.cc and test them there.
- **Tempo:** `setcpm(bpm / 4)` for 4 beats per cycle.
- Check `status { it.lateEvents }` during development; it should stay 0.

## Samples

Synth sounds (`sine`, `triangle`, `sawtooth`, `square`, `pulse`, noise,
`zaw`/`zquare`, …) work offline. Samples are loaded at runtime with
`samples(...)`, exactly as on strudel.cc:

```js
samples('github:tidalcycles/dirt-samples')
$: s("bd*4, [~ hh]*4, ~ sd ~ sd").bank("")
```

- Sources: a sample map object, a URL to a `strudel.json`, or
  `github:user/repo[/branch]`.
- `n`, `bank`, `speed` (also negative), `note` (pitch relative to C3),
  `begin`/`end` and `clip` follow superdough's rules.
- Files are downloaded once, cached in the app's cache directory, decoded
  with Android's MediaCodec (wav, mp3, ogg, flac, aac, …) and resampled.
- Samples used in the next two cycles are preloaded on `play()`; a sample
  that is still downloading is skipped the first time it plays.
- Krapfen does **not** preload strudel.cc's default sample banks. Load the
  banks you need (and check their licences if you ship them in a product).

## What's supported

- Strudel `core`, mini-notation, `transpiler` (`$:`, `"strings"` as
  patterns), `tonal` (`scale`, `chord`, `voicing`, `transpose`, …).
- Synths, noise, ADSR, filters (`lpf`/`hpf`/`bpf` with envelopes and
  resonance), `pan`, `delay`, `room`/`roomsize`, `crush`, `coarse`,
  `distort`, FM, vibrato and the other parameters dough implements.
- `.pianoroll()`, `.scope()` and other visualisations are accepted and ignored.

Known gaps: soundfonts (`gm_*`), `supersaw` and other superdough-only sounds
and effects, sample `cut`/`loop`, MIDI/OSC output. The reverb matches
strudel.cc's length and level but not its exact tone; samples are played
without interpolation when repitched.

## Size, permissions, shrinking

- The AAR adds about 1.0–1.5 MB of native code per ABI and a ~1 MB
  JavaScript bundle. Use an Android App Bundle (or ABI splits) so users
  only download their ABI.
- It declares the `INTERNET` permission (for `samples(...)`).
- R8/ProGuard rules for the JNI entry points ship inside the AAR; minified
  apps need no extra configuration.

## Licence

Krapfen is free software under the
[GNU Affero General Public License v3.0](LICENSE) or (at your option) any
later version, because it contains Strudel and dough (both AGPL-3.0-or-later).

> [!IMPORTANT]
> An app that includes Krapfen is a combined work under the AGPL: if you
> distribute it, you must license it under compatible terms and make its
> complete source code available to its users.

Your own patterns, samples and other assets are not affected by Krapfen's
licence.

### Credits

- [Strudel](https://codeberg.org/uzu/strudel) — Felix Roos and the Strudel
  contributors (AGPL-3.0-or-later); `core`, `mini`, `transpiler`, `tonal`
  are bundled unmodified.
- [dough](https://codeberg.org/uzu/dough) — Felix Roos and contributors
  (AGPL-3.0-or-later); bundled with small, documented modifications
  ([third_party/dough/VENDORED.md](third_party/dough/VENDORED.md)).
- [QuickJS-ng](https://github.com/quickjs-ng/quickjs) — QuickJS authors (MIT)
- [miniaudio](https://miniaud.io/) — David Reid (public domain / MIT-0)
- [tonal](https://github.com/tonaljs/tonal) (MIT),
  [chord-voicings](https://github.com/felixroos/chord-voicings) (ISC),
  [fraction.js](https://github.com/rawify/Fraction.js) (MIT),
  [acorn](https://github.com/acornjs/acorn) (MIT),
  [escodegen](https://github.com/estools/escodegen) (BSD-2-Clause),
  [estree-walker](https://github.com/Rich-Harris/estree-walker) (MIT)

### AI disclosure

This project is developed with substantial assistance from AI coding tools.

---

## Developing Krapfen

### Layout

| Path | |
|---|---|
| `krapfen/` | The library: Kotlin API, native engine (`src/main/cpp`), Strudel bundle (`src/main/assets`). |
| `app/` | Demo app using only the public API: editor, examples, volume, recording. |
| `js/` | Builds the Strudel bundle with esbuild; desktop tests for QuickJS. |
| `third_party/` | Vendored Strudel packages, dough, QuickJS-ng (see each `VENDORED.md`). |
| `tools/dough-bench` | Offline measurements of dough (levels, reverb RT60, filter response). |
| `tools/analyze` | Compares recordings of the calibration patterns with strudel.cc. |

### Building

Requires JDK 17 and the Android SDK with NDK 28.2.13676358 and CMake 3.31.6.

```sh
./gradlew :krapfen:assembleRelease   # library: krapfen/build/outputs/aar/
./gradlew :app:assembleDebug         # demo app
./gradlew :app:assembleMinified      # demo shrunk with R8 (checks consumer rules)
```

The Strudel bundle (`krapfen/src/main/assets/krapfen/strudel.js`) is
generated and checked in, so the Android build doesn't need Node. To
regenerate it and run the desktop tests with a QuickJS-ng `qjs` binary:

```sh
cd js && npm install && npm run build && cd ..
B=krapfen/src/main/assets/krapfen/strudel.js
qjs --std -I $B js/test/smoke.js
qjs --std -I $B js/test/tonal.js
qjs --std -I js/test/capture-send.js -I $B js/test/schedule.js
qjs --std -I js/test/fake-host.js -I $B js/test/samples.js
```

### Calibration

The demo's examples include `calibration` and `calibration-reverb`. Record
them with the demo's **Record & play** button and on strudel.cc (same
patterns), then:

```sh
tools/analyze/calibration.py krapfen.wav strudel.wav
tools/analyze/reverb.py krapfen-reverb.wav strudel-reverb.wav
tools/analyze/fit_reverb.py      # refits the roomsize mapping
tools/dough-bench/run.sh         # offline dough measurements
```

### Releasing

Set `krapfen.version` in `gradle.properties`, add a `CHANGELOG.md` section,
commit, and push a matching tag (`v0.1.0`). The release workflow builds the
AAR, the Maven repository zip and the demo APK and attaches them, with the
changelog section, to a GitHub release.
