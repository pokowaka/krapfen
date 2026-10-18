// Krapfen JS host: runs Strudel's pattern engine (assets/krapfen/strudel.js)
// in an embedded QuickJS-ng runtime.
//
// Threading: QuickJS runtimes are single-threaded. Kotlin calls into this
// file from one dedicated thread (see StrudelEngine.kt); a mutex guards
// against accidental concurrent use, and JS_UpdateStackTop() is called on
// every entry so QuickJS's stack-overflow check measures the right stack.
//
// Results cross JNI as JSON strings to keep the boundary small.

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "quickjs.h"
#include "krapfen.h"

#define TAG "KrapfenJS"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// The JS thread is created by Kotlin with an 8 MB stack; leave headroom.
#define JS_STACK_SIZE (6 * 1024 * 1024)
#define JS_MEMORY_LIMIT (256 * 1024 * 1024)
// Upper bound on job-queue iterations while settling one promise.
#define MAX_PENDING_JOBS 1000000

static JSRuntime *rt;
static JSContext *ctx;
static pthread_mutex_t js_lock = PTHREAD_MUTEX_INITIALIZER;

// --- host functions exposed to JS ---

static JSValue js_now(JSContext *c, JSValueConst this_val, int argc, JSValueConst *argv)
{
    (void)this_val;
    (void)argc;
    (void)argv;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return JS_NewFloat64(c, ts.tv_sec + ts.tv_nsec / 1e9);
}

static JSValue js_log(JSContext *c, JSValueConst this_val, int argc, JSValueConst *argv)
{
    (void)this_val;
    int level = 1;
    if (argc > 0)
        JS_ToInt32(c, &level, argv[0]);
    const char *msg = argc > 1 ? JS_ToCString(c, argv[1]) : NULL;
    static const int prio[] = {ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR};
    __android_log_print(prio[level < 0 ? 0 : level > 3 ? 3 : level], TAG, "%s", msg ? msg : "");
    if (msg)
        JS_FreeCString(c, msg);
    return JS_UNDEFINED;
}

// __krapfenAudioTime() -> seconds on dough's timeline (the scheduler clock).
static JSValue js_audio_time(JSContext *c, JSValueConst this_val, int argc, JSValueConst *argv)
{
    (void)this_val;
    (void)argc;
    (void)argv;
    return JS_NewFloat64(c, krapfen_audio_time());
}

// __krapfenSend(message) -> queues a dough message for the audio thread.
static JSValue js_send(JSContext *c, JSValueConst this_val, int argc, JSValueConst *argv)
{
    (void)this_val;
    if (argc < 1)
        return JS_FALSE;
    const char *msg = JS_ToCString(c, argv[0]);
    if (!msg)
        return JS_EXCEPTION;
    int ok = krapfen_send(msg);
    JS_FreeCString(c, msg);
    return JS_NewBool(c, ok);
}

// --- helpers ---

// Returns a malloc'd "name: message\nstack" description of the pending
// exception (or of `err` if given), clearing it.
static char *describe_exception(JSValue err)
{
    JSValue e = JS_IsUndefined(err) ? JS_GetException(ctx) : err;
    const char *msg = JS_ToCString(ctx, e);
    char *out = NULL;
    if (JS_IsError(e))
    {
        JSValue stack = JS_GetPropertyStr(ctx, e, "stack");
        const char *s = JS_IsUndefined(stack) ? NULL : JS_ToCString(ctx, stack);
        size_t n = strlen(msg ? msg : "") + strlen(s ? s : "") + 2;
        out = malloc(n);
        if (out)
            snprintf(out, n, "%s\n%s", msg ? msg : "", s ? s : "");
        if (s)
            JS_FreeCString(ctx, s);
        JS_FreeValue(ctx, stack);
    }
    else
    {
        out = strdup(msg ? msg : "unknown error");
    }
    if (msg)
        JS_FreeCString(ctx, msg);
    JS_FreeValue(ctx, e);
    return out;
}

