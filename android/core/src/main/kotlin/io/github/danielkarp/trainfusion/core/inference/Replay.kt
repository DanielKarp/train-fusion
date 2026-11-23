package io.github.danielkarp.trainfusion.core.inference

import io.github.danielkarp.trainfusion.core.recording.GzipCsv
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.File
import java.io.OutputStream

/** A recording's audio as 48 kHz mono floats, frame 0 first (priming already dropped). */
interface AudioReader : Closeable {
    val sampleRate: Int

    /** Up to `frames` frames into `buf`; 0 at the end. */
    fun read(buf: FloatArray, frames: Int): Int
}

/**
 * What the replay loop reads as it goes, so that pausing, changing speed and skipping
 * take effect straight away.
 */
class ReplayControl {
    enum class Command { NEXT_STOP, PREVIOUS_STOP, RESTART }

    @Volatile var paused = false

    /** Recording seconds per second; null = as fast as it goes. */
    @Volatile var speed: Double? = 16.0

    private var command: Command? = null

    @Synchronized fun send(c: Command) { command = c }
    val hasCommand: Boolean @Synchronized get() = command != null
    @Synchronized fun takeCommand(): Command? = command.also { command = null }
}

/** Plays an existing recording through the engine (ios/TrainRecorder/Inference/Drivers.swift, `Replay`). */
object Replay {
    class Failure(message: String) : Exception(message)

    data class Update(
        val beliefs: List<Belief>,
        val second: Int,
        val restarted: Boolean = false,  // the beliefs start over from the beginning of the recording
        val atEnd: Boolean = false,
    )

    private sealed interface Target {
        data class Stops(val k: Int) : Target
        data class Second(val t: Int) : Target
    }

    /** What replay needs from meta.json. */
    class Meta(val t0: Double, val audioFile: String, val audioStart: Double, val duration: Double)

    fun meta(dir: File): Meta {
        val meta = try {
            Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
        } catch (e: Exception) {
            throw Failure("This recording's meta.json is incomplete.")
        }
        val t0 = meta["started_uptime_s"]?.jsonPrimitive?.doubleOrNull
        val segment = (meta["audio"] as? JsonObject)?.get("segments")?.let { it as? JsonArray }?.firstOrNull() as? JsonObject
        val name = segment?.get("file")?.jsonPrimitive?.contentOrNullSafe()
        val audioStart = segment?.get("first_frame_uptime_s")?.jsonPrimitive?.doubleOrNull
        if (t0 == null || name == null || audioStart == null) throw Failure("This recording's meta.json is incomplete.")
        val ended = meta["ended_uptime_s"]?.jsonPrimitive?.doubleOrNull ?: t0
        return Meta(t0, name, audioStart, ended - t0)
    }

    /** Whether the recording in `dir` is the generated sample ride (its meta.json says so). */
    fun isSample(dir: File): Boolean = try {
        (Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject["sample"] as? JsonPrimitive)?.content == "true"
    } catch (_: Exception) {
        false
    }

    /** What to replay `dir` from: the sample ride's generator, or the recording's files (audio opened with `openAudio`). */
    fun source(dir: File, model: Model, openAudio: (File) -> AudioReader): ReplaySource =
        if (isSample(dir)) SampleRide(model) else FileSource(dir, openAudio)

