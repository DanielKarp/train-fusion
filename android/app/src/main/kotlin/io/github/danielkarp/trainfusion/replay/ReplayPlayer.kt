package io.github.danielkarp.trainfusion.replay

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.danielkarp.trainfusion.TrainFusionApp
import io.github.danielkarp.trainfusion.core.inference.AudioReader
import io.github.danielkarp.trainfusion.core.inference.Replay
import io.github.danielkarp.trainfusion.core.inference.ReplayControl
import io.github.danielkarp.trainfusion.core.recording.Mp4
import io.github.danielkarp.trainfusion.platform.Storage
import io.github.danielkarp.trainfusion.recording.InferenceStore
import io.github.danielkarp.trainfusion.recording.SharedModel
import io.github.danielkarp.trainfusion.store.Recording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Plays an existing recording through the same engine, at a chosen speed, to try the model
 * without being on a train (ios/TrainRecorder/Inference/Drivers.swift's ReplayPlayer).
 */
class ReplayPlayer(private val app: TrainFusionApp) {
    var recording by mutableStateOf<Recording?>(null)  // what is loaded
        private set
    var paused by mutableStateOf(false)
        private set
    var atEnd by mutableStateOf(false)
        private set
    var position by mutableIntStateOf(0)  // seconds into the recording
        private set
    private var speedState by mutableDoubleStateOf(16.0)
    /** Recording seconds per second; 0 = as fast as it goes. */
    var speed: Double
        get() = speedState
        set(v) { speedState = v; control.speed = if (v > 0) v else null }
    val store = InferenceStore()
    private val control = ReplayControl()
    private var job: Job? = null

    val playing: Boolean get() = recording != null && !paused && !atEnd

    /** Load `recording` and play it from the beginning. `dump`: also write every second's features and belief to replay_dump/<id>.jsonl. */
    fun play(recording: Recording, dump: Boolean = false) {
        exit()
        store.reset()
        this.recording = recording
        control.paused = false
        control.speed = if (speed > 0) speed else null
        control.takeCommand()
        job = app.scope.launch(Dispatchers.Default) {
            val out = if (dump) File(Storage.replayDumps(app), "${recording.id}.jsonl").outputStream().buffered() else null
            try {
                val model = SharedModel.get(app).getOrThrow()
                Replay.run(recording.dir, model, { MediaAudioReader(it) }, control, out) { update ->
                    withContext(Dispatchers.Main) {
                        if (this@ReplayPlayer.recording?.id != recording.id) return@withContext
                        if (update.restarted) store.reset()
                        store.publish(update.beliefs)
                        position = update.second
                        atEnd = update.atEnd
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { store.message = "Replay failed: ${e.message}" }
            } finally {
                try { out?.close() } catch (_: Exception) {}
            }
        }
    }

    /** Pause / resume; at the end, start over. */
    fun toggle() {
        if (recording == null) return
        if (atEnd) {
            paused = false
            control.paused = false
            control.send(ReplayControl.Command.RESTART)
        } else {
            paused = !paused
            control.paused = paused
        }
    }

    /** Skip to the next door opening the model hears (or the end of the recording). */
    fun nextStop() = control.send(ReplayControl.Command.NEXT_STOP)

    /** Back to the latest door opening before now (or the beginning). */
    fun previousStop() = control.send(ReplayControl.Command.PREVIOUS_STOP)

    /** Leave replay altogether. */
    fun exit() {
        job?.cancel()
        job = null
        recording = null
        paused = false
        atEnd = false
        position = 0
    }
}

/**
 * A recording's audio (its first segment) decoded by the phone's AAC decoder, to mono floats,
 * with the encoder's priming dropped by what the file's edit list says (so iOS's recordings
 * replay aligned too), frame 0 first.
 */
class MediaAudioReader(file: File) : AudioReader {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val channels: Int
    override val sampleRate: Int
    private var skip: Long
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var pending = FloatArray(0)
    private var pendingAt = 0
    private var pendingCount = 0
    private var pcmFloat = false

    init {
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IOException("no audio in ${file.name}")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        // the decoder isn't to trim anything: the priming is dropped here, from the edit list
        for (key in listOf("encoder-delay", "encoder-padding")) if (format.containsKey(key)) format.removeKey(key)
        skip = Mp4.primingFrames(file, sampleRate)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    override fun read(buf: FloatArray, frames: Int): Int {
        var n = 0
        while (n < frames) {
            if (pendingAt < pendingCount) {
                val k = minOf(frames - n, pendingCount - pendingAt)
                System.arraycopy(pending, pendingAt, buf, n, k)
                pendingAt += k
                n += k
                continue
            }
            if (outputDone) break
            decodeSome()
        }
        return n
    }

    /** Feeds the decoder and takes what comes out into `pending`. */
    private fun decodeSome() {
        if (!inputDone) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                if (size < 0) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val o = codec.dequeueOutputBuffer(info, 10_000)
        if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val f = codec.outputFormat
            pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
            return
        }
        if (o < 0) return
        val out = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
        out.position(info.offset); out.limit(info.offset + info.size)
        val samples = if (pcmFloat) info.size / 4 else info.size / 2
        val count = samples / channels
        if (pending.size < count) pending = FloatArray(count)
        if (pcmFloat) {
            val fb = out.asFloatBuffer()
            for (k in 0 until count) { var s = 0f; for (c in 0 until channels) s += fb.get(); pending[k] = s / channels }
        } else {
            val sb = out.asShortBuffer()
            for (k in 0 until count) { var s = 0f; for (c in 0 until channels) s += sb.get() / 32768f; pending[k] = s / channels }
        }
        codec.releaseOutputBuffer(o, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
        // the priming: decoded, but before captured frame 0
        val drop = minOf(skip, count.toLong()).toInt()
        skip -= drop
        pendingAt = drop
        pendingCount = count
    }

    override fun close() {
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        extractor.release()
    }
}
