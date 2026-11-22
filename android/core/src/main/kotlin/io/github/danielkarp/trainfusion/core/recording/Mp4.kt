package io.github.danielkarp.trainfusion.core.recording

import java.io.File
import java.io.RandomAccessFile

/** Just enough of the MP4 format to read an .m4a's edit list. */
object Mp4 {
    /**
     * Where the audio of the first track's edit list starts, in frames at `sampleRate`: the
     * encoder's priming that a decoder skips (2112 in iOS's files, the measured delay in this
     * app's). 0 if the file has no edit list.
     */
    fun primingFrames(file: File, sampleRate: Int): Long = try {
        RandomAccessFile(file, "r").use { f ->
            val moov = find(f, 0, f.length(), "moov") ?: return 0
            val trak = find(f, moov.first, moov.second, "trak") ?: return 0
            val mdia = find(f, trak.first, trak.second, "mdia")
            val timescale = mdia?.let { find(f, it.first, it.second, "mdhd") }?.let { (start, _) ->
                f.seek(start)
                val version = f.readUnsignedByte()
                f.skipBytes(3 + if (version == 1) 16 else 8)
                f.readInt().toLong() and 0xFFFFFFFFL
            } ?: sampleRate.toLong()
            val edts = find(f, trak.first, trak.second, "edts") ?: return 0
            val elst = find(f, edts.first, edts.second, "elst") ?: return 0
            f.seek(elst.first)
            val version = f.readUnsignedByte()
            f.skipBytes(3)
            if (f.readInt() < 1) return 0
            val mediaTime = if (version == 1) { f.skipBytes(8); f.readLong() } else { f.skipBytes(4); f.readInt().toLong() }
            if (mediaTime <= 0 || timescale <= 0) 0 else mediaTime * sampleRate / timescale
        }
    } catch (_: Exception) {
        0
    }

    /** The body (start, end) of the first box of `type` among the boxes in [start, end). */
    private fun find(f: RandomAccessFile, start: Long, end: Long, type: String): Pair<Long, Long>? {
        var at = start
        while (at + 8 <= end) {
            f.seek(at)
            var size = f.readInt().toLong() and 0xFFFFFFFFL
            val t = ByteArray(4).also { f.readFully(it) }.toString(Charsets.US_ASCII)
            var header = 8L
            if (size == 1L) { size = f.readLong(); header = 16 }
            if (size == 0L) size = end - at
            if (size < header) return null
            if (t == type) return (at + header) to minOf(at + size, end)
            at += size
        }
        return null
    }
}
