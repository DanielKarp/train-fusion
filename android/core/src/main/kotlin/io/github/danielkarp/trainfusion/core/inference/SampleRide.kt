package io.github.danielkarp.trainfusion.core.inference

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A made-up ride from San Francisco 4th & King to Palo Alto, stopping everywhere but
 * Broadway, generated second by second as it is played back, so the real feature extraction
 * and filter run on it as on a recording. For trying the app without a train (and for the
 * store's reviewers). A port of ios/TrainRecorder/Inference/SampleRide.swift
 * (docs/recorder.md, "The sample ride").
 *
 * The only hard-coded data is the stop list below and how long the train stands at each.
 * Everything else comes from simple models plus noise:
 *  - motion: as fast as the speed limits allow, with an electric train's power-limited
 *    acceleration and service braking. The limits: the line's 79 mph, curves (from the
 *    map's curvature, at the sideways acceleration a canted curve allows) and the slow
 *    approach to 4th & King. Positions are the map's (`model.track`);
 *  - audio: a noise floor, rolling rumble that grows with speed, the traction motor's tone
 *    (two partials proportional to speed) and the door chimes (their partials);
 *  - accelerometer: gravity, the along-track acceleration and some of the centripetal one in
 *    a phone lying flat at an angle to the track, wheel-rotation lines and vibration that
 *    grow with speed;
 *  - gyro: the train's turning (speed × the track's curvature) plus noise and bias;
 *  - attitude: the track's heading; barometer: the track's elevation.
 * Same seed, same ride: replaying it from the start gives the same data.
 */
class SampleRide(model: Model) : ReplaySource {
    /** A stop: the station, and when (s into the sample) the doors open and close. */
    data class Stop(val station: String, val opened: Double, val closed: Double)

    private class Motion(val s: Double, val v: Double, val a: Double)

    companion object {
        /** Which station, and how long the train stands there (s; the last one: until the sample ends). */
        private val legs = listOf(
            "22nd Street" to 40.0,
            "Bayshore" to 35.0,
            "South San Francisco" to 45.0,
            "San Bruno" to 35.0,
            "Millbrae" to 55.0,
            "Burlingame" to 40.0,
            "San Mateo" to 45.0,
            "Hayward Park" to 35.0,
            "Hillsdale" to 45.0,
            "Belmont" to 35.0,
            "San Carlos" to 40.0,
            "Redwood City" to 60.0,
            "Menlo Park" to 40.0,
            "Palo Alto" to 25.0,
        )
        private const val ORIGIN = "San Francisco 4th & King Street"
        private const val ORIGIN_OPEN = 6.0  // s into the sample: the doors open at the origin
        private const val ORIGIN_DWELL = 50.0
        private const val SEED = 0x5EEDCA17L

        // a few physical constants of the made-up train, track and phone
        private const val MAX_ACCEL = 0.9  // m/s^2 (an electric multiple unit's starting acceleration)
        private const val POWER = 16.0  // m^2/s^3: acceleration x speed at most (power per mass)
        private const val BRAKING = 0.8  // m/s^2, service braking
        private const val LINE_SPEED = 35.3  // m/s: 79 mph, the line's maximum
        private const val CURVE_ACCEL = 1.3  // m/s^2 sideways that a canted curve allows at its limit
        private const val TERMINAL_LENGTH = 500.0  // m past 4th & King...
        private const val TERMINAL_SPEED = 11.2  // ... at 25 mph
        private const val PHONE_YAW = 25.0  // degrees between the phone's x axis and the train's forward
        private const val GRID_STEP = 0.1  // s, of the precomputed motion

        private val OPEN_PARTIALS = doubleArrayOf(2203.0, 4398.0, 6602.0)  // Hz, inside the model's bands
        private val CLOSE_PARTIALS = doubleArrayOf(3516.0, 5281.0, 7039.0)
        private const val CHIME_LENGTH = 2.6  // s

        /**
         * The speed limit at map position x (m): the line's, a curve's (the sharpest within
         * 100 m, about a train's length), or the terminal's.
         */
        private fun limit(x: Double, track: Model.Track, origin: Double): Double {
            val c = track.curvature
            val lo = max(((x - 100) / track.step).toInt(), 0)
            val hi = min(((x + 100) / track.step).toInt(), c.size - 1)
            var sharpest = 1e-9
            if (lo <= hi) for (k in lo..hi) sharpest = max(sharpest, abs(c[k]))
            var v = min(LINE_SPEED, sqrt(CURVE_ACCEL / sharpest))
            if (x - origin < TERMINAL_LENGTH) v = min(v, TERMINAL_SPEED)
            return v
        }

        /**
         * Speed and acceleration every grid step of a leg from map position `a` to a stop at
         * `b`: as fast as the limits allow, braking in time for lower limits ahead and the
         * stop (the shortest-time profile: the lower of what acceleration can reach going
         * forwards and what braking allows going backwards).
         */
        private fun drive(a: Double, b: Double, track: Model.Track, origin: Double): List<Pair<Double, Double>> {
            val ds = 2.0
            val n = max(roundHalfAway((b - a) / ds).toInt(), 1)
            val envelope = DoubleArray(n + 1) { limit(a + it * ds, track, origin) }
            envelope[n] = 0.0
            for (i in n - 1 downTo 0) envelope[i] = min(envelope[i], sqrt(envelope[i + 1] * envelope[i + 1] + 2 * BRAKING * ds))
            val speed = DoubleArray(n + 1)
            for (i in 0 until n) {
                val push = min(MAX_ACCEL, POWER / max(speed[i], 0.1))
                speed[i + 1] = min(envelope[i + 1], sqrt(speed[i] * speed[i] + 2 * push * ds))
            }
            // to time: speed every grid step, then the acceleration, smoothed over a second
            // (traction and brakes don't change instantly)
            val out = ArrayList<Double>()
            var t = 0.0
            var i = 0
            var at = 0.0  // time at distance step i
            while (i < n) {
                val dt = 2 * ds / max(speed[i] + speed[i + 1], 0.01)
                while (t < at + dt) {
                    val w = (t - at) / dt
                    out.add(speed[i] + (speed[i + 1] - speed[i]) * w)
                    t += GRID_STEP
                }
                at += dt
                i += 1
            }
            out.add(0.0)
            val raw = DoubleArray(out.size)
            for (k in 1 until out.size) raw[k] = (out[k] - out[k - 1]) / GRID_STEP
            val half = (0.5 / GRID_STEP).toInt()
            return out.indices.map { k ->
                val lo = max(k - half, 0)
                val hi = min(k + half, raw.size - 1)
                var sum = 0.0
                for (j in lo..hi) sum += raw[j]
                out[k] to sum / (hi - lo + 1)
            }
        }
    }

