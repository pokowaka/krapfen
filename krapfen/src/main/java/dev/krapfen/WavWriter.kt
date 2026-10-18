package dev.krapfen

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes IEEE float WAV files (WAVE_FORMAT_IEEE_FLOAT). */
internal object WavWriter {
    fun writeFloatStereo(file: File, interleaved: FloatArray, sampleRate: Int) {
        val channels = 2
        val dataBytes = interleaved.size * 4
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16)
            putShort(3) // IEEE float
            putShort(channels.toShort()); putInt(sampleRate)
            putInt(sampleRate * channels * 4) // byte rate
            putShort((channels * 4).toShort()) // block align
            putShort(32) // bits per sample
            put("data".toByteArray()); putInt(dataBytes)
        }
        val data = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        data.asFloatBuffer().put(interleaved)
        file.outputStream().use { out ->
            out.write(header.array())
            out.write(data.array())
        }
    }
}