// Runs the job queue until `promise` settles. Takes ownership of `promise`.
// On success returns the resolved value (caller frees); on failure returns
// JS_EXCEPTION and sets *error (malloc'd).
static JSValue await_promise(JSValue promise, char **error)
{
    if (JS_IsException(promise))
    {
        *error = describe_exception(JS_UNDEFINED);
        return JS_EXCEPTION;
    }
    if (!JS_IsPromise(promise))
        return promise; // plain value

    for (int i = 0; i < MAX_PENDING_JOBS; i++)
    {
        JSPromiseStateEnum state = JS_PromiseState(ctx, promise);
        if (state == JS_PROMISE_FULFILLED)
        {
            JSValue result = JS_PromiseResult(ctx, promise);
            JS_FreeValue(ctx, promise);
            return result;
        }
        if (state == JS_PROMISE_REJECTED)
        {
            JSValue reason = JS_PromiseResult(ctx, promise);
            JS_FreeValue(ctx, promise);
            *error = describe_exception(reason);
            return JS_EXCEPTION;
        }
        JSContext *job_ctx;
        int r = JS_ExecutePendingJob(rt, &job_ctx);
        if (r < 0)
        {
            char *job_err = describe_exception(JS_UNDEFINED);
            LOGE("error in pending job: %s", job_err ? job_err : "?");
            free(job_err);
        }
        else if (r == 0)
        {
            break; // queue empty but promise still pending
        }
    }
    JS_FreeValue(ctx, promise);
    *error = strdup("promise did not settle (no pending jobs left)");
    return JS_EXCEPTION;
}

// Calls globalThis.krapfen[method](...args). Returns new value or JS_EXCEPTION.
static JSValue call_krapfen(const char *method, int argc, JSValueConst *argv)
{
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue krapfen = JS_GetPropertyStr(ctx, global, "krapfen");
    JSValue fn = JS_GetPropertyStr(ctx, krapfen, method);
    JSValue result = JS_Call(ctx, fn, krapfen, argc, argv);
    JS_FreeValue(ctx, fn);
    JS_FreeValue(ctx, krapfen);
    JS_FreeValue(ctx, global);
    return result;
}

static jstring json_error(JNIEnv *env, const char *message)
{
    // Build {"ok":false,"error":"..."} with the message JSON-escaped by QuickJS.
    JSValue obj = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, obj, "ok", JS_FALSE);
    JS_SetPropertyStr(ctx, obj, "error", JS_NewString(ctx, message ? message : "unknown error"));
    JSValue json = JS_JSONStringify(ctx, obj, JS_UNDEFINED, JS_UNDEFINED);
    const char *s = JS_ToCString(ctx, json);
    jstring out = (*env)->NewStringUTF(env, s ? s : "{\"ok\":false}");
    if (s)
        JS_FreeCString(ctx, s);
    JS_FreeValue(ctx, json);
    JS_FreeValue(ctx, obj);
    return out;
}

static jstring to_json(JNIEnv *env, JSValue value)
{
    JSValue json = JS_JSONStringify(ctx, value, JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(json))
    {
        char *err = describe_exception(JS_UNDEFINED);
        jstring out = json_error(env, err);
        free(err);
        return out;
    }
    const char *s = JS_ToCString(ctx, json);
    jstring out = (*env)->NewStringUTF(env, s ? s : "null");
    if (s)
        JS_FreeCString(ctx, s);
    JS_FreeValue(ctx, json);
    return out;
}

// --- JNI ---

// Creates the runtime and evaluates the Strudel bundle.
// Returns null on success, or an error description.
JNIEXPORT jstring JNICALL
Java_dev_krapfen_Native_jsInit(JNIEnv *env, jclass clazz, jstring jbundle)
{
    (void)clazz;
    pthread_mutex_lock(&js_lock);
    if (ctx)
    {
        pthread_mutex_unlock(&js_lock);
        return NULL;
    }

    rt = JS_NewRuntime();
    JS_SetMaxStackSize(rt, JS_STACK_SIZE);
    JS_SetMemoryLimit(rt, JS_MEMORY_LIMIT);
    JS_UpdateStackTop(rt);
    ctx = JS_NewContext(rt);

    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "__krapfenNow", JS_NewCFunction(ctx, js_now, "__krapfenNow", 0));
    JS_SetPropertyStr(ctx, global, "__krapfenLog", JS_NewCFunction(ctx, js_log, "__krapfenLog", 2));
    JS_SetPropertyStr(ctx, global, "__krapfenAudioTime", JS_NewCFunction(ctx, js_audio_time, "__krapfenAudioTime", 0));
    JS_SetPropertyStr(ctx, global, "__krapfenSend", JS_NewCFunction(ctx, js_send, "__krapfenSend", 1));
    JS_FreeValue(ctx, global);

    const char *bundle = (*env)->GetStringUTFChars(env, jbundle, NULL);
    size_t len = strlen(bundle);
    struct timespec t0, t1;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    JSValue r = JS_Eval(ctx, bundle, len, "strudel.js", JS_EVAL_TYPE_GLOBAL);
    clock_gettime(CLOCK_MONOTONIC, &t1);
    (*env)->ReleaseStringUTFChars(env, jbundle, bundle);

    jstring result = NULL;
    if (JS_IsException(r))
    {
        char *err = describe_exception(JS_UNDEFINED);
        LOGE("bundle failed: %s", err ? err : "?");
        result = (*env)->NewStringUTF(env, err ? err : "bundle failed");
        free(err);
    }
    else
    {
        // Let module-level promises (evalScope) settle.
        JSContext *job_ctx;
        while (JS_ExecutePendingJob(rt, &job_ctx) > 0)
            ;
        LOGI("bundle loaded: %zu KB in %.1f ms", len / 1024,
             (t1.tv_sec - t0.tv_sec) * 1e3 + (t1.tv_nsec - t0.tv_nsec) / 1e6);
    }
    JS_FreeValue(ctx, r);
    pthread_mutex_unlock(&js_lock);
    return result;
}

