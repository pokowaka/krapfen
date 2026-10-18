Vendored from npm package dough-synth@0.4.2 (https://codeberg.org/uzu/dough)
by Felix Roos and contributors, AGPL-3.0-or-later.

libs/miniaudio.h: miniaudio by David Reid, public domain (Unlicense) or MIT-0.

## Local workarounds (in krapfen/src/main/cpp/dough_impl.c, file itself unmodified)

dough's single engine allocation is redirected to `calloc(1, size + 64)`:

- **zeroed:** `dough_engine_init()` assumes zeroed memory (e.g. `Delay.write`
  is never initialised).
- **padded:** `gen_sample()` wraps the scope framebuffer index with
  `frame > framebuffer_length` instead of `>=`, writing one float past the end
  of the allocation once per wrap. Android's allocator has a guard page there.

## Local modifications to dough.h (marked `KRAPFEN PATCH`)

- Linear amplitude (gain) envelope instead of dough's quadratic curve, to
  match superdough. Measured against strudel.cc: a `decay(.2).sustain(0)`
  pluck was 11.7 dB lower than strudel.cc 150 ms after the onset.
- Exact `sinf`/`cosf` instead of `par_sinf`/`par_cosf` (a parabolic
  approximation) in the biquad filter coefficients and the pan law. Measured
  against strudel.cc: a sawtooth through `lpf(1000)` had harmonics 3-5 2-4 dB
  too low; strudel.cc matches the ideal Web Audio biquad within ~0.7 dB.
- A `loop` event parameter: sample voices with `loop` > 0 wrap to the start
  instead of being freed when the sample ends. Used for looping sound effects
  (Krapfen.loopSound) and Strudel's `loop` control on samples.
