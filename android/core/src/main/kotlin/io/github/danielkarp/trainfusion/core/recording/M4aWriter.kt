package io.github.danielkarp.trainfusion.core.recording

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.min

/**
 * AAC access units (raw, as MediaCodec's AAC encoder gives them) into an .m4a: ftyp, mdat,
 * and the moov written when it's closed. Like the iOS app's AVAudioFile, the file has an
 * edit list that skips the encoder's priming and padding, so a decoder that honours it
 * (ffmpeg, and so librosa / torchaudio / PyAV) yields exactly the frames that were captured,
 * frame 0 first (docs/recorder.md, "Timestamps"). Android's MediaMuxer can't be told the
 * priming, which is why this exists.
 *
 * A file that is never closed (the app killed) has no moov and can't be played, as on iOS.
 * Not thread-safe.
 */
class M4aWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitRate: Int,
    private val framesPerSample: Int = 1024,
) {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private var position = 0L
    private val sizes = IntList()
    private var maxSize = 0
    private var mdatStart = 0L
    private var codecConfig: ByteArray = audioSpecificConfig(sampleRate, channels)
    private var closed = false

    /** Access units written so far. */
    val samples: Int get() = sizes.size

    init {
        write(box("ftyp") {
            writeBytes("M4A "); writeInt(0)
            writeBytes("M4A "); writeBytes("mp42"); writeBytes("isom")
        })
        mdatStart = position
        // a 64-bit size, filled in on close
        write(ByteArray(16).also { put32(it, 0, 1); "mdat".toByteArray().copyInto(it, 4) })
    }

    /** The decoder configuration (AudioSpecificConfig) the encoder gave, if it differs from the default. */
    fun setCodecConfig(csd: ByteArray) {
        codecConfig = csd.copyOf()
    }

    fun writeSample(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        check(!closed)
        out.write(data, offset, length)
        position += length
        sizes.add(length)
        maxSize = max(maxSize, length)
    }

    /**
     * Writes the moov and closes the file. `primingFrames`: how many decoded frames come
     * before captured frame 0 (the encoder's delay); `capturedFrames`: how many frames went
     * into the encoder, so the encoder's padding at the end is cut off too.
     */
    fun close(primingFrames: Int, capturedFrames: Long) {
        if (closed) return
        closed = true
        val n = sizes.size
        val mediaFrames = n.toLong() * framesPerSample
        val priming = primingFrames.coerceIn(0, max(0L, mediaFrames).toInt())
        val presented = min(capturedFrames, mediaFrames - priming).coerceAtLeast(0)
        // chunks of about a second
        val perChunk = max(1, sampleRate / framesPerSample)
        val chunkOffsets = ArrayList<Long>()
        run {
            var offset = mdatStart + 16
            for (i in 0 until n) {
                if (i % perChunk == 0) chunkOffsets.add(offset)
                offset += sizes[i]
            }
        }
        val co64 = (chunkOffsets.lastOrNull() ?: 0L) > 0xFFFF_FFFFL
        val seconds = mediaFrames.toDouble() / sampleRate
        val avgBitrate = if (seconds > 0) ((position - mdatStart - 16) * 8 / seconds).toLong() else bitRate.toLong()
        val maxBitrate = max(avgBitrate, maxSize.toLong() * 8 * sampleRate / framesPerSample)

        val moov = box("moov") {
            write(fullBox("mvhd", 0, 0) {
                writeInt(0); writeInt(0)  // created, modified
                writeInt(sampleRate)  // movie timescale: samples, so the edit list is exact
                writeInt(presented.toInt())
                writeInt(0x00010000); writeShort(0x0100); write(ByteArray(10))
                writeMatrix()
                write(ByteArray(24))
                writeInt(2)  // next track id
            })
            write(box("trak") {
                write(fullBox("tkhd", 0, 0x7) {  // enabled, in movie, in preview
                    writeInt(0); writeInt(0)
                    writeInt(1)  // track id
                    writeInt(0)
                    writeInt(presented.toInt())
                    write(ByteArray(8))
                    writeShort(0); writeShort(0)  // layer, alternate group
                    writeShort(0x0100); writeShort(0)  // volume
                    writeMatrix()
                    writeInt(0); writeInt(0)  // width, height
                })
                write(box("edts") {
                    write(fullBox("elst", 0, 0) {
                        writeInt(1)
                        writeInt(presented.toInt())  // segment duration (movie timescale)
                        writeInt(priming)  // media time: where the captured audio starts
                        writeShort(1); writeShort(0)  // rate 1.0
                    })
                })
                write(box("mdia") {
                    write(fullBox("mdhd", 0, 0) {
                        writeInt(0); writeInt(0)
                        writeInt(sampleRate)
                        writeInt(mediaFrames.toInt())
                        writeShort(0x55C4)  // "und"
                        writeShort(0)
                    })
                    write(fullBox("hdlr", 0, 0) {
                        writeInt(0); writeBytes("soun"); write(ByteArray(12)); writeBytes("SoundHandler"); writeByte(0)
                    })
                    write(box("minf") {
                        write(fullBox("smhd", 0, 0) { writeShort(0); writeShort(0) })
                        write(box("dinf") {
                            write(fullBox("dref", 0, 0) { writeInt(1); write(fullBox("url ", 0, 1) {}) })
                        })
                        write(box("stbl") {
                            write(fullBox("stsd", 0, 0) {
                                writeInt(1)
                                write(box("mp4a") {
                                    write(ByteArray(6)); writeShort(1)  // data reference index
                                    write(ByteArray(8))
                                    writeShort(channels); writeShort(16)
                                    writeShort(0); writeShort(0)
                                    writeInt(sampleRate shl 16)
                                    write(esds(maxBitrate, avgBitrate))
                                })
                            })
                            write(fullBox("stts", 0, 0) {
                                if (n > 0) { writeInt(1); writeInt(n); writeInt(framesPerSample) } else writeInt(0)
                            })
                            write(fullBox("stsc", 0, 0) {
                                val chunks = chunkOffsets.size
                                val last = if (n == 0) 0 else n - (chunks - 1) * perChunk
                                if (n == 0) {
                                    writeInt(0)
                                } else if (last == perChunk || chunks == 1) {
                                    writeInt(1); writeInt(1); writeInt(if (chunks == 1) n else perChunk); writeInt(1)
                                } else {
                                    writeInt(2)
                                    writeInt(1); writeInt(perChunk); writeInt(1)
                                    writeInt(chunks); writeInt(last); writeInt(1)
                                }
                            })
                            write(fullBox("stsz", 0, 0) {
                                writeInt(0); writeInt(n)
                                for (i in 0 until n) writeInt(sizes[i])
                            })
                            if (co64) {
                                write(fullBox("co64", 0, 0) { writeInt(chunkOffsets.size); chunkOffsets.forEach { writeLong(it) } })
                            } else {
                                write(fullBox("stco", 0, 0) { writeInt(chunkOffsets.size); chunkOffsets.forEach { writeInt(it.toInt()) } })
                            }
                        })
                    })
                })
            })
        }
        val mdatSize = position - mdatStart
        write(moov)
        out.close()
        RandomAccessFile(file, "rw").use { f ->
            f.seek(mdatStart + 8)
            f.writeLong(mdatSize)
        }
    }

    /** Closes without a moov (e.g. the recording failed to start): the file is then removed. */
    fun abandon() {
        closed = true
        try { out.close() } catch (_: Exception) {}
        file.delete()
    }

    private fun esds(maxBitrate: Long, avgBitrate: Long): ByteArray {
        val dsi = descriptor(0x05, codecConfig)
        val dcd = descriptor(0x04, ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply {
                writeByte(0x40)  // MPEG-4 audio
                writeByte((0x05 shl 2) or 1)  // audio stream
                val buffer = max(maxSize, 1)
                writeByte(buffer shr 16); writeShort(buffer and 0xFFFF)
                writeInt(maxBitrate.toInt()); writeInt(avgBitrate.toInt())
                write(dsi)
            }
        }.toByteArray())
        val sl = descriptor(0x06, byteArrayOf(0x02))
        val es = descriptor(0x03, ByteArrayOutputStream().also { b ->
            DataOutputStream(b).apply { writeShort(0); writeByte(0); write(dcd); write(sl) }
        }.toByteArray())
        return fullBox("esds", 0, 0) { write(es) }
    }

    private fun write(bytes: ByteArray) {
        out.write(bytes)
        position += bytes.size
    }

    companion object {
        private val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** AudioSpecificConfig for AAC-LC (object type 2). */
        fun audioSpecificConfig(sampleRate: Int, channels: Int): ByteArray {
            val index = rates.indexOf(sampleRate).let { if (it < 0) 0xF else it }
            val bits = (2 shl 11) or (index shl 7) or (channels shl 3)
            return if (index == 0xF) {
                // explicit 24-bit rate: 5 + 4 + 24 + 4 + 3 bits
                val v = (2L shl 35) or (0xFL shl 31) or (sampleRate.toLong() shl 7) or (channels.toLong() shl 3)
                ByteArray(5) { (v shr (32 - 8 * it)).toByte() }
            } else {
                byteArrayOf((bits shr 8).toByte(), bits.toByte())
            }
        }

        private fun put32(a: ByteArray, at: Int, v: Int) {
            a[at] = (v shr 24).toByte(); a[at + 1] = (v shr 16).toByte(); a[at + 2] = (v shr 8).toByte(); a[at + 3] = v.toByte()
        }

        private fun box(type: String, body: DataOutputStream.() -> Unit): ByteArray {
            val content = ByteArrayOutputStream().also { DataOutputStream(it).body() }.toByteArray()
            return ByteArrayOutputStream().also { b ->
                DataOutputStream(b).apply { writeInt(content.size + 8); writeBytes(type); write(content) }
            }.toByteArray()
        }

        private fun fullBox(type: String, version: Int, flags: Int, body: DataOutputStream.() -> Unit): ByteArray =
            box(type) { writeInt((version shl 24) or flags); body() }

        private fun descriptor(tag: Int, body: ByteArray): ByteArray = ByteArrayOutputStream().also { b ->
            b.write(tag)
            // expandable size: 7 bits a byte, high bit set on all but the last
            val len = body.size
            val groups = ArrayList<Int>()
            var v = len
            do { groups.add(v and 0x7F); v = v shr 7 } while (v > 0)
            for (i in groups.indices.reversed()) b.write(groups[i] or if (i > 0) 0x80 else 0)
            b.write(body)
        }.toByteArray()

        private fun DataOutputStream.writeMatrix() {
            for (v in intArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)) writeInt(v)
        }
    }

    private class IntList {
        var a = IntArray(4096)
        var size = 0
        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }
        operator fun get(i: Int) = a[i]
    }
}

