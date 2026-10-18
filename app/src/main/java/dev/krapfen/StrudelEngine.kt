package dev.krapfen

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue

/**
 * Owns the embedded QuickJS runtime running Strudel's pattern engine.
 *
 * QuickJS is single-threaded, so every call runs on one dedicated thread
 * (with a large stack for QuickJS's recursion). Callers get results via
 * callbacks on that thread.
 */
class StrudelEngine(private val context: Context) {

    data class Event(val begin: Double, val end: Double, val duration: Double, val value: JSONObject)

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    private val tasks = LinkedBlockingQueue<() -> Unit>()

    private val thread = Thread(null, {
        while (true) {
            val task = tasks.take()
            try {
                task()
            } catch (t: Throwable) {
                Log.e(TAG, "task failed", t)
            }
        }
    }, "strudel-js", 8L * 1024 * 1024).apply {
        isDaemon = true
        start()
    }

    /** Loads the bundle from assets. */
    fun init(onDone: (Result<Unit>) -> Unit) = post {
        val bundle = context.assets.open("krapfen/strudel.js").bufferedReader().use { it.readText() }
        val error = Native.jsInit(bundle)
        onDone(if (error == null) Result.Ok(Unit) else Result.Error(error))
    }

    /** Evaluates Strudel code; yields the resulting cycles-per-second. */
    fun evaluate(code: String, onDone: (Result<Double>) -> Unit) = post {
        val json = JSONObject(Native.jsEvaluate(code))
        onDone(
            if (json.optBoolean("ok")) Result.Ok(json.getDouble("cps"))
            else Result.Error(json.optString("error", "unknown error"))
        )
    }

    /** Events with onsets in [begin, end) cycles. */
    fun query(begin: Double, end: Double, onDone: (Result<List<Event>>) -> Unit) = post {
        onDone(parseEvents(Native.jsQuery(begin, end)))
    }

    private fun parseEvents(json: String): Result<List<Event>> {
        if (json.startsWith("{")) {
            return Result.Error(JSONObject(json).optString("error", "unknown error"))
        }
        val array = JSONArray(json)
        return Result.Ok(List(array.length()) { i ->
            val e = array.getJSONObject(i)
            Event(e.getDouble("begin"), e.getDouble("end"), e.getDouble("duration"), e.getJSONObject("value"))
        })
    }

    private fun post(task: () -> Unit) {
        tasks.put(task)
    }

    companion object {
        private const val TAG = "Krapfen"
    }
}
