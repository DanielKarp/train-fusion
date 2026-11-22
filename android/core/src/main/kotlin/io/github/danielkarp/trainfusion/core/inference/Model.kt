@file:Suppress("PropertyName")  // the JSON's own names

package io.github.danielkarp.trainfusion.core.inference

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/**
 * The fitted model and the track, as exported by phone-sensor-fusion/app/export.py
 * (model.json: the build copies ../ios/TrainRecorder/model.json into the app). Everything
 * that could be precomputed already is: this file only describes the JSON.
 * A port of ios/TrainRecorder/Inference/Model.swift.
 */
@Serializable
class Model(
    val n_stopped: Int,
    val n_v: Int,
    val dv: Double,
    val v: DoubleArray,  // speed of every state
    val mode: IntArray,  // mode of every state (0 moving, 1...5 the stop cycle)
    val prior: DoubleArray,
    val transitions: List<DoubleArray>,  // [from, to, probability]
    val open_chime_state: Int,
    val close_chime_state: Int,
    val chime: Map<String, Chime>,
    val chime_edges: DoubleArray,
    val tone: Comb,
    val wheel: Map<String, Comb?>,
    val level: Map<String, Level?>,
    val level_outlier: Double,
    val level_floor: Double,
    val accel: Accel,
    val phone: Phone,
    val dsp: DSP,
    val track: Track,
    val stations: Map<String, Double>,
) {
    @Serializable class Chime(val log_in: DoubleArray, val log_out: DoubleArray)

    @Serializable
    class Partial(
        val ratio: Double,
        val bin: IntArray,  // per speed state: spectrum bin to read, -1 = off the end
        val band: IntArray,  // per speed state: row of `table`
        val table: List<DoubleArray>,  // [speed band][prominence bin] log-likelihood ratio
        val extra: Int,  // extra max-pooling (bins each side) before the lookup
    )

    @Serializable class Comb(val partials: List<Partial>, val z_edges: DoubleArray)
    @Serializable class Level(val mu: DoubleArray, val sd: Double, val weight: Double)
    @Serializable class Accel(val sd: Double, val outlier: Double, val sd_outlier: Double)

    @Serializable
    class Phone(val mean: DoubleArray, val sd: DoubleArray, val `switch`: Double)

    @Serializable class Bins(val `in`: IntArray, val side: IntArray)

    @Serializable
    class ChimeDSP(
        val nwin: Int, val hop: Int, val db: Float, val min_partials: Int,
        val window: FloatArray, val open: List<Bins>, val close: List<Bins>,
    )

    @Serializable
    class LineDSP(
        val nwin: Int, val nfft: Int, val hop: Int? = null, val bins: Int, val median: Int, val pool: Int,
        val window: FloatArray,
    )

    @Serializable
    class DSP(
        val fs: Int,
        val decimate_taps: FloatArray,
        val chime: ChimeDSP,
        val tone: LineDSP,
        val accel: LineDSP,
        val rumble_sos: List<DoubleArray>,
        val vib_sos: List<List<DoubleArray>>,
    )

    @Serializable
    class Track(
        val stations: List<String>, val station_s: DoubleArray,
        val step: Double, val curvature: DoubleArray, val heading_south: DoubleArray,
        val elev_step: Double, val elevation: DoubleArray,
        val declination: Double,
    )

    val n: Int get() = n_stopped + n_v

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(stream: InputStream): Model = stream.use { json.decodeFromString(serializer(), it.reader().readText()) }
        fun load(file: File): Model = load(file.inputStream())
    }
}

/** What the app shows: the model's belief about one second, using only the past. */
data class Belief(
    var stopLog: List<Stop> = emptyList(),  // oldest first
    /**
     * A door-open chime is believed but not yet counted as a stop (that takes about 3 s):
     * `place`, `stops` and `stopLog` are still about the leg just finished.
     */
    var stopPending: Boolean = false,
    var place: Place? = null,  // null until a door opening has been heard and a station can be named
    var second: Int = 0,  // recording time this belief is about: the latest second that is complete
    var speedMean: Double = 0.0,  // m/s
    var speedSd: Double = 0.0,
    var acceleration: Double? = null,  // m/s^2: how the believed speed has changed over the last 3 s
    var speedHist: FloatArray = FloatArray(0),  // P(speed in [k, k+1) m/s), moving states only
    var pStanding: Double = 0.0,
    var modeP: DoubleArray = DoubleArray(0),  // moving, stopped before doors, door-open chime, doors open, door-close chime, stopped after doors
    var phoneP: DoubleArray = DoubleArray(0),  // resting, held, handled
    var distanceSinceStop: Double = 0.0,  // m, since the last door-open chime (or the start)
    var stops: Int = 0,
    var legs: List<Double> = emptyList(),  // m, distance of each completed leg
    var stations: List<Station> = emptyList(),  // most probable first; empty before the first stop
    var accelInUse: Boolean = false,  // along-track acceleration is being trusted right now
    var accelReading: Double? = null,  // m/s^2 along the track as the accelerometer has it (sign and zero applied), when in use
    /**
     * How strongly the motor tone / the wheel-rotation lines back the believed speed: log
     * likelihood there relative to the average over speeds, averaged over the last few
     * seconds. null = that measurement isn't being read (wheel lines: phone being handled).
     */
    var toneEvidence: Double? = null,
    var wheelEvidence: Double? = null,
) {
    data class Station(val name: String, val southbound: Boolean, val p: Double) {
        val id: String get() = name + if (southbound) "S" else "N"
    }

    /**
     * Where the train is, in the terms a rider thinks in: the station it last stopped at,
     * the next one along, and how far between them it has got.
     */
    data class Place(
        val last: String,
        val next: String?,  // null at the end of the line
        val southbound: Boolean,
        val confidence: Double,  // probability of (last station, direction)
        val progress: Double,  // 0 at `last` ... 1 at `next`
        val metresFromLast: Double,
        val metresToNext: Double?,
        val alternative: String?,  // the runner-up, as "last → next", when it is a real contender
    )

    /** One station stop, by the leading edge of each door chime (seconds into the recording). */
    data class Stop(val opened: Int, var closed: Int? = null, var station: String? = null)
}

val modeNames = listOf("moving", "stopped, before doors", "door-open chime", "doors open", "door-close chime", "stopped, after doors")
val phoneNames = listOf("resting", "held", "handled")
