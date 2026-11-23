package io.github.danielkarp.trainfusion.core.inference

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow

/**
 * One second of measurements: what phone-sensor-fusion/features/frames.py computes,
 * recomputed here from the live streams. Second n covers recording time [n, n+1).
 */
class Frame(val second: Int) {
    var openChime = 0f
    var closeChime = 0f  // fraction of the second each chime is present
    var toneSpec = FloatArray(0)  // audio line spectrum, dB over the local floor
    var rumbleDb = 0f
    var vibDb = floatArrayOf(0f, 0f)
    var gyroRms = 0f  // log10 rad/s
    var accMean = Vec3()  // g
    var gyroMean = Vec3()  // rad/s
    var baroAlt: Double? = null  // pressure altitude, m
    var attitude: Quat? = null  // phone axes -> world (x magnetic north, y west, z up)
}

/**
 * Turns the sensor streams into [Frame]s. Feed it samples in time order per stream.
 * A second comes out in two parts: almost everything as soon as the second is over
 * ([makePromptFrame]), and the accelerometer line spectrum about 3 s later
 * ([makeAccelSpec]), because its 5 s window is centred on the second.
 * Not thread-safe: use from one thread. A port of ios/TrainRecorder/Inference/Features.swift.
 *
 * Accelerometer samples are in g with iOS's sign (a phone lying face up reads z = -1),
 * gyroscope in rad/s, all on phone axes; times are seconds since the recording started.
 */
class FeatureExtractor(model: Model) {
    private val d = model.dsp
    private val fs = d.fs

    // audio, 16 kHz, sample k at recording time k / fs
    private val decimator = Decimator(d.decimate_taps)
    private val a16 = FloatBuf(1 shl 16)
    private var a16Base = 0  // index of a16[0] in the whole stream
    private var fed48 = 0L  // 48 kHz samples received so far (including padding)
    private val rumble = SOSFilter(d.rumble_sos)
    private val rumbleSum = ArrayList<Double>()  // per second, sum of filtered^2
    private var rumbleCount = 0  // 16 kHz samples filtered so far
    private val chimeSpec = SegmentSpectrum(d.chime.window, d.chime.nwin, d.fs.toFloat())
    private val toneSpec = SegmentSpectrum(d.tone.window, d.tone.nfft, d.fs.toFloat())
    private val chimeDb = FloatArray(d.chime.nwin / 2 + 1)
    private val toneDb = FloatArray(d.tone.bins)
    private val toneMed = FloatArray(d.tone.bins)
    private val tonePool = FloatArray(d.tone.bins)

    // IMU, resampled to 100 Hz from t = 0
    private class Resampler {
        var prevT = Double.NEGATIVE_INFINITY
        var prev = Vec3()
        val out = ArrayList<Vec3>()
        var base = 0
        val count: Int get() = base + out.size

        fun add(t: Double, v: Vec3) {
            // np.interp onto the grid k / 100: linear between samples, the first value held before it
            while (count.toDouble() * 0.01 <= t) {
                val g = count.toDouble() * 0.01
                out.add(if (prevT == Double.NEGATIVE_INFINITY || t == prevT) v else prev + (v - prev) * ((g - prevT) / (t - prevT)))
            }
            prevT = t
            prev = v
        }

        operator fun get(i: Int): Vec3 = out[maxOf(i, base) - base]

        fun trim(before: Int) {
            if (before <= base) return
            val k = minOf(before - base, out.size)
            out.subList(0, k).clear()
            base = before
        }
    }

    private val accel = Resampler()
    private val gyro = Resampler()
    private val mag = FloatBuf(4096)  // |accel|, aligned with accel.out
    private var firstMag: Float? = null
    private val vib = d.vib_sos.map { SOSFilter(it) }
    private val vibSum = listOf(ArrayList<Double>(), ArrayList<Double>())
    private val accelSpec = SegmentSpectrum(d.accel.window, d.accel.nfft, 100f)
    private val accDb = FloatArray(d.accel.bins)
    private val accMed = FloatArray(d.accel.bins)
    private val accSeg = FloatArray(d.accel.nwin)

    private class Timed<T>(val t: Double, val v: T)

    private val baro = ArrayDeque<Timed<Double>>()
    private val attitude = ArrayDeque<Timed<Quat>>()
    private var attitudeFlip: Boolean? = null  // whether the quaternion has to be inverted to be phone -> world
    private var flipKeep = 0.0
    private var flipFlip = 0.0
    private var flipN = 0

