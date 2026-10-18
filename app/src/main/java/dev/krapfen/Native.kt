package dev.krapfen

/** JNI bindings for the native dough engine (see src/main/cpp/krapfen_jni.c). */
object Native {
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
