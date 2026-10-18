package dev.krapfen.demo

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedurally generated demo sound effects, encoded as 16-bit mono WAV
 * files (the format a game would load from its assets).
 */
object DemoSounds {
    private const val RATE = 44100

    /** A descending sine chirp (0.25 s). */
    fun laser(): ByteArray = wav(0.25) { t ->
        val f = 300 + 1200 * exp(-t * 18)
        sin(2 * PI * phase(f, t)) * exp(-t * 9)
    }

    /** A low-passed noise burst (0.8 s). */
    fun explosion(): ByteArray {
        val rnd = Random(7)
        var lp = 0.0
        return wav(0.8) { t ->
            lp += (rnd.nextDouble(-1.0, 1.0) - lp) * (0.02 + 0.3 * exp(-t * 6))
            lp * 2.5 * exp(-t * 4.5)
        }
    }

    /**
     * An engine hum that loops seamlessly: 1 s containing whole periods of
     * 55, 110 and 165 Hz.
     */
    fun hum(): ByteArray = wav(1.0) { t ->
        0.5 * sin(2 * PI * 55 * t) + 0.25 * sin(2 * PI * 110 * t) + 0.12 * sin(2 * PI * 165 * t)
    }

    // Phase of a sine whose frequency follows f(t) = 300 + 1200 e^(-18t).
    private fun phase(@Suppress("UNUSED_PARAMETER") f: Double, t: Double): Double =
        300 * t + 1200 * (1 - exp(-t * 18)) / 18

    private fun wav(seconds: Double, signal: (Double) -> Double): ByteArray {
        val n = (seconds * RATE).toInt()
        val pcm = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val v = (signal(i.toDouble() / RATE) * 0.8).coerceIn(-1.0, 1.0)
            pcm.putShort((v * 32767).toInt().toShort())
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + n * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(n * 2)
        }
        return ByteArrayOutputStream().apply { write(header.array()); write(pcm.array()) }.toByteArray()
    }
}