    var nextPrompt = 0  // the next second whose prompt part is due
        private set
    var nextLate = 0  // the next second whose accelerometer spectrum is due
        private set

    // MARK: feeding

    /**
     * 48 kHz mono samples whose first sample was captured at recording time `t` (null:
     * straight after the previous buffer). Gaps are filled with silence.
     */
    fun addAudio48k(x: FloatArray, offset: Int = 0, count: Int = x.size - offset, t: Double?) {
        if (t != null) {
            val want = roundHalfAway(t * 48000).toLong()
            if (want - fed48 > 2400 || fed48 == 0L) {  // the start offset, or a gap of > 50 ms
                val pad = maxOf(want - fed48, 0L)
                var left = pad
                val zeros = FloatArray(48000)
                while (left > 0) {
                    val k = minOf(left, zeros.size.toLong()).toInt()
                    feed(zeros, 0, k)
                    left -= k
                }
            }
        }
        feed(x, offset, count)
    }

    /**
     * Audio already at the model's rate (`fs`), straight after what came before: for
     * generated audio (the sample ride), which needn't be made at 48 kHz to be decimated.
     */
    fun addAudio16k(x: FloatArray, offset: Int = 0, count: Int = x.size - offset) {
        val before = a16.size
        a16.append(x, offset, count)
        fed48 += 3L * count
        filterRumble(before)
    }

    private fun feed(x: FloatArray, offset: Int, count: Int) {
        if (count <= 0) return
        fed48 += count
        val before = a16.size
        decimator.process(x, offset, count, a16)
        filterRumble(before)
    }

    private fun filterRumble(before: Int) {
        for (i in before until a16.size) {
            val y = rumble.step(a16[i].toDouble())
            val sec = rumbleCount / fs
            if (sec >= rumbleSum.size) rumbleSum.add(0.0)
            rumbleSum[sec] = rumbleSum[sec] + y * y
            rumbleCount += 1
        }
    }

    fun addAccel(t: Double, v: Vec3) {
        val before = accel.out.size
        accel.add(t, v)
        for (i in before until accel.out.size) {
            val m = accel.out[i].length
            if (firstMag == null) firstMag = m.toFloat()
            mag.append(m.toFloat())
            val k = accel.base + i
            for (b in 0..1) {
                val y = vib[b].step(m)
                if (k / 100 >= vibSum[b].size) vibSum[b].add(0.0)
                vibSum[b][k / 100] = vibSum[b][k / 100] + y * y
            }
        }
    }

    fun addGyro(t: Double, v: Vec3) = gyro.add(t, v)

    fun addBaro(t: Double, kPa: Double) {
        baro.addLast(Timed(t, 44330.0 * (1 - (kPa / 101.325).pow(0.1903))))
    }

    /** The fused attitude quaternion and gravity (phone axes, in g, iOS's sign) from one device-motion sample. */
    fun addAttitude(t: Double, q: Quat, gravity: Vec3) {
        if (attitudeFlip == null) {
            // whichever sense of the quaternion sends gravity straight down is phone -> world
            flipKeep += q.act(gravity).z
            flipFlip += q.inverse.act(gravity).z
            flipN += 1
            if (flipN >= 100) attitudeFlip = flipKeep > flipFlip
        }
        attitude.addLast(Timed(t, q))
    }

    // MARK: producing

    /** Whether the prompt part of second `nextPrompt` can be made from what has arrived. */
    val promptReady: Boolean
        get() {
            val n = nextPrompt
            return a16Base + a16.size >= (n + 1) * fs + d.chime.nwin &&
                accel.count >= 100 * (n + 1) && gyro.count >= 100 * (n + 1)
        }

