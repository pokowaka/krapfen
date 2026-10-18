// Krapfen native layer: drives the dough synth engine from a miniaudio
// playback device and exposes a tiny JNI API to Kotlin.
//
// Threading model:
//   - Kotlin (Native.evaluate) and the Strudel scheduler on the JS thread
//     (krapfen_send) push messages into a ring buffer; producers are
//     serialised with a mutex.
//   - The audio callback (miniaudio's real-time thread) is the only thread
//     that touches dough's engine state: it drains the ring buffer, then
//     renders. dough itself is not thread-safe, so this keeps every dough
//     call on one thread without locking in the audio path.
//   - The audio clock (frames rendered) is published atomically so the
//     scheduler can timestamp events on dough's timeline.

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <math.h>

#include "dough.h"
#include "libs/miniaudio.h"
#include "krapfen.h"

#define TAG "Krapfen"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Defined in dough.h's implementation section but not in its public
// prototypes.
void dough_render(float *out, int frames, double start_time);
int dough_register_pcm(int file_index, const float *pcm, int frames, int channels, float freq);

// --- message ring buffer (UI threads -> audio thread) ---

// Sized for the scheduler bursting a few hundred timestamped events at once
// (e.g. a re-evaluated pattern with dense chords).
#define MSG_SLOTS 1024
#define MSG_LEN 512

static char msg_ring[MSG_SLOTS][MSG_LEN];
static atomic_uint msg_head; // next slot to write (producer)
static atomic_uint msg_tail; // next slot to read (consumer)
static pthread_mutex_t producer_lock = PTHREAD_MUTEX_INITIALIZER;

static int push_message(const char *msg)
{
    pthread_mutex_lock(&producer_lock);
    unsigned head = atomic_load_explicit(&msg_head, memory_order_relaxed);
    unsigned tail = atomic_load_explicit(&msg_tail, memory_order_acquire);
    if (head - tail >= MSG_SLOTS)
    {
        pthread_mutex_unlock(&producer_lock);
        return 0; // full
    }
    strncpy(msg_ring[head % MSG_SLOTS], msg, MSG_LEN - 1);
    msg_ring[head % MSG_SLOTS][MSG_LEN - 1] = '\0';
    atomic_store_explicit(&msg_head, head + 1, memory_order_release);
    pthread_mutex_unlock(&producer_lock);
    return 1;
}

static void drain_messages(void)
{
    unsigned tail = atomic_load_explicit(&msg_tail, memory_order_relaxed);
    unsigned head = atomic_load_explicit(&msg_head, memory_order_acquire);
    while (tail != head)
    {
        dough_evaluate(msg_ring[tail % MSG_SLOTS]);
        tail++;
    }
    atomic_store_explicit(&msg_tail, tail, memory_order_release);
}

// --- audio device ---

static ma_device device;
static int device_running = 0;
static int dough_ready = 0;

// Audio clock: frames rendered so far. Written by the audio thread, read by
// the scheduler (JS thread) through krapfen_audio_time().
static _Atomic int64_t frames_rendered;
static _Atomic uint32_t sample_rate;

// Peak level of the most recent callback, for the UI meter / verification.
static _Atomic float last_peak;

// dough's synth voices peak at 0.1 per channel at gain 1 (measured with
// tools/dough-bench), while superdough's peak at 0.3 (osc * 0.3 * env *
// gain). Scaling the mix by 3 matches strudel.cc's loudness.
#define MASTER_GAIN 3.0f

// User volume (0..1+), on top of MASTER_GAIN.
static _Atomic float volume = 1.0f;

// Seconds on dough's timeline: the start time of the next block to be
// rendered. Events must be timestamped on this clock.
double krapfen_audio_time(void)
{
    uint32_t sr = atomic_load_explicit(&sample_rate, memory_order_relaxed);
    if (sr == 0)
        return 0.0;
    return atomic_load_explicit(&frames_rendered, memory_order_relaxed) / (double)sr;
}

// Queues a dough message for the audio thread. Returns 0 if the queue is full.
int krapfen_send(const char *msg)
{
    int ok = push_message(msg);
    if (!ok)
        LOGE("message queue full, dropped: %s", msg);
    return ok;
}

static void data_callback(ma_device *dev, void *output, const void *input, ma_uint32 frame_count)
{
    (void)input;
    float *out = (float *)output;

    drain_messages();

    int64_t frames = atomic_load_explicit(&frames_rendered, memory_order_relaxed);
    double time = frames / (double)dev->sampleRate;
    dough_render(out, (int)frame_count, time);
    atomic_store_explicit(&frames_rendered, frames + frame_count, memory_order_relaxed);

    float gain = MASTER_GAIN * atomic_load_explicit(&volume, memory_order_relaxed);
    float peak = 0.0f;
    for (ma_uint32 i = 0; i < frame_count * dev->playback.channels; i++)
    {
        // Hard clip like a browser's audio output would.
        float v = fminf(1.0f, fmaxf(-1.0f, out[i] * gain));
        out[i] = v;
        if (fabsf(v) > peak)
            peak = fabsf(v);
    }
    atomic_store_explicit(&last_peak, peak, memory_order_relaxed);
}

