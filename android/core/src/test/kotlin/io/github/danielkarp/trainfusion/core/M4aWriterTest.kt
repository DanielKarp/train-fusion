package io.github.danielkarp.trainfusion.core

import io.github.danielkarp.trainfusion.core.recording.EncoderDelay
import io.github.danielkarp.trainfusion.core.recording.M4aWriter
import io.github.danielkarp.trainfusion.core.recording.Mp4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * The .m4a writer, checked with ffmpeg (skipped without it): AAC from ffmpeg's own encoder
 * (whose priming is 1024 frames) goes into the writer with that priming, and ffmpeg must
 * then decode the frames that went in frame 0 first, as docs/recorder.md promises.
 */
class M4aWriterTest {
    private val rate = 48000
    private val ffmpeg = try { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 } catch (_: Exception) { false }

    private fun run(vararg cmd: String, input: ByteArray? = null): ByteArray {
        val p = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        if (input != null) Thread { p.outputStream.use { it.write(input) } }.start() else p.outputStream.close()
        val out = p.inputStream.readBytes()
        check(p.waitFor() == 0) { "${cmd.toList()} failed" }
        return out
    }

    private fun floats(x: FloatArray): ByteArray =
        ByteBuffer.allocate(4 * x.size).order(ByteOrder.LITTLE_ENDIAN).also { b -> x.forEach { b.putFloat(it) } }.array()

    private fun decode(file: File): FloatArray {
        val raw = run("ffmpeg", "-loglevel", "error", "-i", file.path, "-f", "f32le", "-ac", "1", "-ar", "$rate", "-")
        return FloatArray(raw.size / 4).also { ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
    }

    /** ADTS frames -> raw access units. */
    private fun accessUnits(adts: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 7 <= adts.size) {
            check(adts[i].toInt() and 0xFF == 0xFF && adts[i + 1].toInt() and 0xF0 == 0xF0) { "lost ADTS sync at $i" }
            val protectionAbsent = adts[i + 1].toInt() and 1
            val len = ((adts[i + 3].toInt() and 3) shl 11) or ((adts[i + 4].toInt() and 0xFF) shl 3) or ((adts[i + 5].toInt() and 0xFF) shr 5)
            val header = if (protectionAbsent == 1) 7 else 9
            out.add(adts.copyOfRange(i + header, i + len))
            i += len
        }
        return out
    }

    @Test
    fun ffmpegDecodesExactlyTheCapturedFrames() {
        assumeTrue("needs ffmpeg", ffmpeg)
        val dir = Files.createTempDirectory("m4a").toFile()
        // 2.5 s with the calibration burst at its start (and an odd length, so there's padding at the end)
        val x = EncoderDelay.signal(rate) + FloatArray(rate + rate / 2 + 123)
        val adts = run("ffmpeg", "-loglevel", "error", "-f", "f32le", "-ar", "$rate", "-ac", "1", "-i", "-",
            "-c:a", "aac", "-b:a", "96k", "-f", "adts", "-", input = floats(x))
        val units = accessUnits(adts)

        // without an edit list, the decoder's output is late by the encoder's priming
        val rawFile = File(dir, "raw.aac").also { it.writeBytes(adts) }
        val delay = EncoderDelay.measure(decode(rawFile), rate)
        assertNotNull(delay)
        assertEquals(1024, delay!!.first)

        val file = File(dir, "audio_000.m4a")
        val w = M4aWriter(file, rate, 1, 96_000)
        units.forEach { w.writeSample(it) }
        w.close(primingFrames = delay.first, capturedFrames = x.size.toLong())
        val decoded = decode(file)
        assertEquals("frame 0 first", 0, EncoderDelay.measure(decoded, rate)!!.first)
        // and replay on the phone reads the priming back from the edit list
        assertEquals(1024L, Mp4.primingFrames(file, rate))
        assertEquals(0L, Mp4.primingFrames(rawFile, rate))
        // ffmpeg (6.1) skips the priming but keeps the encoder's padding at the end, even
        // with its own files; the edit list says where the captured frames end
        assertTrue("every captured frame: ${decoded.size}", decoded.size in x.size until x.size + 1024)
        // and it's a file players accept
        val probe = String(run("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,sample_rate,channels", "-of", "csv=p=0", file.path)).trim()
        assertEquals("aac,48000,1", probe)
        dir.deleteRecursively()
    }

    @Test
    fun audioSpecificConfig() {
        assertEquals(listOf(0x11, 0x88), M4aWriter.audioSpecificConfig(48000, 1).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x12, 0x10), M4aWriter.audioSpecificConfig(44100, 2).map { it.toInt() and 0xFF })
    }
}