    override val duration: Double
    /** The stops: station and when (s into the sample) the doors open and close. */
    val stops: List<Stop>

    private val track = model.track
    private val s: DoubleArray
    private val v: DoubleArray
    private val a: DoubleArray  // position (m on the map), speed, acceleration on the grid
    private val openChimes: List<Double>
    private val closeChimes: List<Double>

    // generator state (reset by rewind)
    private var rng = Random(SEED)
    private var rumbleFilter = Biquad.bandpass(40.0, 0.9, 16000.0)
    private var tonePhase = doubleArrayOf(0.0, 0.0)
    private var wheelPhase = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    private var baroDrift = 0.0
    private val rumbleGain: Double  // output rms of the rumble filter for unit white noise

    init {
        fun at(name: String) = track.station_s[track.stations.indexOf(name).let { if (it < 0) 0 else it }]

        // the motion, leg by leg, on the grid
        val gs = ArrayList<Double>()
        val gv = ArrayList<Double>()
        val ga = ArrayList<Double>()
        var t = 0.0
        var sNow = at(ORIGIN)
        fun stand(until: Double) {
            while (t < until) { gs.add(sNow); gv.add(0.0); ga.add(0.0); t += GRID_STEP }
        }
        val departure = ORIGIN_OPEN + ORIGIN_DWELL
        val stops = arrayListOf(Stop(ORIGIN, ORIGIN_OPEN, departure - 9))
        stand(departure)
        for ((station, dwell) in legs) {
            val target = at(station)
            for ((vv, aa) in drive(sNow, target, track, at(ORIGIN))) {
                sNow += vv * GRID_STEP
                gs.add(sNow); gv.add(vv); ga.add(aa)
                t += GRID_STEP
            }
            sNow = target
            val leaves = t + dwell
            stops.add(Stop(station, t + 2, leaves - 9))
            stand(leaves)
        }

        val probe = Biquad.bandpass(40.0, 0.9, 16000.0)
        val r = Random(1)
        var sum = 0.0
        repeat(32000) { val y = probe.step(r.gaussian()); sum += y * y }

        s = gs.toDoubleArray(); v = gv.toDoubleArray(); a = ga.toDoubleArray()
        this.stops = stops
        duration = floor(t)
        openChimes = stops.map { it.opened }
        closeChimes = stops.dropLast(1).map { it.closed }  // the sample ends with the doors open
        rumbleGain = sqrt(sum / 32000)
    }

    override fun rewind() {
        rng = Random(SEED)
        rumbleFilter = Biquad.bandpass(40.0, 0.9, 16000.0)
        tonePhase = doubleArrayOf(0.0, 0.0)
        wheelPhase = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        baroDrift = 0.0
    }

    // MARK: one second of every stream

