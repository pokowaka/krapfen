package dev.krapfen

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Owns the embedded QuickJS runtime running Strudel's pattern engine.
 *
 * QuickJS is single-threaded, so every call runs on one dedicated thread
 * (with a large stack for QuickJS's recursion). While playing, the same
 * thread also drives Strudel's scheduler by calling [Native.jsTick] every
 * few milliseconds; the scheduler sends timestamped events straight to the
 * dough audio engine in native code.
 *
 * Callbacks run on the engine thread.
 */
internal class StrudelEngine(private val context: Context) {

    data class Event(val begin: Double, val end: Double, val duration: Double, val value: JSONObject)

    data class Status(
        val started: Boolean,
        val cycle: Double,
        val cps: Double,
        val time: Double,
        val scheduled: Int,
        val dropped: Int,
        /** Events sent after their start time (dough drops these if >10 ms late). */
        val late: Int,
        /** Smallest lead time (s) of any event over the audio clock, or NaN. */
        val minLead: Double,
        /** Sample files loaded or loading. */
        val sampleFiles: Int,
        /** Sample events skipped because their sample was still loading. */
        val skippedNotReady: Int,
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    private val tasks = LinkedBlockingQueue<() -> Unit>()

    // Network and decoding for JS host requests (samples, sample maps).
    private val io = Executors.newFixedThreadPool(4) { r -> Thread(r, "krapfen-io").apply { isDaemon = true } }
    private val samples = SampleLoader(context)

    // Only touched on the engine thread.
    private var playing = false

    private val thread = Thread(null, ::loop, "strudel-js", 8L * 1024 * 1024).apply {
        isDaemon = true
        start()
    }

    private fun loop() {
        while (true) {
            // Wake up for tasks immediately; otherwise tick the scheduler
            // every TICK_MS while playing.
            val task = if (playing) tasks.poll(TICK_MS, TimeUnit.MILLISECONDS) else tasks.take()
            try {
                task?.invoke()
                if (playing) Native.jsTick()
            } catch (t: Throwable) {
                Log.e(TAG, "engine task failed", t)
            }
        }
    }

    // Engine thread only.
    private var initialized = false

    /** Loads the bundle from assets (once). */
    fun init(onDone: (Result<Unit>) -> Unit) = post {
        if (initialized) {
            onDone(Result.Ok(Unit))
            return@post
        }
        Native.hostRequestHandler = ::onHostRequest
        val bundle = context.assets.open("krapfen/strudel.js").bufferedReader().use { it.readText() }
        val error = Native.jsInit(bundle)
        initialized = error == null
        onDone(if (error == null) Result.Ok(Unit) else Result.Error(error))
    }

    /** Evaluates Strudel code without playing; yields cycles per second. */
    fun evaluate(code: String, onDone: (Result<Double>) -> Unit) = post {
        callAsync("evaluate", code) { r -> onDone(r.map { (it as JSONObject).getDouble("cps") }) }
    }

    /**
     * Evaluates code and plays it. While already playing, this swaps the
     * pattern in without interrupting the clock, as on strudel.cc.
     */
    fun play(code: String, onDone: (Result<Double>) -> Unit) = post {
        val wasPlaying = playing
        // Keep ticking while the evaluation waits on sample maps.
        playing = true
        callAsync("play", code) { r ->
            val result = r.map { (it as JSONObject).getDouble("cps") }
            if (result is Result.Error) playing = wasPlaying
            onDone(result)
        }
    }

    /** Stops playback and silences dough. */
    fun stop(onDone: () -> Unit = {}) = post {
        call("stop")
        playing = false
        onDone()
    }

    /** Opens the audio device (idempotent). */
    fun startAudio(onDone: (Result<Unit>) -> Unit = {}) = post {
        val r = Native.start()
        onDone(if (r == 0) Result.Ok(Unit) else Result.Error("audio output failed to start ($r)"))
    }

    /** Suspends scheduling and closes the audio device, keeping the position. */
    fun pause(onDone: () -> Unit = {}) = post {
        call("pause")
        playing = false
        Native.stop()
        onDone()
    }

    /** Reopens the audio device and continues playback paused by [pause]. */
    fun resume(onDone: (Result<Unit>) -> Unit = {}) = post {
        val r = Native.start()
        if (r != 0) {
            onDone(Result.Error("audio output failed to start ($r)"))
            return@post
        }
        val resumed = call("resume")
        if (resumed is Result.Ok && resumed.value == true) playing = true
        onDone(resumed.map { })
    }

    /** Stops playback and closes the audio device. */
    fun shutdown(onDone: () -> Unit = {}) = post {
        call("stop")
        playing = false
        Native.stop()
        onDone()
    }

    fun status(onDone: (Result<Status>) -> Unit) = post {
        onDone(call("status").map {
            val s = it as JSONObject
            Status(
                started = s.getBoolean("started"),
                cycle = s.getDouble("cycle"),
                cps = s.getDouble("cps"),
                time = s.getDouble("time"),
                scheduled = s.getInt("scheduled"),
                dropped = s.getInt("dropped"),
                late = s.getInt("late"),
                minLead = s.optDouble("minLead", Double.NaN),
                sampleFiles = s.optInt("files"),
                skippedNotReady = s.optInt("skippedNotReady"),
            )
        })
    }

    /** Events with onsets in [begin, end) cycles. */
    fun query(begin: Double, end: Double, onDone: (Result<List<Event>>) -> Unit) = post {
        onDone(call("query", begin, end).map {
            val array = it as JSONArray
            List(array.length()) { i ->
                val e = array.getJSONObject(i)
                Event(e.getDouble("begin"), e.getDouble("end"), e.getDouble("duration"), e.getJSONObject("value"))
            }
        })
    }

    /**
     * Handles `__krapfenRequest(op, id, arg)` from JS (engine thread): does
     * the work on the IO pool and reports back via `krapfen._hostDone`.
     */
    private fun onHostRequest(op: String, id: Int, arg: String) {
        if (op == "callDone") {
            // Completion of callAsync. Deferred so the callback never
            // re-enters native code from inside the current JS call.
            val callback = pendingCalls.remove(id) ?: return
            val json = JSONObject(arg)
            val result: Result<Any> = if (json.optBoolean("ok")) Result.Ok(json.opt("value") ?: JSONObject.NULL)
            else Result.Error(json.optString("error", "unknown error"))
            post { callback(result) }
            return
        }
        io.execute {
            val (ok, payload) = try {
                when (op) {
                    "fetchText" -> true to samples.fetchText(arg)
                    "loadSample" -> {
                        val req = JSONObject(arg)
                        true to samples.loadSample(req.getString("url"), req.getInt("file"))
                    }
                    else -> false to "unknown host request: $op"
                }
            } catch (t: Throwable) {
                Log.w(TAG, "host request $op failed: $arg", t)
                false to (t.message ?: t.toString())
            }
            post { call("_hostDone", id, ok, payload) }
        }
    }

    // callAsync callbacks by id; engine thread only.
    private val pendingCalls = HashMap<Int, (Result<Any>) -> Unit>()
    private var nextCallId = 1

    /** Calls a JS method that may wait on host requests (engine thread). */
    private fun callAsync(method: String, vararg args: Any, onDone: (Result<Any>) -> Unit) {
        val id = nextCallId++
        pendingCalls[id] = onDone
        val r = call("_call", id, method, JSONArray(args.toList()))
        if (r is Result.Error) {
            pendingCalls.remove(id)
            onDone(r)
        }
    }

    private fun call(method: String, vararg args: Any): Result<Any> {
        val json = JSONObject(Native.jsCall(method, JSONArray(args.toList()).toString()))
        return if (json.optBoolean("ok")) Result.Ok(json.opt("value") ?: JSONObject.NULL)
        else Result.Error(json.optString("error", "unknown error"))
    }

    private inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> = when (this) {
        is Result.Ok -> Result.Ok(transform(value))
        is Result.Error -> this
    }

    private fun post(task: () -> Unit) {
        tasks.put(task)
    }

    companion object {
        private const val TAG = "Krapfen"

        /** Scheduler heartbeat. Strudel's clock itself runs every 100 ms. */
        private const val TICK_MS = 10L
    }
}
