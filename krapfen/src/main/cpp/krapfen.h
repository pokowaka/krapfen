// Interfaces shared between Krapfen's native modules.
#pragma once

// Seconds on dough's timeline: the start time of the next audio block to be
// rendered. Scheduled events must be timestamped on this clock.
double krapfen_audio_time(void);

// Queues a dough message (e.g. "dough/play/s/sine/note/60/time/1.25") for
// the audio thread. Thread-safe. Returns 0 if the queue was full.
int krapfen_send(const char *msg);

// dough file slots for samples. Slots below KRAPFEN_SOUND_FILE_BASE belong to
// samples loaded by patterns (samples(...), managed in JS, exposed as
// __krapfenMaxFiles); slots from KRAPFEN_SOUND_FILE_BASE up belong to sound
// effects loaded by the app (Krapfen.loadSound, managed in Kotlin).
#define KRAPFEN_MAX_FILES 1024
#define KRAPFEN_SOUND_FILE_BASE 512