    override fun feed(second: Int, f: FeatureExtractor) {
        val t0 = second.toDouble()
        audio(t0, f)
        imu(t0, f)
    }

    /** Motion at time t (s into the sample), interpolated on the grid. */
    private fun motion(t: Double): Motion {
        val x = min(max(t / GRID_STEP, 0.0), (s.size - 1).toDouble())
        val i = min(x.toInt(), s.size - 2)
        val w = x - i
        return Motion(s[i] + (s[i + 1] - s[i]) * w, v[i] + (v[i + 1] - v[i]) * w, a[i] + (a[i + 1] - a[i]) * w)
    }

    private fun onTrack(table: DoubleArray, x: Double, step: Double): Double {
        val p = min(max(x / step, 0.0), (table.size - 1).toDouble())
        val i = min(p.toInt(), table.size - 2)
        return table[i] + (table[i + 1] - table[i]) * (p - i)
    }

    private fun audio(t0: Double, f: FeatureExtractor) {
        val fs = 16000
        val noise = 0.01  // rms of the broadband noise floor
        val x = FloatArray(fs)
        val m0 = motion(t0)
        val m1 = motion(t0 + 1)
        // rolling rumble, dB in 20..70 Hz: quiet standing, louder with speed
        val rumbleDb = -49 + 11 * tanh(max(m0.v, m1.v) / 15)
        val rumbleScale = 10.0.pow(rumbleDb / 20) / rumbleGain
        // the traction motor's whine: stronger while pulling than while braking
        fun pull(m: Motion) = if (m.v < 0.5) 0.0 else min(m.v / 3, 1.0) * (if (m.a < -0.1) 0.5 else 1.0)
        val tone0 = pull(m0)
        val tone1 = pull(m1)
        val ratios = doubleArrayOf(26.41, 58.12)
        val toneAmp = doubleArrayOf(0.3 * noise, 0.18 * noise)
        val (openStart, closeStart) = chimeEnvelope(t0)
        for (k in 0 until fs) {
            val u = k.toDouble() / fs
            val vv = m0.v + (m1.v - m0.v) * u
            var y = noise * rng.gaussian() + rumbleScale * rumbleFilter.step(rng.gaussian())
            val tone = tone0 + (tone1 - tone0) * u
            if (tone > 0) {
                for (p in 0..1) {
                    tonePhase[p] += 2 * PI * ratios[p] * vv / fs
                    y += tone * toneAmp[p] * sin(tonePhase[p])
                }
            }
            x[k] = y.toFloat()
        }
        openStart?.let { addChime(x, OPEN_PARTIALS, it, 3 * noise) }
        closeStart?.let { addChime(x, CLOSE_PARTIALS, it, 3 * noise) }
        f.addAudio16k(x)
    }

    /** When, relative to the second starting at `t0`, a door chime sounding in it started. */
    private fun chimeEnvelope(t0: Double): Pair<Double?, Double?> {
        fun find(times: List<Double>) = times.firstOrNull { it < t0 + 1 && it + CHIME_LENGTH > t0 }?.let { it - t0 }
        return find(openChimes) to find(closeChimes)
    }

    /** Two "dings", the second a little softer, each decaying, with these partials. */
    private fun addChime(x: FloatArray, partials: DoubleArray, start: Double, amplitude: Double) {
        val fs = x.size.toDouble()
        for (k in x.indices) {
            val tc = k / fs - start  // s into the chime
            if (tc < 0 || tc >= CHIME_LENGTH) continue
            val ding = if (tc < 1.3) exp(-tc * 0.5) else 0.8 * exp(-(tc - 1.3) * 0.5)
            var y = 0.0
            for ((i, p) in partials.withIndex()) y += sin(2 * PI * p * tc) * (if (i == 0) 1.0 else 0.8)
            x[k] += (amplitude * ding * y).toFloat()
        }
    }

