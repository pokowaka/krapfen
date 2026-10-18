package dev.krapfen

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
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
class StrudelEngine(private val context: Context) {

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
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    private val tasks = LinkedBlockingQueue<() -> Unit>()

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

    /** Loads the bundle from assets. */
    fun init(onDone: (Result<Unit>) -> Unit) = post {
        val bundle = context.assets.open("krapfen/strudel.js").bufferedReader().use { it.readText() }
        val error = Native.jsInit(bundle)
        onDone(if (error == null) Result.Ok(Unit) else Result.Error(error))
    }

    /** Evaluates Strudel code without playing; yields cycles per second. */
    fun evaluate(code: String, onDone: (Result<Double>) -> Unit) = post {
        onDone(call("evaluate", code).map { (it as JSONObject).getDouble("cps") })
    }

    /**
     * Evaluates code and plays it. While already playing, this swaps the
     * pattern in without interrupting the clock, as on strudel.cc.
     */
    fun play(code: String, onDone: (Result<Double>) -> Unit) = post {
        val result = call("play", code).map { (it as JSONObject).getDouble("cps") }
        if (result is Result.Ok) playing = true
        onDone(result)
    }

    /** Stops playback and silences dough. */
    fun stop(onDone: () -> Unit = {}) = post {
        call("stop")
        playing = false
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