// Calls globalThis.krapfen[method](...args), where `args_json` is a JSON
// array, and waits for the result if it is a promise.
// Returns {"ok":true,"value":...} or {"ok":false,"error":"..."}.
JNIEXPORT jstring JNICALL
Java_dev_krapfen_Native_jsCall(JNIEnv *env, jclass clazz, jstring jmethod, jstring jargs)
{
    (void)clazz;
    pthread_mutex_lock(&js_lock);
    if (!ctx)
    {
        pthread_mutex_unlock(&js_lock);
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"error\":\"engine not initialised\"}");
    }
    JS_UpdateStackTop(rt);

    const char *method = (*env)->GetStringUTFChars(env, jmethod, NULL);
    const char *args_json = (*env)->GetStringUTFChars(env, jargs, NULL);
    JSValue args = JS_ParseJSON(ctx, args_json, strlen(args_json), "<args>");
    (*env)->ReleaseStringUTFChars(env, jargs, args_json);

    jstring out;
    char *error = NULL;
    if (JS_IsException(args) || !JS_IsArray(args))
    {
        error = strdup("arguments must be a JSON array");
        out = json_error(env, error);
    }
    else
    {
        int64_t argc = 0;
        JS_GetLength(ctx, args, &argc);
        if (argc > 8)
            argc = 8;
        JSValue argv[8];
        for (int i = 0; i < argc; i++)
            argv[i] = JS_GetPropertyUint32(ctx, args, i);

        JSValue result = await_promise(call_krapfen(method, (int)argc, argv), &error);
        for (int i = 0; i < argc; i++)
            JS_FreeValue(ctx, argv[i]);

        if (JS_IsException(result))
        {
            out = json_error(env, error);
        }
        else
        {
            JSValue envelope = JS_NewObject(ctx);
            JS_SetPropertyStr(ctx, envelope, "ok", JS_TRUE);
            JS_SetPropertyStr(ctx, envelope, "value", result); // takes ownership
            out = to_json(env, envelope);
            JS_FreeValue(ctx, envelope);
        }
    }
    free(error);
    JS_FreeValue(ctx, args);
    (*env)->ReleaseStringUTFChars(env, jmethod, method);
    pthread_mutex_unlock(&js_lock);
    return out;
}

// Scheduler heartbeat: runs due JS timers (Strudel's clock) and any promise
// jobs they queue. Called every few milliseconds while playing.
JNIEXPORT void JNICALL
Java_dev_krapfen_Native_jsTick(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    pthread_mutex_lock(&js_lock);
    if (!ctx)
    {
        pthread_mutex_unlock(&js_lock);
        return;
    }
    JS_UpdateStackTop(rt);

    JSValue r = call_krapfen("tick", 0, NULL);
    if (JS_IsException(r))
    {
        char *err = describe_exception(JS_UNDEFINED);
        LOGE("tick failed: %s", err ? err : "?");
        free(err);
    }
    JS_FreeValue(ctx, r);

    JSContext *job_ctx;
    for (int i = 0; i < 10000; i++)
    {
        int jr = JS_ExecutePendingJob(rt, &job_ctx);
        if (jr == 0)
            break;
        if (jr < 0)
        {
            char *err = describe_exception(JS_UNDEFINED);
            LOGE("error in pending job: %s", err ? err : "?");
            free(err);
        }
    }
    pthread_mutex_unlock(&js_lock);
}
