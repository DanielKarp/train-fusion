package io.github.danielkarp.trainfusion.recording

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.danielkarp.trainfusion.core.inference.Belief
import io.github.danielkarp.trainfusion.core.inference.InferenceEngine
import io.github.danielkarp.trainfusion.core.inference.Model
import io.github.danielkarp.trainfusion.core.inference.Quat
import io.github.danielkarp.trainfusion.core.inference.Vec3
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** What a screen shows: the latest belief. */
class InferenceStore {
    var belief by mutableStateOf<Belief?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    fun publish(beliefs: List<Belief>) {
        beliefs.lastOrNull()?.let { belief = it }
    }

    fun reset() {
        belief = null
        message = null
    }
}

/** model.json, loaded once (a quarter of a megabyte of JSON). */
object SharedModel {
    @Volatile private var result: Result<Model>? = null

    fun get(context: Context): Result<Model> = result ?: synchronized(this) {
        result ?: runCatching { Model.load(context.assets.open("model.json")) }.also { result = it }
    }
}

/**
 * Inference alongside a recording in progress (ios/TrainRecorder/Inference/Drivers.swift's
 * LiveInference). The sensor callbacks only append to buffers under a lock; once a second a
 * low-priority thread drains them, runs the model for whatever seconds are complete, and
 * publishes the newest belief.
 */
class LiveInference(context: Context, private val store: InferenceStore, private val t0: Double) {
    private val lock = Any()
    private var engine: InferenceEngine? = null
    private var executor: ScheduledExecutorService? = null
    private val main = Handler(Looper.getMainLooper())

    private class Chunk(val t: Double?, val x: FloatArray)
    private var audio = ArrayList<Chunk>()
    private var accel = ArrayList<Pair<Double, Vec3>>()
    private var gyro = ArrayList<Pair<Double, Vec3>>()
    private var baro = ArrayList<Pair<Double, Double>>()
    private var attitude = ArrayList<Triple<Double, Quat, Vec3>>()
    private var badAudio = false

    init {
        store.reset()
        SharedModel.get(context)
            .onSuccess { engine = InferenceEngine(it) }
            .onFailure { store.message = "No model: ${it.message}" }
        if (engine != null) {
            executor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "inference")
            }.also { it.scheduleWithFixedDelay({ pump() }, 1, 1, TimeUnit.SECONDS) }
        }
    }

    fun stop() {
        executor?.shutdownNow()
        executor = null
    }

    // MARK: from the capture threads

    fun audio(x: FloatArray, count: Int, uptime: Double?, sampleRate: Int = AudioCapture.SAMPLE_RATE) {
        if (sampleRate != 48000) { synchronized(lock) { badAudio = true }; return }
        val copy = x.copyOf(count)
        synchronized(lock) { audio.add(Chunk(uptime?.let { it - t0 }, copy)) }
    }

    fun accel(t: Double, x: Double, y: Double, z: Double) = synchronized(lock) { accel.add(t - t0 to Vec3(x, y, z)); Unit }
    fun gyro(t: Double, x: Double, y: Double, z: Double) = synchronized(lock) { gyro.add(t - t0 to Vec3(x, y, z)); Unit }
    fun baro(t: Double, kPa: Double) = synchronized(lock) { baro.add(t - t0 to kPa); Unit }
    fun attitude(t: Double, q: Quat, gravity: Vec3) = synchronized(lock) { attitude.add(Triple(t - t0, q, gravity)); Unit }

    // MARK: on the inference thread

    private fun pump() {
        val engine = engine ?: return
        val a: List<Chunk>; val ac: List<Pair<Double, Vec3>>; val gy: List<Pair<Double, Vec3>>
        val ba: List<Pair<Double, Double>>; val at: List<Triple<Double, Quat, Vec3>>; val bad: Boolean
        synchronized(lock) {
            a = audio; ac = accel; gy = gyro; ba = baro; at = attitude; bad = badAudio
            audio = ArrayList(); accel = ArrayList(); gyro = ArrayList(); baro = ArrayList(); attitude = ArrayList()
        }
        try {
            val f = engine.features
            for (chunk in a) f.addAudio48k(chunk.x, 0, chunk.x.size, chunk.t)
            for (s in ac) f.addAccel(s.first, s.second)
            for (s in gy) f.addGyro(s.first, s.second)
            for (s in ba) f.addBaro(s.first, s.second)
            for (s in at) f.addAttitude(s.first, s.second, s.third)
            val beliefs = engine.process()
            if (beliefs.isNotEmpty() || bad) {
                main.post {
                    store.publish(beliefs)
                    if (bad) store.message = "Inference needs 48 kHz audio from the phone's own microphone."
                }
            }
        } catch (e: Exception) {
            main.post { store.message = "The model stopped: ${e.message}" }
            stop()
        }
    }
}
