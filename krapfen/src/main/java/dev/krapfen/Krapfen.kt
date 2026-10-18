package dev.krapfen

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
 *
 * val laser = Krapfen.loadSound("laser", bytes)  // wav/ogg/mp3/...
 * Krapfen.playSound(laser, volume = .8f, pan = .3f)
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
        if (musicVolume != 1f) e.setMusicVolume(musicVolume)
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

    /** Stops the pattern playing and silences its notes. Sound effects keep playing. */
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

    /** Output volume of everything; 1.0 matches strudel.cc's loudness. */
    public var volume: Float = 1f
        set(value) {
            field = value.coerceAtLeast(0f)
            Native.setVolume(field)
        }

    /**
     * Volume of patterns ([play]), multiplied with [volume]. Applies to notes
     * scheduled from now on (about 0.1 s ahead).
     */
    public var musicVolume: Float = 1f
        set(value) {
            field = value.coerceAtLeast(0f)
            engine?.setMusicVolume(field)
        }

    // --- sound effects -------------------------------------------------------

    /**
     * Volume of sound effects ([playSound], [loopSound]), multiplied with
     * [volume]. Running loops follow changes immediately.
     */
    public var effectsVolume: Float = 1f
        set(value) {
            field = value.coerceAtLeast(0f)
            activeLoops.forEach { updateSound(it) }
        }

    // dough plays sample voices at pcm * gain * 0.5 and the output stage
    // multiplies by 3, so gain * 2/3 plays a sound at its original level
    // (matching how patterns play samples, see js/src/krapfen.mjs).
    private const val SAMPLE_GAIN = 2f / 3f

    private val soundsByName = ConcurrentHashMap<String, Sound>()
    private val nextSoundSlot = AtomicInteger(0)
    // dough voice groups identify playbacks; patterns don't use groups.
    private val nextGroup = AtomicInteger(1)
    private val activeLoops: MutableSet<SoundHandle> = ConcurrentHashMap.newKeySet()

    /**
     * Decodes [data] (an encoded audio file: wav, ogg, mp3, flac, aac, ...)
     * and loads it into the audio engine as a sound effect named [name].
     * It can then be played with [playSound]/[loopSound], and from patterns
     * as `s("name")`.
     *
     * Blocks while decoding (short sounds take a few milliseconds), and until
     * audio output has started after [initialize]. Loading a name that is
     * already loaded returns the existing sound.
     *
     * @throws IllegalStateException if Krapfen isn't initialized, audio
     *   output didn't start, or the engine's sample memory is full.
     * @throws java.io.IOException if the data can't be decoded.
     */
    public fun loadSound(name: String, data: ByteArray): Sound {
        soundsByName[name]?.let { return it }
        val e = requireEngine()
        check(e.awaitAudio(5_000)) { "audio output did not start" }
        synchronized(soundsByName) {
            soundsByName[name]?.let { return it }
            val slot = Native.soundFileBase() + nextSoundSlot.get()
            check(slot < Native.maxFiles()) { "too many sounds loaded (max ${Native.maxFiles() - Native.soundFileBase()})" }
            val seconds = e.loadSound(name, data, slot)
            nextSoundSlot.incrementAndGet()
            val sound = Sound(name, slot, seconds)
            soundsByName[name] = sound
            e.registerSoundForPatterns(name, slot, seconds)
            return sound
        }
    }

    /** A sound loaded earlier with [loadSound], or null. */
    public fun getSound(name: String): Sound? = soundsByName[name]

    /**
     * Plays [sound] once, now (at the next audio block). [volume] 1.0 is the
     * sound's original level; [pan] 0 = left, 0.5 = centre, 1 = right;
     * [speed] also changes the pitch (2 = an octave up).
     */
    @JvmOverloads
    public fun playSound(sound: Sound, volume: Float = 1f, pan: Float = 0.5f, speed: Float = 1f): SoundHandle =
        start(sound, volume, pan, speed, loop = false)

    /**
     * Plays [sound] in a seamless loop until [SoundHandle.stop] is called.
     * Parameters as for [playSound].
     */
    @JvmOverloads
    public fun loopSound(sound: Sound, volume: Float = 1f, pan: Float = 0.5f, speed: Float = 1f): SoundHandle =
        start(sound, volume, pan, speed, loop = true).also { activeLoops.add(it) }

    private fun start(sound: Sound, volume: Float, pan: Float, speed: Float, loop: Boolean): SoundHandle {
        val handle = SoundHandle(nextGroup.getAndIncrement(), volume.coerceAtLeast(0f), pan.coerceIn(0f, 1f), loop)
        val msg = StringBuilder("dough/play/file/").append(sound.file)
            .append("/group/").append(handle.group)
            .append("/speed/").append(speed)
            .append("/gain/").append(effectiveGain(handle))
            .append("/pan/").append(handle.pan)
        if (loop) msg.append("/loop/1")
        Native.evaluate(msg.toString())
        return handle
    }

    private fun effectiveGain(handle: SoundHandle): Float = handle.volume * effectsVolume * SAMPLE_GAIN

    internal fun updateSound(handle: SoundHandle) {
        Native.evaluate("dough/update_group/group/${handle.group}/gain/${effectiveGain(handle)}/pan/${handle.pan}")
    }

    internal fun stopSound(handle: SoundHandle) {
        activeLoops.remove(handle)
        Native.evaluate("dough/hush_group/group/${handle.group}")
    }

    /** Peak sample level (0..1) of the most recent audio block, e.g. for a meter. */
    public val outputPeak: Float get() = if (engine != null) Native.peak() else 0f

    /**
     * Starts recording Krapfen's output (what is sent to the speaker, after
     * volume) for up to [maxSeconds]. For debugging and analysis; see
     * [stopRecording].
     */
    @JvmOverloads
    public fun startRecording(maxSeconds: Int = 120): Boolean =
        engine != null && Native.startCapture(maxSeconds)

    /**
     * Stops recording and writes it to [file] as a 32-bit float stereo WAV
     * at the device sample rate. The callback receives the duration in
     * seconds.
     */
    public fun stopRecording(file: java.io.File, onResult: ((Result<Double>) -> Unit)? = null) {
        val pcm = Native.stopCapture()
            ?: return deliver(onResult, Result.Failure("not recording"))
        val rate = Native.sampleRate()
        Thread {
            val result = try {
                WavWriter.writeFloatStereo(file, pcm, rate)
                Result.Success(pcm.size / 2.0 / rate)
            } catch (e: java.io.IOException) {
                Result.Failure(e.message ?: e.toString())
            }
            deliver(onResult, result)
        }.start()
    }

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
