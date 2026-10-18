// Compiles the dough synth engine (third_party/dough/dough.h, unmodified).
// No DOUGH_MINIAUDIO: Krapfen drives the audio device itself, see
// krapfen_jni.c.

#include <stdlib.h>

// dough_engine_init() carves all engine state (voices, orbits, delay lines,
// reverb, scope framebuffer) out of a single malloc(). Two assumptions in
// dough break on Android's allocator (scudo), so we redirect that one
// allocation here instead of patching the vendored header:
//
// 1. Zeroed memory: several fields are never initialised (e.g. Delay.write,
//    used as buffer[(write + 1) % max]). WASM linear memory starts zeroed,
//    and dough's large native allocation gets fresh zero pages, but scudo
//    makes no such guarantee -> calloc().
//
// 2. Slack after the end: gen_sample() writes engine->framebuffer[frame]
//    and only wraps when frame > framebuffer_length, so once per wrap it
//    writes one float past the framebuffer -- the last region of the
//    allocation. scudo places a guard page right after large allocations,
//    so that write faults (SEGV_ACCERR in gen_sample). A little padding
//    absorbs the overrun.
#define DOUGH_ALLOC_PADDING 64
#define malloc(size) calloc(1, (size) + DOUGH_ALLOC_PADDING)

#define DOUGH_IMPLEMENTATION
#include "dough.h"
