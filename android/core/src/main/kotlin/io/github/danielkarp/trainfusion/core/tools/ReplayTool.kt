package io.github.danielkarp.trainfusion.core.tools

import io.github.danielkarp.trainfusion.core.inference.AudioReader
import io.github.danielkarp.trainfusion.core.inference.Model
import io.github.danielkarp.trainfusion.core.inference.Replay
import io.github.danielkarp.trainfusion.core.inference.ReplayControl
import kotlinx.coroutines.runBlocking
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/**
 * Replays recordings through the Kotlin port of the model on a laptop, flat out, and writes
 * the same dump the app writes with `-dump` (one JSON line per second), for
 * `python -m app.compare` (android/README.md, "Checking the model's port"). Needs ffmpeg.
 *
 *   ./gradlew :core:replay --args="<recording dir> <out.jsonl>"
 */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("usage: replay <recording dir> <out.jsonl>")
        exitProcess(2)
    }
    val dir = File(args[0])
    val out = File(args[1])
    val model = Model.load(File(System.getProperty("model.json") ?: "../ios/TrainRecorder/model.json"))
    out.parentFile?.mkdirs()
    val seconds = replayToFile(dir, model, out)
    println("$dir: $seconds s -> $out")
}

/** Replays `dir` to its end as fast as it goes, dumping every second to `out`. Returns the seconds replayed. */
fun replayToFile(dir: File, model: Model, out: File): Int {
    class Done : Exception()
    var last = 0
    runBlocking {
        out.outputStream().buffered().use { dump ->
            val control = ReplayControl().apply { speed = null }
            try {
                Replay.run(dir, model, { FfmpegAudioReader(it) }, control, dump) { update ->
                    last = update.second
                    if (update.atEnd) throw Done()
                }
            } catch (_: Done) {
            }
        }
    }
    return last
}

/** A recording's audio decoded by ffmpeg (which honours the .m4a's edit list) to 48 kHz mono floats. */
class FfmpegAudioReader(file: File) : AudioReader {
    private val process = ProcessBuilder(
        "ffmpeg", "-loglevel", "error", "-i", file.path, "-f", "f32le", "-ac", "1", "-ar", "48000", "-",
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val input: InputStream = DataInputStream(process.inputStream.buffered(1 shl 16))
    private val bytes = ByteArray(4 * 48000)

    override val sampleRate = 48000

    override fun read(buf: FloatArray, frames: Int): Int {
        val want = minOf(frames, buf.size) * 4
        var got = 0
        while (got < want) {
            val k = try { input.read(bytes, got, want - got) } catch (_: EOFException) { -1 }
            if (k < 0) break
            got += k
        }
        val n = got / 4
        ByteBuffer.wrap(bytes, 0, n * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(buf, 0, n)
        return n
    }

    override fun close() {
        process.destroy()
    }
}
