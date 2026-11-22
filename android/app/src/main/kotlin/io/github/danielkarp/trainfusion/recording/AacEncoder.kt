package io.github.danielkarp.trainfusion.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import io.github.danielkarp.trainfusion.core.recording.EncoderDelay
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM in, AAC-LC access units out (MediaCodec, synchronous). As the iOS app's files:
 * 96 kbit/s per channel. `onSample` gets each access unit, `onConfig` the decoder
 * configuration (AudioSpecificConfig).
 */
class AacEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val onConfig: (ByteArray) -> Unit,
    private val onSample: (ByteArray) -> Unit,
) {
    companion object {
        const val BIT_RATE_PER_CHANNEL = 96_000
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TAG = "AacEncoder"

        /**
         * How many decoded frames come before captured frame 0 with this phone's AAC encoder
         * (what the .m4a's edit list skips, as Apple's 2112): measured once, by putting a
         * noise burst through the encoder and the phone's decoder (EncoderDelay), and kept.
         * null if it couldn't be measured.
         */
        @Synchronized
        fun primingFrames(context: Context): Int? {
            val prefs = context.getSharedPreferences("encoder", Context.MODE_PRIVATE)
            val key = "priming:${codecName()}:${Build.FINGERPRINT}"
            if (prefs.contains(key)) return prefs.getInt(key, 0).takeIf { it >= 0 }
            val measured = try { measure() } catch (e: Exception) { Log.w(TAG, "couldn't measure the encoder's delay", e); null }
            prefs.edit().putInt(key, measured ?: -1).apply()
            return measured
        }

        fun codecName(): String = try {
            MediaCodec.createEncoderByType(MIME).let { c -> c.name.also { c.release() } }
        } catch (_: Exception) {
            "?"
        }

        private fun measure(): Int? {
            val rate = 48000
            val units = ArrayList<ByteArray>()
            var csd: ByteArray? = null
            val enc = AacEncoder(rate, 1, { csd = it }, { units.add(it) })
            val x = EncoderDelay.signal(rate)
            enc.encode(x, 0, x.size)
            enc.finish()
            val config = csd ?: return null
            val decoded = decode(units, config, rate)
            return EncoderDelay.measure(decoded, rate)?.first
        }

        /** The phone's own AAC decoder on raw access units (nothing trimmed). */
        private fun decode(units: List<ByteArray>, csd: ByteArray, rate: Int): FloatArray {
            val format = MediaFormat.createAudioFormat(MIME, rate, 1).apply { setByteBuffer("csd-0", ByteBuffer.wrap(csd)) }
            val dec = MediaCodec.createDecoderByType(MIME)
            dec.configure(format, null, null, 0)
            dec.start()
            val out = ArrayList<Float>(rate * 2)
            val info = MediaCodec.BufferInfo()
            var next = 0
            var inputDone = false
            var pcmFloat = false
            try {
                while (true) {
                    if (!inputDone) {
                        val i = dec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            if (next < units.size) {
                                val buf = dec.getInputBuffer(i)!!
                                buf.clear(); buf.put(units[next])
                                dec.queueInputBuffer(i, 0, units[next].size, next * 1024L * 1_000_000 / rate, 0)
                                next += 1
                            } else {
                                dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            }
                        }
                    }
                    val o = dec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        pcmFloat = dec.outputFormat.let { it.containsKey(MediaFormat.KEY_PCM_ENCODING) && it.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT }
                    } else if (o >= 0) {
                        val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        if (pcmFloat) { val f = buf.asFloatBuffer(); while (f.hasRemaining()) out.add(f.get()) }
                        else { val s = buf.asShortBuffer(); while (s.hasRemaining()) out.add(s.get() / 32768f) }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            } finally {
                dec.stop(); dec.release()
            }
            return out.toFloatArray()
        }
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MIME)
    val name: String get() = codec.name
    private val info = MediaCodec.BufferInfo()
    private var framesIn = 0L
    private var pcm = ShortArray(0)

    init {
        val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE_PER_CHANNEL * channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** `count` mono float frames from x[offset]. */
    fun encode(x: FloatArray, offset: Int, count: Int) {
        if (pcm.size < count) pcm = ShortArray(count)
        for (i in 0 until count) pcm[i] = (x[offset + i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        var done = 0
        while (done < count) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i < 0) { drain(); continue }
            val buf = codec.getInputBuffer(i)!!.order(ByteOrder.nativeOrder())
            buf.clear()
            val n = minOf(count - done, buf.capacity() / 2)
            buf.asShortBuffer().put(pcm, done, n)
            codec.queueInputBuffer(i, 0, n * 2, framesIn * 1_000_000 / sampleRate, 0)
            framesIn += n
            done += n
            drain()
        }
    }

    /** Flushes the encoder (its last access units come out) and releases it. */
    fun finish() {
        try {
            while (true) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i < 0) { drain(); continue }
                codec.queueInputBuffer(i, 0, 0, framesIn * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(untilEnd = true)
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }

    private fun drain(untilEnd: Boolean = false) {
        var waits = 0
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // (at the end: give up after 2 s rather than hang if the codec never says it's done)
                if (untilEnd && ++waits < 200) continue else return
            }
            if (o < 0) continue  // format / buffers changed
            val buf = codec.getOutputBuffer(o)!!
            buf.position(info.offset); buf.limit(info.offset + info.size)
            val bytes = ByteArray(info.size).also { buf.get(it) }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) onConfig(bytes) else if (bytes.isNotEmpty()) onSample(bytes)
            codec.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }
}