/**
 * How many frames an AAC encoder + decoder pair delays audio by (the "priming" the edit list
 * skips), measured by sending a noise burst through them: the lag at which the decoded
 * signal best matches the original. The app measures its encoder this way once (Android
 * doesn't say what its encoder's delay is).
 */
object EncoderDelay {
    /** One second at `sampleRate`: silence with a burst of deterministic noise from 0.25 s for 0.1 s. */
    fun signal(sampleRate: Int): FloatArray {
        val x = FloatArray(sampleRate)
        val start = sampleRate / 4
        var seed = 12345L
        for (i in 0 until sampleRate / 10) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            x[start + i] = 0.3f * (((seed ushr 33).toInt() and 0xFFFF) / 32768f - 1f)
        }
        return x
    }

    /** The lag (frames) of `signal(sampleRate)` in `decoded`, and how well it matches (correlation, 0...1); null if it isn't clearly there. */
    fun measure(decoded: FloatArray, sampleRate: Int, maxLag: Int = 8192): Pair<Int, Double>? {
        val x = signal(sampleRate)
        val start = sampleRate / 4
        val len = sampleRate / 10
        var ex = 0.0
        for (i in 0 until len) ex += x[start + i].toDouble() * x[start + i]
        var bestLag = -1
        var best = 0.0
        for (lag in 0..maxLag) {
            if (start + lag + len > decoded.size) break
            var xy = 0.0
            var yy = 0.0
            for (i in 0 until len) {
                val y = decoded[start + lag + i].toDouble()
                xy += x[start + i] * y
                yy += y * y
            }
            if (yy <= 0) continue
            val r = xy / kotlin.math.sqrt(ex * yy)
            if (r > best) { best = r; bestLag = lag }
        }
        return if (bestLag >= 0 && best > 0.5) bestLag to best else null
    }
}
