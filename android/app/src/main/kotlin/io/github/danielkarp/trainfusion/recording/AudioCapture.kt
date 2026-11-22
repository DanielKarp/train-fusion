package io.github.danielkarp.trainfusion.recording

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import io.github.danielkarp.trainfusion.core.recording.GzipWriter
import io.github.danielkarp.trainfusion.core.recording.M4aWriter
import io.github.danielkarp.trainfusion.platform.AndroidClock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * The microphone, to AAC in .m4a, with the capture time of every buffer logged
 * (ios/TrainRecorder/AudioCapture.swift).
 *
 * Audio is a sequence of segments (audio_000.m4a, audio_001.m4a, ...): normally one, but
 * whenever capture has to restart (the audio server died, the microphone was taken away) a
 * new segment starts. audio_timing.csv.gz has one row per captured buffer (0.1 s): which
 * segment, the buffer's first frame within the segment's file, and the uptime at which that
 * frame was captured. So frames map to time exactly, and gaps are explicit.
 *
 * Android differences: the input is the phone's own microphone with the UNPROCESSED source
 * where the phone has one (no gain control, noise suppression or other processing; the
 * equivalent of iOS's measurement mode), else VOICE_RECOGNITION (which should have none of
 * those either, but phones vary); `session_mode` in meta.json says which. When another app
 * takes the microphone (a call) Android doesn't stop the recording: it hands it silence,
 * which is kept (time stays continuous) and listed in meta.json's `audio.silenced`.
 */
class AudioCapture(private val context: Context, private val dir: File) {
    companion object {
        const val SAMPLE_RATE = 48000
        const val BUFFER_FRAMES = 4800  // 0.1 s, as the iOS app's tap
    }

    private class Segment(val file: String, val sampleRate: Int, val channels: Int, val writer: M4aWriter, val encoder: AacEncoder) {
        var frames = 0L
        var firstUptime: Double? = null
        var priming: Int? = null
    }

    /** Called on the capture thread with every buffer (frames, count) and the uptime of its first frame. */
    @Volatile var onBuffer: ((FloatArray, Int, Double?) -> Unit)? = null

