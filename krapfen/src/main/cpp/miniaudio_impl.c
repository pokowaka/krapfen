// Compiles miniaudio (third_party/dough/libs/miniaudio.h, unmodified).
// Only playback is needed; on Android miniaudio picks AAudio and falls
// back to OpenSL ES.
#define MA_NO_DECODING
#define MA_NO_ENCODING
#define MA_NO_GENERATION
#define MINIAUDIO_IMPLEMENTATION
#include "libs/miniaudio.h"