    /**
     * Runs until cancelled: plays to the end at the control's speed, obeys its commands,
     * and at the end waits for more. The model only runs forwards, so going back means
     * starting over and running flat out to the place (a whole ride takes a few seconds).
     */
    suspend fun run(
        source: ReplaySource,
        model: Model,
        control: ReplayControl,
        dump: OutputStream?,
        report: suspend (Update) -> Unit,
    ) {
        try {
            val duration = source.duration
            var engine = InferenceEngine(model)
            var second = 0
            val unsent = ArrayList<Belief>()
            var restarted = false
            var stops = 0  // heard so far in this pass
            val stopTimes = ArrayList<Int>()  // when each was heard, over all passes
            var target: Target? = null  // running flat out towards this

            fun restart() {
                engine = InferenceEngine(model)
                source.rewind()
                second = 0; stops = 0
                unsent.clear()
                restarted = true
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                when (control.takeCommand()) {
                    ReplayControl.Command.NEXT_STOP -> target = Target.Stops(stops + 1)
                    ReplayControl.Command.PREVIOUS_STOP -> {
                        val t = stopTimes.lastOrNull { it < second - 5 } ?: 0
                        restart()
                        target = Target.Second(t)
                    }
                    ReplayControl.Command.RESTART -> { restart(); target = null }
                    null -> {}
                }
                if (second >= duration) {  // the end: say so, and wait for a command
                    target = null
                    report(Update(unsent.toList(), second, restarted, atEnd = true))
                    unsent.clear(); restarted = false
                    while (!control.hasCommand) {
                        currentCoroutineContext().ensureActive()
                        delay(100)
                    }
                    continue
                }
                val tick = System.nanoTime()
                source.feed(second, engine.features)
                second += 1

                while (true) {
                    val b = engine.processNext() ?: break
                    unsent.add(b)
                    if (b.stops > stops) {
                        stops = b.stops
                        if (b.second > (stopTimes.lastOrNull() ?: -1)) stopTimes.add(b.second)
                    }
                    if (dump != null) engine.lastFrame?.let { dump.write(dumpLine(b, it, engine.lastAccel, engine.lastAccelSpec)) }
                }
                val arrived = when (val t = target) {
                    is Target.Stops -> stops >= t.k
                    is Target.Second -> second >= t.t
                    null -> false
                }
                if (arrived) target = null
                val speed = control.speed
                // the screen needs no more than a few updates a second, however fast this runs
                if (arrived || (target == null && speed != null) || second % 20 == 0) {
                    report(Update(unsent.toList(), second, restarted))
                    unsent.clear(); restarted = false
                }
                if (target == null) {
                    if (speed != null) {
                        val waitMs = (1000 / speed - (System.nanoTime() - tick) / 1e6).toLong()
                        if (waitMs > 0) delay(waitMs)
                    }
                    while (control.paused && !control.hasCommand) {
                        currentCoroutineContext().ensureActive()
                        delay(100)
                    }
                }
            }
        } finally {
            source.close()
            dump?.flush()
        }
    }

    /** One line of the replay dump (`python -m app.compare` reads it). */
    fun dumpLine(b: Belief, f: Frame, accel: InferenceEngine.Accel?, late: Pair<Int, FloatArray>?): ByteArray {
        val o = buildJsonObject {
            put("n", b.second); put("v", b.speedMean); put("v_sd", b.speedSd)
            put("mode", nums(b.modeP)); put("phone", nums(b.phoneP))
            put("dist", b.distanceSinceStop); put("stops", b.stops)
            put("legs", buildJsonArray { b.legs.forEach { add(JsonPrimitive(it)) } })
            put("stations", buildJsonArray {
                b.stations.forEach { add(buildJsonObject { put("name", it.name); put("south", it.southbound); put("p", it.p) }) }
            })
            put("stop_log", buildJsonArray {
                b.stopLog.forEach {
                    add(buildJsonObject {
                        put("opened", it.opened)
                        put("closed", it.closed?.let(::JsonPrimitive) ?: JsonNull)
                        put("station", it.station?.let(::JsonPrimitive) ?: JsonNull)
                    })
                }
            })
            put("open_chime", f.openChime); put("close_chime", f.closeChime); put("rumble_db", f.rumbleDb)
            put("vib_low", f.vibDb[0]); put("vib_high", f.vibDb[1]); put("gyro_rms", f.gyroRms)
            put("acc_mean", buildJsonArray { add(JsonPrimitive(f.accMean.x)); add(JsonPrimitive(f.accMean.y)); add(JsonPrimitive(f.accMean.z)) })
            put("tone_spec", buildJsonArray { intArrayOf(150, 300, 475, 700).forEach { add(JsonPrimitive(f.toneSpec[it])) } })
            // the accelerometer spectrum is for an earlier second than the rest of the line
            if (late != null) {
                put("accel_n", late.first)
                put("accel_spec", buildJsonArray { intArrayOf(100, 250, 500).forEach { add(JsonPrimitive(late.second[it])) } })
            }
            f.baroAlt?.let { put("baro_alt", it) }
            b.toneEvidence?.let { put("tone_ev", it) }
            b.wheelEvidence?.let { put("wheel_ev", it) }
            if (accel != null) { put("a_long", accel.a); put("a_sign", accel.sign); put("a_offset", accel.offset) }
        }
        return (o.toString() + "\n").toByteArray()
    }

