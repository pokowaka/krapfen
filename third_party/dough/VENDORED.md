Vendored from npm package dough-synth@0.4.2 (https://codeberg.org/uzu/dough)
by Felix Roos and contributors, AGPL-3.0-or-later. Files are unmodified.

libs/miniaudio.h: miniaudio by David Reid, public domain (Unlicense) or MIT-0.

## Local workarounds (in app/src/main/cpp/dough_impl.c, file itself unmodified)

dough's single engine allocation is redirected to `calloc(1, size + 64)`:

- **zeroed:** `dough_engine_init()` assumes zeroed memory (e.g. `Delay.write`
  is never initialised).
- **padded:** `gen_sample()` wraps the scope framebuffer index with
  `frame > framebuffer_length` instead of `>=`, writing one float past the end
  of the allocation once per wrap. Android's allocator has a guard page there.