    private val lock = Any()
    @Volatile private var running = false
    private var thread: Thread? = null
    private var timing: GzipWriter? = null
    private val segments = ArrayList<Segment>()
    private var lastBufferUptime = 0.0
    private var firstBufferLatency: Double? = null
    private var lastError: String? = null
    private var route: Map<String, Any?> = emptyMap()
    private var source = "unprocessed"
    private var stamped = 0L  // buffers whose time came from AudioRecord's own timestamp
    private var estimated = 0L  // ... and those whose time had to be worked out from when they arrived
    private val silenced = ArrayList<Pair<Double, Double?>>()
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** Starts capturing; throws if the microphone can't be started at all. */
    fun start() {
        timing = GzipWriter(File(dir, "audio_timing.csv.gz"), "segment,frame,frames,uptime_s\n")
        val first = try {
            open()
        } catch (e: Exception) {
            timing?.close()
            throw e
        }
        running = true
        thread = Thread({ loop(first) }, "audio-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(10_000)
        thread = null
        recordingCallback?.let { audioManager.unregisterAudioRecordingCallback(it) }
        recordingCallback = null
        synchronized(lock) {
            silenced.lastOrNull()?.let { if (it.second == null) silenced[silenced.size - 1] = it.first to AndroidClock.uptime() }
            timing?.close()
            timing = null
        }
    }

    @SuppressLint("MissingPermission")  // the recorder checks it before starting
    private fun open(): AudioRecord {
        val unprocessed = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val audioSource = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        source = if (unprocessed) "unprocessed" else "voice_recognition"
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val record = AudioRecord.Builder()
            .setAudioSource(audioSource)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min * 2, BUFFER_FRAMES * 4 * 4))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("the microphone couldn't be opened")
        }
        // always the phone's own mic, even with a headset plugged in
        val mic = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (mic != null) record.preferredDevice = mic
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            throw IOException("the microphone didn't start")
        }
        watchSilencing(record)
        val device = record.routedDevice
        synchronized(lock) {
            route = mapOf(
                "input_port" to (device?.productName?.toString()),
                "input_port_type" to (device?.let { deviceType(it.type) }),
                "input_data_source" to null,
                // AudioRecord's timestamps are when a frame was captured: nothing to subtract
                "input_latency_s" to 0.0,
                "io_buffer_duration_s" to record.bufferSizeInFrames.toDouble() / SAMPLE_RATE,
            )
        }
        return record
    }

    /** Notes when Android gives this recording silence because another app has the microphone. */
    private fun watchSilencing(record: AudioRecord) {
        recordingCallback?.let { audioManager.unregisterAudioRecordingCallback(it) }
        val session = record.audioSessionId
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
                val now = AndroidClock.uptime()
                synchronized(lock) {
                    val open = silenced.lastOrNull()?.second == null && silenced.isNotEmpty()
                    if (mine.isClientSilenced && !open) silenced.add(now to null)
                    if (!mine.isClientSilenced && open) silenced[silenced.size - 1] = silenced.last().first to now
                }
            }
        }
        audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        recordingCallback = callback
    }

    private fun loop(first: AudioRecord) {
        var record: AudioRecord? = first
        val buf = FloatArray(BUFFER_FRAMES)
        val ts = AudioTimestamp()
        while (running) {
            val r = record ?: try {
                open()
            } catch (e: Exception) {
                setError("audio stopped: ${e.message}")
                // ... and keep trying, e.g. for as long as something else has the microphone
                sleepWhileRunning(3000)
                null
            } ?: continue
            record = r
            val segment = newSegment()
            var framesInRecord = 0L
            while (running) {
                val n = r.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    setError("audio stopped (error $n)")
                    break  // start again, as a new segment
                }
                if (n == 0) continue
                val arrived = AndroidClock.uptime()
                // when the buffer's first frame was captured: from AudioRecord's own timestamp
                // (BOOTTIME, the uptime clock), else from when the buffer arrived
                var uptime: Double? = null
                if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    val t = ts.nanoTime / 1e9 + (framesInRecord - ts.framePosition).toDouble() / SAMPLE_RATE
                    if (abs(t - (arrived - n.toDouble() / SAMPLE_RATE)) < 1.0) uptime = t
                }
                synchronized(lock) { if (uptime != null) stamped++ else estimated++ }
                if (uptime == null) uptime = arrived - n.toDouble() / SAMPLE_RATE
                try {
                    segment.encoder.encode(buf, 0, n)
                } catch (e: Exception) {
                    setError("audio write failed: ${e.message}")
                    break
                }
                synchronized(lock) {
                    val index = segments.size - 1
                    timing?.write("$index,${segment.frames},$n,${"%.6f".format(java.util.Locale.US, uptime)}\n")
                    if (segment.frames == 0L) segment.firstUptime = uptime
                    segment.frames += n
                    if (firstBufferLatency == null) firstBufferLatency = arrived - uptime
                    lastBufferUptime = arrived
                    lastError = null
                }
                onBuffer?.invoke(buf, n, uptime)
                framesInRecord += n
            }
            closeSegment(segment)
            try { r.stop() } catch (_: Exception) {}
            r.release()
            record = null
        }
    }

    private fun newSegment(): Segment {
        val name = "audio_%03d.m4a".format(segments.size)
        val writer = M4aWriter(File(dir, name), SAMPLE_RATE, 1, AacEncoder.BIT_RATE_PER_CHANNEL)
        val encoder = AacEncoder(SAMPLE_RATE, 1, { writer.setCodecConfig(it) }, { writer.writeSample(it) })
        val s = Segment(name, SAMPLE_RATE, 1, writer, encoder)
        synchronized(lock) { segments.add(s) }
        return s
    }

    private fun closeSegment(s: Segment) {
        try { s.encoder.finish() } catch (_: Exception) {}
        val priming = AacEncoder.primingFrames(context)
        s.priming = priming
        try { s.writer.close(priming ?: 0, s.frames) } catch (e: Exception) { setError("audio write failed: ${e.message}") }
    }

    private fun sleepWhileRunning(ms: Long) {
        val until = System.currentTimeMillis() + ms
        while (running && System.currentTimeMillis() < until) Thread.sleep(100)
    }

    private fun setError(message: String?) = synchronized(lock) { lastError = message }

    private fun deviceType(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        else -> "type_$type"
    }

    // MARK: for the UI and meta.json

    data class Status(
        val capturing: Boolean = false,  // a buffer arrived within the last second
        val seconds: Double = 0.0,
        val sampleRate: Double = 0.0,
        val segments: Int = 0,
        val error: String? = null,
    )

    val status: Status
        get() = synchronized(lock) {
            Status(
                capturing = AndroidClock.uptime() - lastBufferUptime < 1,
                seconds = segments.sumOf { it.frames.toDouble() / it.sampleRate },
                sampleRate = segments.lastOrNull()?.sampleRate?.toDouble() ?: 0.0,
                segments = segments.size,
                error = lastError,
            )
        }

    val meta: JsonObject
        get() = synchronized(lock) {
            buildJsonObject {
                put("codec", "AAC-LC in MPEG-4 (.m4a)")
                put("bit_rate_per_channel", AacEncoder.BIT_RATE_PER_CHANNEL)
                put("session_mode", source)  // iOS: "measurement"
                put("first_buffer_latency_s", firstBufferLatency?.let(::JsonPrimitive) ?: JsonNull)
                put("segments", JsonArray(segments.map { s ->
                    buildJsonObject {
                        put("file", s.file)
                        put("sample_rate", s.sampleRate.toDouble())
                        put("channels", s.channels)
                        put("frames", s.frames)
                        put("first_frame_uptime_s", s.firstUptime?.let(::JsonPrimitive) ?: JsonNull)
                        // the edit list skips this many decoded frames (the encoder's priming)
                        put("encoder_delay_frames", s.priming?.let(::JsonPrimitive) ?: JsonNull)
                        put("encoder", s.encoder.name)
                    }
                }))
                for ((k, v) in route) put(k, when (v) { null -> JsonNull; is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) })
                put("timestamps", buildJsonObject { put("audio_record_boottime", stamped); put("arrival", estimated) })
                put("silenced", buildJsonArray {
                    silenced.forEach { (a, b) -> add(buildJsonObject { put("from_uptime_s", a); put("to_uptime_s", b?.let(::JsonPrimitive) ?: JsonNull) }) }
                })
            }
        }
}