    /** Everything but the accelerometer line spectrum (`accelSpec` is left empty). */
    fun makePromptFrame(): Frame {
        val n = nextPrompt
        val f = Frame(n)
        chimes(n, f)
        tone(n, f)
        f.rumbleDb = (10 * log10(rumbleSum[n] / fs + 1e-16)).toFloat()
        for (b in 0..1) f.vibDb[b] = (10 * log10(vibSum[b][n] / 100 + 1e-16)).toFloat()

        var sumSq = 0.0
        var accSum = Vec3()
        var gyroSum = Vec3()
        for (k in (100 * n) until (100 * n + 100)) {
            accSum += accel[k]
            val g = gyro[k]
            gyroSum += g
            sumSq += g.lengthSquared
        }
        f.accMean = accSum / 100.0
        f.gyroMean = gyroSum / 100.0
        f.gyroRms = (0.5 * log10(sumSq / 100 + 1e-12)).toFloat()

        // barometer and attitude at the second's middle (the barometer's next sample may
        // not be in yet: then its latest value stands)
        val centre = n + 0.5
        if (baro.isNotEmpty()) {
            while (baro.size >= 2 && baro[1].t <= centre) baro.removeFirst()
            if (baro.size >= 2 && baro[0].t <= centre) {
                val a = baro[0]
                val b = baro[1]
                f.baroAlt = a.v + (b.v - a.v) * (centre - a.t) / (b.t - a.t)
            } else {
                f.baroAlt = baro[0].v
            }
        }
        attitudeFlip?.let { flip ->
            while (attitude.size >= 2 && attitude[0].t < centre) attitude.removeFirst()
            val a = attitude.firstOrNull()
            if (a != null && abs(a.t - centre) < 0.5) f.attitude = if (flip) a.v.inverse else a.v
        }

        nextPrompt += 1
        // drop what no later second needs
        val keepAudio = nextPrompt * fs - d.chime.nwin - a16Base
        if (keepAudio > fs) {
            a16.removeFirst(keepAudio)
            a16Base += keepAudio
        }
        if (100 * nextPrompt - gyro.base > 500) gyro.trim(100 * nextPrompt)
        return f
    }

    /** Whether the accelerometer line spectrum of second `nextLate` can be made. */
    val lateReady: Boolean
        get() = nextLate < nextPrompt && accel.count >= 100 * nextLate + d.accel.nwin - 206

    /** The accelerometer line spectrum of second `nextLate`: 5.12 s centred on its middle. */
    fun makeAccelSpec(): FloatArray {
        val n = nextLate
        val start = 100 * n - 206
        for (i in 0 until d.accel.nwin) {
            val k = start + i
            accSeg[i] = if (k < 0) (firstMag ?: 1f) else mag[maxOf(k, accel.base) - accel.base]
        }
        accelSpec.db(accSeg, 0, accDb)
        Filters.median(accDb, d.accel.median, accMed)
        for (i in accDb.indices) accDb[i] -= accMed[i]
        val out = FloatArray(accDb.size)
        Filters.maximum(accDb, d.accel.pool, out)
        nextLate += 1
        val keepImu = 100 * nextLate - 300
        if (keepImu - accel.base > 500) {
            mag.removeFirst(keepImu - accel.base)
            accel.trim(keepImu)
        }
        return out
    }

    private fun chimes(n: Int, f: Frame) {
        // STFT frames whose middle falls in this second
        val c = d.chime
        val lo = maxOf(0, ceil((fs * n - c.nwin / 2).toDouble() / c.hop).toInt())
        val hi = (fs * (n + 1) - c.nwin / 2 - 1) / c.hop
        val on = intArrayOf(0, 0)
        var total = 0
        if (hi >= lo) {
            for (i in lo..hi) {
                val s = i * c.hop - a16Base
                chimeSpec.db(a16.data, s, chimeDb)
                total += 1
                for ((which, partials) in listOf(c.open, c.close).withIndex()) {
                    var present = 0
                    for (p in partials) if (prominence(p) > c.db) present += 1
                    if (present >= c.min_partials) on[which] += 1
                }
            }
        }
        f.openChime = on[0].toFloat() / maxOf(total, 1)
        f.closeChime = on[1].toFloat() / maxOf(total, 1)
    }

    /** dB of the strongest bin at a partial over the median of the bins to either side. */
    private fun prominence(b: Model.Bins): Float {
        var peak = Float.NEGATIVE_INFINITY
        for (k in b.`in`) if (chimeDb[k] > peak) peak = chimeDb[k]
        val side = FloatArray(b.side.size) { chimeDb[b.side[it]] }
        side.sort()
        val m = side.size
        return peak - if (m % 2 == 1) side[m / 2] else (side[m / 2 - 1] + side[m / 2]) / 2
    }

    private fun tone(n: Int, f: Frame) {
        val t = d.tone
        val hop = t.hop!!
        val per = fs / hop
        val spec = FloatArray(t.bins)
        for (i in (per * n) until (per * n + per)) {
            val s = i * hop - a16Base
            toneSpec.db(a16.data, s, toneDb)
            Filters.median(toneDb, t.median, toneMed)
            for (k in toneDb.indices) toneDb[k] -= toneMed[k]
            Filters.maximum(toneDb, t.pool, tonePool)
            for (k in spec.indices) spec[k] += tonePool[k]
        }
        val scale = 1f / per
        for (k in spec.indices) spec[k] *= scale
        f.toneSpec = spec
    }
}
