package io.github.danielkarp.trainfusion.recording

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.danielkarp.trainfusion.BuildConfig
import io.github.danielkarp.trainfusion.TrainFusionApp
import io.github.danielkarp.trainfusion.core.recording.Ntp
import io.github.danielkarp.trainfusion.core.recording.NtpSample
import io.github.danielkarp.trainfusion.core.recording.RecordingID
import io.github.danielkarp.trainfusion.core.recording.RideInfo
import io.github.danielkarp.trainfusion.platform.AndroidClock
import io.github.danielkarp.trainfusion.platform.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Date

/**
 * One recording at a time: audio + motion sensors + barometer + clock log, into
 * recordings/<id>/ (ios/TrainRecorder/Recorder.swift; docs/recorder.md describes the files).
 * Its state is read by the screens; everything here runs on the main thread.
 */
class Recorder(private val app: TrainFusionApp) {
    data class Live(
        val audio: AudioCapture.Status = AudioCapture.Status(),
        val accelSamples: Int = 0,
        val gyroSamples: Int = 0,
        val magSamples: Int = 0,
        val baroSamples: Int = 0,
        val fusedSamples: Int = 0,
        val ntp: NtpSample? = null,
    )

    var activeID by mutableStateOf<String?>(null)
        private set
    /** When the recording started, unix milliseconds. */
    var startedAt by mutableStateOf<Long?>(null)
        private set
    var live by mutableStateOf(Live())
        private set
    var busy by mutableStateOf(false)  // starting or stopping
        private set
    var starting by mutableStateOf(false)  // starting the sensors
        private set
    var error by mutableStateOf<String?>(null)
    /** Trip and car of the recording in progress (or, typed in before starting, of the next one). Set with `setRide`. */
    var ride by mutableStateOf<RideInfo?>(null)
        private set
    /** What the model believes about the recording in progress. */
    val inference = InferenceStore()

    private var dir: File? = null
    private var started = 0.0 to 0.0  // uptime, unix
    private var audio: AudioCapture? = null
    private var motion: MotionCapture? = null
    private var clock: ClockLog? = null
    private var poll: Job? = null
    private var liveInference: LiveInference? = null

    val isRecording: Boolean get() = activeID != null

