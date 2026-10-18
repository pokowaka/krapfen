package dev.krapfen

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Downloads (with a disk cache), decodes and resamples audio samples for
 * dough, and fetches text resources such as `strudel.json` sample maps.
 *
 * All methods block; call them off the engine and UI threads.
 */
internal class SampleLoader(context: Context) {

    private val cacheDir = File(context.cacheDir, "krapfen-samples").apply { mkdirs() }

    /** Decoded audio: interleaved float PCM. */
    internal class Pcm(val data: FloatArray, val channels: Int, val sampleRate: Int) {
        val frames get() = data.size / channels
    }

    fun fetchText(url: String): String = download(url).readText()

    /**
     * Loads [url] and registers it with dough as file slot [file].
     * Returns the sample's length in seconds.
     */
    fun loadSample(url: String, file: Int): Double {
        val path = download(url).path
        return register(decode(url) { it.setDataSource(path) }, file, url)
    }

    /**
     * Decodes encoded audio ([data]: wav, ogg, mp3, ...) and registers it with
     * dough as file slot [file]. Returns the length in seconds.
     */
    fun loadSound(name: String, data: ByteArray, file: Int): Double =
        register(decode(name) { it.setDataSource(ByteArrayDataSource(data)) }, file, name)

    private fun register(decoded: Pcm, file: Int, label: String): Double {
        val targetRate = Native.sampleRate()
        check(targetRate > 0) { "audio engine not running" }
        val pcm = resample(decoded, targetRate)
        // freq is the sample's reference pitch for dough's `freq`-based
        // repitching; Krapfen always sends `speed` instead, so any constant
        // works.
        check(Native.registerSample(file, pcm.data, pcm.channels, 261.63f)) {
            "dough sample memory is full"
        }
        Log.i(TAG, "sample #$file: ${pcm.frames} frames, ${decoded.channels} ch @ ${decoded.sampleRate} Hz <- $label")
        return pcm.frames / targetRate.toDouble()
    }

    private class ByteArrayDataSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    private fun download(url: String): File {
        val key = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val cached = File(cacheDir, key)
        if (cached.isFile && cached.length() > 0) return cached

        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Krapfen")
            try {
                when (val code = conn.responseCode) {
                    in 200..299 -> {
                        val tmp = File(cacheDir, "$key.part")
                        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        if (!tmp.renameTo(cached)) throw IOException("cannot write cache file")
                        return cached
                    }
                    in 300..399 -> current = URL(current, conn.getHeaderField("Location"))
                    else -> throw IOException("HTTP $code for $url")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("too many redirects for $url")
    }

    /** Decodes any format MediaCodec supports (wav, mp3, ogg, flac, aac, ...). */
    private fun decode(label: String, setSource: (MediaExtractor) -> Unit): Pcm {
        val extractor = MediaExtractor()
        setSource(extractor)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: throw IOException("no audio track in $label")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        val out = FloatList()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            while (fb.hasRemaining()) out.add(fb.get())
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.hasRemaining()) out.add(sb.get() / 32768f)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }

        // dough handles mono and stereo; fold anything wider down to stereo.
        val data = out.toArray()
        if (channels <= 2) return Pcm(data, channels, sampleRate)
        val frames = data.size / channels
        val stereo = FloatArray(frames * 2)
        for (i in 0 until frames) {
            var l = 0f
            var r = 0f
            for (c in 0 until channels) {
                if (c % 2 == 0) l += data[i * channels + c] else r += data[i * channels + c]
            }
            stereo[i * 2] = l / ((channels + 1) / 2)
            stereo[i * 2 + 1] = r / (channels / 2)
        }
        return Pcm(stereo, 2, sampleRate)
    }

    /** Linear-interpolation resampling to dough's sample rate. */
    private fun resample(pcm: Pcm, targetRate: Int): Pcm {
        if (pcm.sampleRate == targetRate || pcm.frames == 0) return Pcm(pcm.data, pcm.channels, targetRate)
        val ch = pcm.channels
        val ratio = pcm.sampleRate.toDouble() / targetRate
        val outFrames = (pcm.frames / ratio).toInt()
        val out = FloatArray(outFrames * ch)
        for (i in 0 until outFrames) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(pcm.frames - 1)
            val i1 = (i0 + 1).coerceAtMost(pcm.frames - 1)
            val frac = (pos - i0).toFloat()
            for (c in 0 until ch) {
                val a = pcm.data[i0 * ch + c]
                val b = pcm.data[i1 * ch + c]
                out[i * ch + c] = a + (b - a) * frac
            }
        }
        return Pcm(out, ch, targetRate)
    }

    /** Growable float buffer without boxing. */
    private class FloatList {
        private var data = FloatArray(1 shl 16)
        private var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    companion object {
        private const val TAG = "Krapfen"
        private const val MAX_REDIRECTS = 5
    }
}
