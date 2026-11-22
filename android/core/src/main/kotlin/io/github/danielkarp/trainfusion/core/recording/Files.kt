package io.github.danielkarp.trainfusion.core.recording

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Appends text to a .gz file as it arrives (ios/TrainRecorder/GzipWriter.swift). Not
 * thread-safe: use from one thread. Flushed every few seconds (a sync flush), so a file cut
 * short (app killed) still decompresses up to the last flush (`gzip -dc` complains about the
 * missing end but outputs the data).
 */
class GzipWriter(file: File, header: String, private val now: () -> Double = { System.nanoTime() / 1e9 }) {
    private var out: OutputStream? = GZIPOutputStream(FileOutputStream(file), 1 shl 16, true)
    private var lastFlush = now()

    init { write(header) }

    /** (A failed write, e.g. a full disk, loses that line, as gzputs does, rather than stopping the caller.) */
    fun write(text: String) {
        val o = out ?: return
        try {
            o.write(text.toByteArray(Charsets.US_ASCII))
            val t = now()
            if (t - lastFlush > 5) {
                o.flush()
                lastFlush = t
            }
        } catch (_: IOException) {
        }
    }

    fun close() {
        try { out?.close() } catch (_: IOException) {}
        out = null
    }
}

object GzipCsv {
    /**
     * The chosen columns of a numeric .csv.gz (header skipped), row after row in one flat
     * array. A missing file gives an empty array (older recordings lack some sensors); a file
     * cut short (the app was killed) gives the rows up to where it ends.
     */
    fun read(file: File, columns: IntArray): DoubleArray {
        if (!file.exists()) return DoubleArray(0)
        val out = DoubleList()
        val last = columns.maxOrNull() ?: 0
        // decompressed piece by piece, so a cut-off end loses only what comes after the cut
        val line = StringBuilder()
        var header = true
        val chunk = ByteArray(1 shl 16)
        try {
            GZIPInputStream(BufferedInputStream(file.inputStream(), 1 shl 16), 1 shl 16).use { z ->
                while (true) {
                    val k = z.read(chunk)
                    if (k < 0) break
                    for (i in 0 until k) {
                        val c = chunk[i].toInt()
                        if (c == '\n'.code) {
                            if (header) header = false else parse(line.toString(), columns, last, out)
                            line.setLength(0)
                        } else {
                            line.append(c.toChar())
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // a truncated or corrupt file: keep the lines read so far
        }
        return out.toArray()
    }

    private fun parse(line: String, columns: IntArray, last: Int, out: DoubleList) {
        var col = 0
        var want = 0
        var start = 0
        val n = line.length
        while (col <= last && start <= n) {
            var end = line.indexOf(',', start)
            if (end < 0) end = n
            if (want < columns.size && columns[want] == col) {
                val v = line.substring(start, end).toDoubleOrNull()
                if (v == null) { out.removeLast(want); return }
                out.add(v)
                want += 1
            }
            col += 1
            start = end + 1
            if (end == n) break
        }
        if (want < columns.size) out.removeLast(want)  // a cut-off last line
    }

    private class DoubleList {
        var a = DoubleArray(1024)
        var size = 0
        fun add(v: Double) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }
        fun removeLast(k: Int) { size -= k }
        fun toArray() = a.copyOf(size)
    }
}

/** A recording's id is its start time in UTC, e.g. 20000102T030405Z. */
object RecordingID {
    private fun format() = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    val pattern = Regex("^[0-9]{8}T[0-9]{6}Z$")

    fun make(date: Date): String = format().format(date)

    fun date(id: String): Date? = if (!pattern.matches(id)) null else try { format().parse(id) } catch (_: Exception) { null }
}

/** Decimal byte counts as iOS's ByteCountFormatter (.file) writes them: 980 KB, 45.2 MB, 200 MB, 1.23 GB. */
fun formatBytes(bytes: Long): String {
    val b = bytes.toDouble()
    fun trim(s: String) = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    return when {
        bytes < 1000 -> if (bytes == 1L) "1 byte" else "$bytes bytes"
        b < 999_500 -> "${Math.round(b / 1e3)} KB"
        b < 999_950_000 -> trim(String.format(Locale.US, "%.1f", b / 1e6)) + " MB"
        else -> trim(String.format(Locale.US, "%.2f", b / 1e9)) + " GB"
    }
}
