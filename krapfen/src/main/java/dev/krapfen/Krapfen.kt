package dev.krapfen

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Plays [Strudel](https://strudel.cc) patterns on Android.
 *
 * Strudel code is evaluated by Strudel's own pattern engine in an embedded
 * JavaScript engine (QuickJS), scheduled on the audio clock and rendered by
 * the native dough synth, so patterns sound as they do on strudel.cc.
 *
 * Krapfen is a process-wide singleton: there is one native audio engine per
 * process. Typical use:
 *
 * ```kotlin
 * Krapfen.initialize(context) { ready -> ... }
 * Krapfen.play("""note("c e g").s("sine")""") { result -> ... }
 * Krapfen.resume()  // in onStart
 * Krapfen.pause()   // in onStop
 * ```
 *
 * All methods are asynchronous and may be called from any thread; calls are
 * processed in order. Callbacks are delivered on the main thread.
 */
public object Krapfen {

    /** Outcome of an operation. */
    public sealed class Result<out T> {
        public data class Success<T>(val value: T) : Result<T>()
        public data class Failure(val message: String) : Result<Nothing>()
    }

    /** Playback state, see [status]. */
    public data class Status(
        /** Whether patterns are currently being scheduled. */
        val playing: Boolean,
        /** Current position in cycles. */
        val cycle: Double,
        /** Cycles per second. */
        val cps: Double,
        /** Audio clock in seconds. */
        val audioTime: Double,
        /** Events sent to the synth since initialization. */
        val scheduledEvents: Int,
        /** Events sent too late to be played on time (should stay 0). */
        val lateEvents: Int,
        /** Samples loaded or loading. */
        val samples: Int,
    )

    public enum class LogLevel { DEBUG, INFO, WARN, ERROR }

    /** Receives Strudel's console output, e.g. evaluation and sample errors. */
    public fun interface LogListener {
        public fun onLog(level: LogLevel, message: String)
    }

    /** Library version, e.g. "0.1.0". */
    public const val VERSION: String = BuildConfig.VERSION

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var engine: StrudelEngine? = null

    /** Called on the main thread with Strudel's console output. */
    @Volatile
    public var logListener: LogListener? = null

    /** Whether [initialize] has been called. */
    public val isInitialized: Boolean get() = engine != null

    /**
     * Starts audio output and loads the Strudel engine (a few hundred ms).
     * Safe to call more than once; later calls only reopen audio output.
     */
    @JvmOverloads
    public fun initialize(context: Context, onReady: ((Result<Unit>) -> Unit)? = null) {
        val e = synchronized(this) {
            engine ?: StrudelEngine(context.applicationContext).also { created ->
                engine = created
                Native.logHandler = { level, message ->
                    logListener?.let { listener ->
                        val l = LogLevel.entries[level.coerceIn(0, 3)]
                        main.post { listener.onLog(l, message) }
                    }
                }
            }
        }
        // Audio first, so samples can be registered at the device rate.
        e.startAudio { audio ->
            if (audio is StrudelEngine.Result.Error) deliver(onReady, Result.Failure(audio.message))
            else e.init { deliver(onReady, it.toPublic()) }
        }
    }

    /**
     * Evaluates [code] and plays it. If something is already playing, the new
     * pattern takes over on the running clock, as when re-evaluating on
     * strudel.cc. The callback receives the tempo in cycles per second, or the
     * evaluation error.
     */
    @JvmOverloads
    public fun play(code: String, onResult: ((Result<Double>) -> Unit)? = null) {
        requireEngine().play(code) { deliver(onResult, it.toPublic()) }
    }

    /** Evaluates [code] without playing it, e.g. to check it for errors. */
    @JvmOverloads
    public fun evaluate(code: String, onResult: ((Result<Double>) -> Unit)? = null) {
        requireEngine().evaluate(code) { deliver(onResult, it.toPublic()) }
    }

    /** Stops playback and silences all sounds. */
    public fun stop() {
        engine?.stop()
    }

    // Number of active resume() callers not yet balanced by pause(); main thread.
    private var activeUsers = 0

    // Releasing audio is deferred briefly so that a resume() arriving right
    // after (rotation, switching activities) cancels it.
    private const val PAUSE_DELAY_MS = 500L
    private val deferredPause = Runnable { engine?.pause() }

    /**
     * Marks a caller (typically an activity, in `onStop`) as no longer
     * needing audio. When the last caller has paused, playback is suspended
     * and the audio device released (after a short grace period), keeping
     * the position.
     *
     * Calls are counted and the release is deferred, so configuration
     * changes and switching between activities don't interrupt playback.
     * Pair every [resume] with a [pause].
     */
    public fun pause() {
        main.post {
            if (activeUsers > 0) activeUsers--
            if (activeUsers == 0 && engine != null) {
                main.removeCallbacks(deferredPause)
                main.postDelayed(deferredPause, PAUSE_DELAY_MS)
            }
        }
    }

    /**
     * Marks a caller (typically an activity, in `onStart`) as needing audio.
     * If playback was suspended by [pause], audio output reopens and playback
     * continues where it stopped.
     */
    @JvmOverloads
    public fun resume(onResult: ((Result<Unit>) -> Unit)? = null) {
        val e = engine ?: return deliver(onResult, Result.Failure("Krapfen is not initialized"))
        main.post {
            activeUsers++
            main.removeCallbacks(deferredPause)
            e.resume { deliver(onResult, it.toPublic()) }
        }
    }

    /** Output volume; 1.0 matches strudel.cc's loudness. */
    public var volume: Float = 1f
        set(value) {
            field = value.coerceAtLeast(0f)
            Native.setVolume(field)
        }

    /** Peak sample level (0..1) of the most recent audio block, e.g. for a meter. */
    public val outputPeak: Float get() = if (engine != null) Native.peak() else 0f

    /** Current playback state. */
    public fun status(onResult: (Result<Status>) -> Unit) {
        requireEngine().status { r ->
            deliver(onResult, r.toPublic { s ->
                Status(s.started, s.cycle, s.cps, s.time, s.scheduled, s.late, s.sampleFiles)
            })
        }
    }

    /**
     * Stops playback and closes audio output. [initialize] can start it
     * again; loaded patterns and samples are kept.
     */
    public fun shutdown() {
        engine?.shutdown()
    }

    private fun requireEngine(): StrudelEngine =
        engine ?: throw IllegalStateException("Call Krapfen.initialize(context) first")

    private fun <T> deliver(callback: ((Result<T>) -> Unit)?, result: Result<T>) {
        if (callback != null) main.post { callback(result) }
    }

    private fun <T> StrudelEngine.Result<T>.toPublic(): Result<T> = toPublic { it }

    private inline fun <T, R> StrudelEngine.Result<T>.toPublic(map: (T) -> R): Result<R> = when (this) {
        is StrudelEngine.Result.Ok -> Result.Success(map(value))
        is StrudelEngine.Result.Error -> Result.Failure(message)
    }
}
