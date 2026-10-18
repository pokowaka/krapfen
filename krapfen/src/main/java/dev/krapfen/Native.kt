package dev.krapfen

/**
 * JNI bindings for the native engine (src/main/cpp). Internal: use [Krapfen].
 *
 * The class name and the members called from native code ([onHostRequest],
 * [onLog]) must not be renamed; see consumer-rules.pro.
 */
internal object Native {
    init {
        System.loadLibrary("krapfen")
    }

    /** Opens the audio device and starts rendering. Returns 0 on success. */
    @JvmStatic external fun start(): Int

    /** Stops and closes the audio device. */
    @JvmStatic external fun stop()

    /**
     * Sends a dough message, e.g. `dough/play/sound/sine/freq/440/duration/1`.
     * Messages are applied on the audio thread at the start of the next block.
     * Returns false if the message queue is full.
     */
    @JvmStatic external fun evaluate(message: String): Boolean

    /** Peak sample level (0..1) of the most recently rendered block. */
    @JvmStatic external fun peak(): Float

    /** Output volume, 1.0 = strudel.cc loudness. */
    @JvmStatic external fun setVolume(volume: Float)

    /** Device sample rate (valid after [start]), or 0. */
    @JvmStatic external fun sampleRate(): Int

    /**
     * Registers interleaved float PCM (at [sampleRate]) as dough file slot
     * [file]. Returns false if dough isn't running or its sample pool is full.
     */
    @JvmStatic external fun registerSample(file: Int, pcm: FloatArray, channels: Int, freq: Float): Boolean

    /** Receives async requests from JS (`__krapfenRequest`); set by StrudelEngine. */
    @Volatile
    var hostRequestHandler: ((op: String, id: Int, arg: String) -> Unit)? = null

    /** Called from native code on the engine thread. */
    @JvmStatic
    fun onHostRequest(op: String, id: Int, arg: String) {
        hostRequestHandler?.invoke(op, id, arg)
    }

    /** Receives console output from JS; set by Krapfen. */
    @Volatile
    var logHandler: ((level: Int, message: String) -> Unit)? = null

    /** Called from native code (engine thread) for JS console output. */
    @JvmStatic
    fun onLog(level: Int, message: String) {
        logHandler?.invoke(level, message)
    }

    // --- Strudel pattern engine (QuickJS). Call only from StrudelEngine's thread. ---

    /** Loads the Strudel bundle. Returns null on success, else an error. */
    @JvmStatic external fun jsInit(bundle: String): String?

    /**
     * Calls `krapfen[method](...args)` (args: JSON array), awaiting promises.
     * Returns `{"ok":true,"value":...}` or `{"ok":false,"error":"..."}`.
     */
    @JvmStatic external fun jsCall(method: String, argsJson: String): String

    /** Scheduler heartbeat: runs due JS timers; call every few ms while playing. */
    @JvmStatic external fun jsTick()
}
