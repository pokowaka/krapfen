package dev.krapfen

/**
 * A sound effect loaded with [Krapfen.loadSound]: decoded audio held by the
 * native engine, ready to play with low latency. Also playable from Strudel
 * patterns as `s("name")`.
 */
public class Sound internal constructor(
    /** The name given to [Krapfen.loadSound]. */
    public val name: String,
    internal val file: Int,
    /** Length in seconds at speed 1. */
    public val duration: Double,
) {
    override fun toString(): String = "Sound($name, %.3f s)".format(duration)
}

/**
 * One playback of a [Sound], from [Krapfen.playSound] or [Krapfen.loopSound].
 * Thread-safe; calls take effect at the next audio block.
 */
public class SoundHandle internal constructor(
    internal val group: Int,
    volume: Float,
    pan: Float,
    /** Whether this is a loop ([Krapfen.loopSound]). */
    public val isLoop: Boolean,
) {
    @Volatile
    internal var stopped: Boolean = false
        private set

    /** Volume of this playback (1.0 = the sound's original level), times [Krapfen.effectsVolume]. */
    @Volatile
    public var volume: Float = volume
        set(value) {
            field = value.coerceAtLeast(0f)
            if (!stopped) Krapfen.updateSound(this)
        }

    /** Stereo position: 0 = left, 0.5 = centre, 1 = right. */
    @Volatile
    public var pan: Float = pan
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (!stopped) Krapfen.updateSound(this)
        }

    /** Stops this playback (with a short release). Safe to call more than once. */
    public fun stop() {
        if (stopped) return
        stopped = true
        Krapfen.stopSound(this)
    }
}