JNIEXPORT jint JNICALL
Java_dev_krapfen_Native_start(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    if (device_running)
        return 0;

    ma_device_config config = ma_device_config_init(ma_device_type_playback);
    config.playback.format = ma_format_f32;
    config.playback.channels = 2; // dough renders interleaved stereo
    // First start: use the device's native rate. dough's engine is created
    // once with that rate, so later restarts (after pause) keep it and let
    // miniaudio resample if the new device differs.
    config.sampleRate = dough_ready ? atomic_load(&sample_rate) : 0;
    config.dataCallback = data_callback;
    config.performanceProfile = ma_performance_profile_low_latency;

    if (ma_device_init(NULL, &config, &device) != MA_SUCCESS)
    {
        LOGE("ma_device_init failed");
        return -1;
    }

    if (!dough_ready)
    {
        // sample_rate, max_voices, max_orbits, max_delay_time (s),
        // max_pcm (floats: 8M = 32 MB, ~87 s of stereo samples at 48 kHz),
        // max_events (scheduled ahead), max_files (sample slots)
        dough_init((int)device.sampleRate, 64, 1, 1, 1 << 23, 1024, KRAPFEN_MAX_FILES);
        atomic_store(&sample_rate, device.sampleRate);
        dough_ready = 1;
    }

    if (ma_device_start(&device) != MA_SUCCESS)
    {
        LOGE("ma_device_start failed");
        ma_device_uninit(&device);
        return -2;
    }
    device_running = 1;
    LOGI("audio started: backend=%s rate=%u channels=%u period=%u frames",
         ma_get_backend_name(device.pContext->backend), device.sampleRate,
         device.playback.channels, device.playback.internalPeriodSizeInFrames);
    return 0;
}

JNIEXPORT void JNICALL
Java_dev_krapfen_Native_stop(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    if (!device_running)
        return;
    ma_device_uninit(&device);
    device_running = 0;
    LOGI("audio stopped");
}

JNIEXPORT jboolean JNICALL
Java_dev_krapfen_Native_evaluate(JNIEnv *env, jclass clazz, jstring jmsg)
{
    (void)clazz;
    const char *msg = (*env)->GetStringUTFChars(env, jmsg, NULL);
    if (msg == NULL)
        return JNI_FALSE;
    int ok = krapfen_send(msg);
    (*env)->ReleaseStringUTFChars(env, jmsg, msg);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_dev_krapfen_Native_peak(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return atomic_load_explicit(&last_peak, memory_order_relaxed);
}

// Registers decoded sample data (interleaved float PCM at the device sample
// rate) as dough file slot `file`.
//
// Runs on the caller's thread, not the audio thread. That is safe because
// dough_register_pcm() only writes the unused tail of dough's PCM pool,
// engine.pcm_offset (touched by nothing else) and files[file]; the audio
// thread reads files[file] only when a message referencing it arrives, and
// such messages are queued after this returns (the ring buffer's
// release/acquire ordering publishes the writes). Registrations are
// serialised with a mutex.
static pthread_mutex_t register_lock = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT jboolean JNICALL
Java_dev_krapfen_Native_registerSample(JNIEnv *env, jclass clazz, jint file, jfloatArray jpcm,
                                       jint channels, jfloat freq)
{
    (void)clazz;
    if (!dough_ready || channels < 1 || channels > 2)
        return JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, jpcm);
    jfloat *pcm = (*env)->GetFloatArrayElements(env, jpcm, NULL);
    if (!pcm)
        return JNI_FALSE;
    pthread_mutex_lock(&register_lock);
    int r = dough_register_pcm(file, pcm, len / channels, channels, freq);
    pthread_mutex_unlock(&register_lock);
    (*env)->ReleaseFloatArrayElements(env, jpcm, pcm, JNI_ABORT);
    if (r != 0)
        LOGE("registerSample: no room for file %d (%d floats)", file, (int)len);
    return r == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_dev_krapfen_Native_sampleRate(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    return (jint)atomic_load(&sample_rate);
}

JNIEXPORT void JNICALL
Java_dev_krapfen_Native_setVolume(JNIEnv *env, jclass clazz, jfloat v)
{
    (void)env;
    (void)clazz;
    atomic_store_explicit(&volume, fmaxf(0.0f, v), memory_order_relaxed);
}