    private fun nums(a: DoubleArray) = buildJsonArray { a.forEach { add(JsonPrimitive(it)) } }
}

/** Where a replay's sensor streams come from: a recording's files, or the sample ride. */
interface ReplaySource : java.io.Closeable {
    /** Seconds. */
    val duration: Double

    /** Feed one second of every stream (seconds are asked for in order from 0, and again from 0 after `rewind`). */
    fun feed(second: Int, f: FeatureExtractor)
    fun rewind()
    override fun close() {}
}

/** A recording's files: its (first) audio segment, opened with `openAudio`, and the sensors' .csv.gz. */
class FileSource(private val dir: File, private val openAudio: (File) -> AudioReader) : ReplaySource {
    private val meta = Replay.meta(dir)
    override val duration = meta.duration
    private val t0 = meta.t0
    private val accel = GzipCsv.read(File(dir, "accel.csv.gz"), intArrayOf(0, 1, 2, 3))
    private val gyro = GzipCsv.read(File(dir, "gyro.csv.gz"), intArrayOf(0, 1, 2, 3))
    private val baro = GzipCsv.read(File(dir, "baro.csv.gz"), intArrayOf(0, 1))
    private val fused = GzipCsv.read(File(dir, "device_motion.csv.gz"), intArrayOf(0, 1, 2, 3, 4, 8, 9, 10))
    private var audio = openAudio(File(dir, meta.audioFile))
    private var ia = 0; private var ig = 0; private var ib = 0; private var ifu = 0
    private val buffer = FloatArray(48000)
    private val chunk = FloatArray(48000)

    init {
        if (audio.sampleRate != 48000) {
            audio.close()
            throw Replay.Failure("Replay needs 48 kHz audio.")
        }
    }

    override fun rewind() {
        audio.close()
        audio = openAudio(File(dir, meta.audioFile))
        ia = 0; ig = 0; ib = 0; ifu = 0
    }

    override fun feed(second: Int, f: FeatureExtractor) {
        var got = 0
        while (got < 48000) {
            val k = audio.read(chunk, 48000 - got)
            if (k <= 0) break
            System.arraycopy(chunk, 0, buffer, got, k)
            got += k
        }
        if (got > 0) f.addAudio48k(buffer, 0, got, if (second == 0) meta.audioStart - t0 else null)
        val until = t0 + second + 1
        while (ia < accel.size / 4 && accel[4 * ia] < until) {
            f.addAccel(accel[4 * ia] - t0, Vec3(accel[4 * ia + 1], accel[4 * ia + 2], accel[4 * ia + 3])); ia += 1
        }
        while (ig < gyro.size / 4 && gyro[4 * ig] < until) {
            f.addGyro(gyro[4 * ig] - t0, Vec3(gyro[4 * ig + 1], gyro[4 * ig + 2], gyro[4 * ig + 3])); ig += 1
        }
        while (ib < baro.size / 2 && baro[2 * ib] < until) { f.addBaro(baro[2 * ib] - t0, baro[2 * ib + 1]); ib += 1 }
        while (ifu < fused.size / 8 && fused[8 * ifu] < until) {
            val r = 8 * ifu
            f.addAttitude(fused[r] - t0, Quat(fused[r + 1], fused[r + 2], fused[r + 3], fused[r + 4]),
                Vec3(fused[r + 5], fused[r + 6], fused[r + 7]))
            ifu += 1
        }
    }

    override fun close() = audio.close()
}

private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content