    private fun imu(t0: Double, f: FeatureExtractor) {
        val phi = PHONE_YAW * PI / 180
        val forward = Vec3(cos(phi), sin(phi), 0.0)  // the train's forward, in phone axes
        val left = Vec3(-sin(phi), cos(phi), 0.0)
        val up = Vec3(0.0, 0.0, 1.0)
        val gyroBias = Vec3(0.002, -0.001, 0.0015)
        val wheelRatios = doubleArrayOf(0.2639, 0.3611, 0.7013, 1.0481)  // Hz per m/s
        val wheelAmp = doubleArrayOf(0.45, 0.25, 0.3, 0.25)  // x the vibration's rms
        for (k in 0 until 100) {
            val t = t0 + k / 100.0
            val m = motion(t)
            val curvature = onTrack(track.curvature, m.s, track.step)  // rad/m, + turning left
            // vertical vibration (g): its 10..45 Hz level in dB grows with speed
            val highDb = -58 + 24 * tanh(m.v / 18)
            val vib = sqrt(10.0.pow(highDb / 10) / 0.7)
            var bump = vib * rng.gaussian()
            if (m.v > 1) {
                for (i in 0..3) {
                    wheelPhase[i] += 2 * PI * wheelRatios[i] * m.v / 100
                    bump += wheelAmp[i] * vib * sin(wheelPhase[i])
                }
            }
            // specific force (m/s^2): the train's acceleration, a third of the centripetal (the
            // rest is taken by the track's cant), and gravity's reaction; iOS reports its
            // negative in g
            val force = forward * m.a + left * (0.3 * m.v * m.v * curvature) + up * 9.81
            val nx = 0.002 * rng.gaussian()
            val ny = 0.002 * rng.gaussian()
            f.addAccel(t, -force / 9.81 + Vec3(nx, ny, bump))
            val yaw = m.v * curvature
            val gx = rng.gaussian()
            val gy = rng.gaussian()
            val gz = rng.gaussian()
            f.addGyro(t, up * yaw + gyroBias + Vec3(gx, gy, gz) * 0.0049)
            if (k % 10 == 0) {
                // phone -> world (x magnetic north, y west, z up): the phone's x axis points the
                // train's heading turned by the phone's angle, less the declination
                val heading = onTrack(track.heading_south, m.s, track.step) + PHONE_YAW - track.declination
                val half = -heading * PI / 180 / 2
                f.addAttitude(t, Quat(0.0, 0.0, sin(half), cos(half)), Vec3(0.0, 0.0, -1.0))
            }
            if (k == 37) {
                baroDrift += 0.02 * rng.gaussian()
                val alt = onTrack(track.elevation, m.s, track.elev_step) + 4 + baroDrift + 0.15 * rng.gaussian()
                f.addBaro(t, 101.325 * (1 - alt / 44330).pow(1 / 0.1903))
            }
        }
    }

    // MARK: the sample's place in History

    /**
     * The sample's meta.json: enough for History (a ride with its stops, as if the model had
     * heard them) and for replay to know it's the sample.
     */
    fun meta(startedUnix: Double): JsonObject = buildJsonObject {
        put("sample", true)
        put("app_version", "sample")
        put("complete", true)
        put("started_uptime_s", 0.0)
        put("started_unix_s", startedUnix)
        put("ended_uptime_s", duration)
        put("inferred", buildJsonObject {
            put("direction", "southbound")
            put("station_confidence", 1.0)
            put("stops", buildJsonArray {
                stops.forEach {
                    add(buildJsonObject {
                        put("station", it.station); put("doors_opened_uptime_s", it.opened); put("doors_closed_uptime_s", it.closed)
                    })
                }
            })
        })
    }
}

/** xoshiro256** with Gaussian draws (Box-Muller, one cached), as the iOS app's, so the same seed gives the same ride. */
private class Random(seed: Long) {
    private var s0: Long
    private var s1: Long
    private var s2: Long
    private var s3: Long
    private var spare: Double? = null

    init {
        var z = seed
        fun next(): Long {  // splitmix64
            z += 0x9E3779B97F4A7C15uL.toLong()
            var x = z
            x = (x xor (x ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
            x = (x xor (x ushr 27)) * 0x94D049BB133111EBuL.toLong()
            return x xor (x ushr 31)
        }
        s0 = next(); s1 = next(); s2 = next(); s3 = next()
    }

    private fun next(): Long {
        val result = java.lang.Long.rotateLeft(s1 * 5, 7) * 9
        val t = s1 shl 17
        s2 = s2 xor s0; s3 = s3 xor s1; s1 = s1 xor s2; s0 = s0 xor s3
        s2 = s2 xor t
        s3 = java.lang.Long.rotateLeft(s3, 45)
        return result
    }

    fun uniform(): Double = (next() ushr 11).toDouble() * (1.0 / (1L shl 53))

    fun gaussian(): Double {
        spare?.let { spare = null; return it }
        val u1 = max(uniform(), 1e-300)
        val u2 = uniform()
        val r = sqrt(-2 * ln(u1))
        spare = r * sin(2 * PI * u2)
        return r * cos(2 * PI * u2)
    }
}

/** A second-order section (RBJ cookbook). */
private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    private var z1 = 0.0
    private var z2 = 0.0

    companion object {
        /** Band-pass with 0 dB peak gain. */
        fun bandpass(f0: Double, q: Double, fs: Double): Biquad {
            val w = 2 * PI * f0 / fs
            val alpha = sin(w) / (2 * q)
            val a0 = 1 + alpha
            return Biquad(alpha / a0, 0.0, -alpha / a0, -2 * cos(w) / a0, (1 - alpha) / a0)
        }
    }

    fun step(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}