    suspend fun start() {
        if (isRecording || busy) return
        busy = true
        starting = true
        error = null
        try {
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                error = "Microphone access is off. Turn it on in Settings › Apps › Train Fusion › Permissions."
                return
            }
            val now = AndroidClock.now()
            val id = RecordingID.make(Date((now.second * 1000).toLong()))
            val dir = File(Storage.recordings(app), id)
            try {
                dir.mkdirs()
                // keeps the app running with the screen off, and says so in a notification
                RecordingService.start(app, (now.second * 1000).toLong())
                val clock = ClockLog(dir)
                val motion = MotionCapture(app, dir)
                val audio = AudioCapture(app, dir)
                // the model runs alongside, fed from the same callbacks that write the files
                val inference = LiveInference(app, inference, now.first)
                audio.onBuffer = { x, n, t -> inference.audio(x, n, t) }
                motion.inference = inference
                try {
                    // off the main thread: starting the audio hardware can take a moment
                    withContext(Dispatchers.IO) { audio.start() }
                } catch (e: Exception) {
                    withContext(Dispatchers.IO) { clock.stop(finalNTP = false); motion.stop() }
                    inference.stop()
                    throw e
                }
                motion.start()
                this.clock = clock; this.motion = motion; this.audio = audio; this.liveInference = inference
            } catch (e: Exception) {
                dir.deleteRecursively()
                RecordingService.stop(app)
                error = "Couldn't start: ${e.message ?: e.javaClass.simpleName}"
                return
            }
            this.dir = dir
            started = now
            startedAt = (now.second * 1000).toLong()
            activeID = id
            live = Live()
            writeMeta(ended = null)  // so a recording that never gets stopped is still described
            poll = app.scope.launch {
                while (true) {
                    delay(1000)
                    refreshLive()
                }
            }
        } finally {
            busy = false
            starting = false
        }
    }

    @JvmName("changeRide")  // (the property's own setter is setRide)
    fun setRide(ride: RideInfo?) {
        this.ride = ride
        writeMeta(ended = null)  // nothing happens if no recording is in progress yet
    }

    suspend fun stop() {
        if (!isRecording || busy) return
        busy = true
        try {
            val (audio, motion, clock) = Triple(audio, motion, clock)
            withContext(Dispatchers.IO) {
                audio?.stop()
                motion?.stop()
                clock?.stop(finalNTP = true)
            }
            finish()
        } finally {
            busy = false
        }
    }

    private fun finish() {
        val id = activeID ?: return
        writeMeta(ended = AndroidClock.now())
        poll?.cancel()
        liveInference?.stop()
        poll = null; audio = null; motion = null; clock = null; dir = null; liveInference = null
        activeID = null
        startedAt = null
        ride = null  // the next recording is another train
        RecordingService.stop(app)
        app.recordingEnded(id)
    }

    private fun refreshLive() {
        val audio = audio ?: return
        val motion = motion ?: return
        live = Live(audio.status, motion.accelCount, motion.gyroCount, motion.magCount, motion.baroCount, motion.fusedCount, clock?.latestNTP)
    }

    /**
     * What the model believed when the recording was stopped: the stops it heard, when the
     * doors opened and closed at each (the start of each chime), and its best guess at the
     * stations. Null if it never ran.
     */
    private fun inferred(): JsonElement {
        val belief = inference.belief ?: return JsonNull
        val best = belief.stations.firstOrNull()
        return buildJsonObject {
            put("direction", best?.let { JsonPrimitive(if (it.southbound) "southbound" else "northbound") } ?: JsonNull)
            put("station_confidence", best?.let { JsonPrimitive(it.p) } ?: JsonNull)  // of the last stop's station and the direction
            put("stops", buildJsonArray {
                belief.stopLog.forEach { stop ->
                    add(buildJsonObject {
                        put("station", stop.station?.let(::JsonPrimitive) ?: JsonNull)
                        put("doors_opened_uptime_s", started.first + stop.opened)
                        put("doors_closed_uptime_s", stop.closed?.let { JsonPrimitive(started.first + it) } ?: JsonNull)
                    })
                }
            })
        }
    }

    private fun writeMeta(ended: Pair<Double, Double>?) {
        val dir = dir ?: return
        val id = activeID ?: return
        val fields = sortedMapOf<String, JsonElement>(
            "format_version" to JsonPrimitive(1),
            "id" to JsonPrimitive(id),
            "complete" to JsonPrimitive(ended != null),
            "started_uptime_s" to JsonPrimitive(started.first),
            "started_unix_s" to JsonPrimitive(started.second),
            "ended_uptime_s" to (ended?.let { JsonPrimitive(it.first) } ?: JsonNull),
            "ended_unix_s" to (ended?.let { JsonPrimitive(it.second) } ?: JsonNull),
            // iOS has device_model ("iPhone15,2") and ios_version; Android says what it is instead
            "platform" to JsonPrimitive("android"),
            "device_model" to JsonPrimitive(Build.MODEL),
            "device_manufacturer" to JsonPrimitive(Build.MANUFACTURER),
            "device" to JsonPrimitive(Build.DEVICE),
            "android_version" to JsonPrimitive(Build.VERSION.RELEASE),
            "android_sdk" to JsonPrimitive(Build.VERSION.SDK_INT),
            "app_version" to JsonPrimitive(appVersion),
            "ntp_server" to JsonPrimitive(Ntp.SERVER),
            "audio" to (audio?.meta ?: JsonObject(emptyMap())),
            "motion" to (motion?.meta ?: JsonObject(emptyMap())),
            "inferred" to (if (ended == null) JsonNull else inferred()),
            // typed in by the rider; null if they didn't
            "ride" to (ride?.let { buildJsonObject { put("trip_number", it.trip); put("car_number", it.car); put("car_position", it.position.raw) } } ?: JsonNull),
        )
        val text = pretty.encodeToString(JsonElement.serializer(), JsonObject(fields))
        try {
            val tmp = File(dir, ".meta.json.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, "meta.json"))
        } catch (_: Exception) {
        }
    }

    companion object {
        val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        private val pretty = Json { prettyPrint = true }
    }
}
