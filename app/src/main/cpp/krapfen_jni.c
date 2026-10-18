// Krapfen native layer: drives the dough synth engine from a miniaudio
// playback device and exposes a tiny JNI API to Kotlin.
//
// Threading model:
//   - Kotlin (any thread) calls nativeEvaluate(); messages are pushed into a
//     single-producer/single-consumer ring buffer (producers are serialised
//     with a mutex).
//   - The audio callback (miniaudio's real-time thread) is the only thread
//     that touches dough's engine state: it drains the ring buffer, then
//     renders. dough itself is not thread-safe, so this keeps every dough
//     call on one thread without locking in the audio path.

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdatomic.h>
#include <string.h>
#include <math.h>

#include "dough.h"
#include "libs/miniaudio.h"

#define TAG "Krapfen"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Defined in dough.h's implementation section but not in its public
// prototypes.
void dough_render(float *out, int frames, double start_time);

// --- message ring buffer (UI threads -> audio thread) ---

#define MSG_SLOTS 64
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
static int64_t frames_rendered = 0;

// Peak level of the most recent callback, for the UI meter / verification.
static _Atomic float last_peak;

static void data_callback(ma_device *dev, void *output, const void *input, ma_uint32 frame_count)
{
    (void)input;
    float *out = (float *)output;

    drain_messages();

    double time = frames_rendered / (double)dev->sampleRate;
    dough_render(out, (int)frame_count, time);
    frames_rendered += frame_count;

    float peak = 0.0f;
    for (ma_uint32 i = 0; i < frame_count * dev->playback.channels; i++)
    {
        float v = fabsf(out[i]);
        if (v > peak)
            peak = v;
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
    config.sampleRate = 0;        // use the device's native rate
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
        // max_pcm (floats), max_events, max_files
        dough_init((int)device.sampleRate, 32, 1, 1, 1 << 20, 64, 16);
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
    int ok = push_message(msg);
    if (!ok)
        LOGE("message queue full, dropped: %s", msg);
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
