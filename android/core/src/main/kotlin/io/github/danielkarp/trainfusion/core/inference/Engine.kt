package io.github.danielkarp.trainfusion.core.inference

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The online version of phone-sensor-fusion's model: per second, phone state ->
 * along-track acceleration and yaw (while the phone rests) -> emissions -> one forward
 * step of the HMM over (mode, speed) -> distance -> at each door opening, the station
 * layer. Only the past is used. Not thread-safe: use from one thread.
 * A port of ios/TrainRecorder/Inference/Engine.swift; see there (and docs/recorder.md,
 * "Online inference") for the reasoning.
 *
 * Two beliefs are kept, because one measurement (the accelerometer's line spectrum, for
 * the wheel-rotation lines) arrives about 3 s after the second it describes:
 *  - the *settled* belief, 3 s behind, which has every measurement of its second;
 *  - the *current* belief: the settled one stepped forward through the seconds since,
 *    using everything already in for them (all but those lines). It is what gets shown.
 * Stops, legs and the station layer advance with the settled belief.
 */
class InferenceEngine(val model: Model) {
    /** Along-track acceleration as measured, and the sign and offset to read it by. */
    data class Accel(val a: Double, val sign: Double, val offset: Double)

    /** The hidden-state belief and what rides along with it. */
    private class Filter(
        var alpha: DoubleArray,
        var dist: DoubleArray,  // expected metres since the last stop, given the state now
        var started: Boolean = false,
        var armed: Boolean = true,  // ready to count the next door-open chime as a stop
        var prevAccel: Accel? = null,  // the acceleration measured in the second this belief is about
        var prevResting: Boolean = false,
    ) {
        fun copy() = Filter(alpha.copyOf(), dist.copyOf(), started, armed, prevAccel, prevResting)
    }

    /** A second whose prompt measurements are in but whose accelerometer spectrum isn't. */
    private class Pending(
        val frame: Frame,
        val lb: DoubleArray,  // log emissions from everything prompt
        val phone: DoubleArray,
        val phoneState: Int,
        val resting: Boolean,
        val stretch: Int?,  // which resting stretch this second belongs to, if any
        val accel: Accel?,
        val yaw: Double?,
        val heading: Double?,  // compass heading of travel
        val toneRel: DoubleArray,  // the motor tone's relative log-likelihood per speed state
    )

    private class Transition(val i: Int, val j: Int, val p: Double, val dv: Double)

    val features = FeatureExtractor(model)
    private val n = model.n
    private val nSt = model.n_stopped
    private val nV = model.n_v
    private val trans = model.transitions.map {
        Transition(it[0].toInt(), it[1].toInt(), it[2], model.v[it[1].toInt()] - model.v[it[0].toInt()])
    }
    private var settled = Filter(model.prior.copyOf(), DoubleArray(model.n))
    private val pending = ArrayList<Pending>()
    private var phone = doubleArrayOf(1.0 / 3, 1.0 / 3, 1.0 / 3)
    private var settledSpeed = 0.0
    private var lastPrepared: Pending? = null
    private val speedRecent = ArrayList<Double>()  // the current belief's mean speed over the last 4 s
    private val toneRecent = ArrayList<Double>()  // evidence over the last few seconds, for the status display
    private val wheelRecent = ArrayList<Double?>()

    /** For checking the port: the latest prompt frame, the latest accelerometer spectrum
     * (and which second it belongs to), and how the acceleration is being read. */
    var lastFrame: Frame? = null
        private set
    var lastAccelSpec: Pair<Int, FloatArray>? = null
        private set
    var lastAccel: Accel? = null
        private set

    // the current stretch of resting phone
    private var restRun = 0
    private var stretchID = 0
    private val sAcc = ArrayList<Vec3>()
    private val sGyro = ArrayList<Vec3>()
    // from settled beliefs, so these trail sAcc by the seconds still pending
    private val sDv = ArrayList<Double>()  // speed change into each second of the stretch
    private val sDoors = ArrayList<Boolean>()  // whether the doors were (all but certainly) open or chiming that second

    // stops and the station layer (settled)
    private var stops = 0
    private val legs = ArrayList<Double>()
    private val stopLog = ArrayList<Belief.Stop>()
    private var closeArmed = true
    private val stationLayer = StationLayer(model)

    companion object {
        private const val EVIDENCE_SPAN = 5
        private const val EDGE = 2
        private const val MIN_STRETCH = 30
        private const val MIN_ACCEL_SD = 0.1
        private const val MIN_CORRELATION = 0.25
        private const val MIN_EVENT_ACCEL = 0.15  // m/s^2: mean braking / pulling-away acceleration that settles the sign
        private const val G = 9.80665
    }

    /** The belief about every second that has become available since the last call. */
    fun process(): List<Belief> {
        val out = ArrayList<Belief>()
        while (true) out.add(processNext() ?: break)
        return out
    }

    /** The current belief about the next second whose prompt measurements are in, if any. */
    fun processNext(): Belief? {
        if (!features.promptReady) return null
        val frame = features.makePromptFrame()
        lastFrame = frame
        val prepared = prepare(frame)
        lastPrepared = prepared
        pending.add(prepared)
        // settle every second whose accelerometer spectrum has now arrived
        while (features.lateReady && pending.isNotEmpty()) {
            val spec = features.makeAccelSpec()
            lastAccelSpec = pending[0].frame.second to spec
            settle(pending.removeAt(0), spec)
        }
        // the current belief: the settled one carried through what is still pending
        val now = settled.copy()
        // (if the door-open chime is in those seconds, the current belief has already started
        // the next leg at 0 while the stop isn't counted yet: remember the leg it finished)
        var uncounted: Double? = null
        for (p in pending) advance(now, p.lb, p.resting, p.accel, countStops = true)?.let { uncounted = it }
        val b = summarise(now, frame.second)
        b.phoneP = phone.copyOf()
        b.accelInUse = pending.lastOrNull()?.accel != null
        b.accelReading = pending.lastOrNull()?.accel?.let { it.sign * (it.a - it.offset) }
        b.stops = stops
        b.legs = legs.toList()
        b.stations = stationLayer.top(4)
        // until the stop is counted the station layer is still on the leg just finished
        b.stopPending = uncounted != null
        b.place = stationLayer.place((uncounted ?: 0.0) + b.distanceSinceStop)
        // the best guess, however unsure: whoever shows it looks at the confidence
        val names = stationLayer.stopNames(legs)
        b.stopLog = stopLog.mapIndexed { k, s -> s.copy(station = if (k < names.size) names[k] else s.station) }
        speedRecent.add(b.speedMean)
        if (speedRecent.size > 4) speedRecent.removeAt(0)
        if (speedRecent.size == 4) b.acceleration = (speedRecent[3] - speedRecent[0]) / 3
        (pending.lastOrNull() ?: lastPrepared)?.let { p ->
            toneRecent.add(evidence(p.toneRel, b.speedMean))
            if (toneRecent.size > EVIDENCE_SPAN) toneRecent.removeAt(0)
        }
        b.toneEvidence = if (toneRecent.isEmpty()) null else toneRecent.sum() / toneRecent.size
        val wheel = wheelRecent.filterNotNull()
        b.wheelEvidence = if (wheelRecent.lastOrNull() == null || wheel.isEmpty()) null else wheel.sum() / wheel.size
        return b
    }

    /** Everything about a second that doesn't wait for the accelerometer spectrum. */
    private fun prepare(f: Frame): Pending {
        // phone state (forward filter on the gyro level)
        val ph = model.phone
        val q = ph.`switch`
        val pred = DoubleArray(3)
        for (a in 0..2) for (b in 0..2) pred[b] += phone[a] * (if (a == b) 1 - 1.5 * q else q / 2)
        var best = Double.NEGATIVE_INFINITY
        val ll = DoubleArray(3)
        for (s in 0..2) {
            val z = (f.gyroRms.toDouble() - ph.mean[s]) / ph.sd[s]
            ll[s] = -0.5 * z * z - ln(ph.sd[s])
            best = max(best, ll[s])
        }
        var sum = 0.0
        val next = DoubleArray(3)
        for (s in 0..2) { next[s] = pred[s] * exp(ll[s] - best); sum += next[s] }
        for (s in 0..2) next[s] /= sum
        phone = next
        val state = argmaxFirst(3) { phone[it] }
        val resting = phone[0] > 0.5

        // motion while resting
        var accel: Accel? = null
        var yaw: Double? = null
        var heading: Double? = null
        var stretch: Int? = null
        if (resting) {
            restRun += 1
            if (restRun > EDGE) {
                sAcc.add(f.accMean); sGyro.add(f.gyroMean)
                stretch = stretchID
            }
            if (sAcc.size >= MIN_STRETCH) {
                val m = motion(f)
                accel = m.accel; yaw = m.yaw; heading = m.heading
            }
        } else if (restRun > 0) {
            restRun = 0
            stretchID += 1
            sAcc.clear(); sGyro.clear(); sDv.clear(); sDoors.clear()
        }
        lastAccel = accel

        // emissions from the prompt measurements
        val lb = DoubleArray(n)
        for ((name, x, st) in listOf(
            Triple("open", f.openChime, model.open_chime_state),
            Triple("close", f.closeChime, model.close_chime_state),
        )) {
            val cat = (if (x.toDouble() >= model.chime_edges[0]) 1 else 0) + (if (x.toDouble() >= model.chime_edges[1]) 1 else 0)
            val c = model.chime.getValue(name)
            for (k in 0 until n) lb[k] += c.log_out[cat]
            lb[st] += c.log_in[cat] - c.log_out[cat]
        }
        val toneRel = addComb(model.tone, f.toneSpec, lb)
        model.level["rumble"]?.let { addLevel(it, f.rumbleDb.toDouble(), lb) }
        if (state < 2) {
            val suffix = if (state == 0) "resting" else "held"
            for ((b, name) in listOf("vib_low", "vib_high").withIndex()) {
                model.level["${name}_$suffix"]?.let { addLevel(it, f.vibDb[b].toDouble(), lb) }
            }
        }
        val travel = if (heading != null && accel != null) (heading + if (accel.sign > 0) 180.0 else 0.0) % 360.0 else null
        return Pending(f, lb, phone.copyOf(), state, resting, stretch, accel, yaw, travel, toneRel)
    }

    /**
     * A second's accelerometer spectrum has arrived: finish its emissions and make it
     * part of the settled belief; count stops and feed the station layer.
     */
    private fun settle(p: Pending, accelSpec: FloatArray) {
        val lb = p.lb.copyOf()
        var wheelRel: DoubleArray? = null
        if (p.phoneState < 2) {
            model.wheel[if (p.phoneState == 0) "resting" else "held"]?.let { wheelRel = addComb(it, accelSpec, lb) }
        }
        val stopped = advance(settled, lb, p.resting, p.accel, countStops = true)
        val b = summarise(settled, p.frame.second)
        wheelRecent.add(wheelRel?.let { evidence(it, b.speedMean) })
        if (wheelRecent.size > EVIDENCE_SPAN) wheelRecent.removeAt(0)
        if (p.stretch == stretchID) {  // (a second of a stretch that has since ended teaches nothing)
            sDv.add(b.speedMean - settledSpeed)
            sDoors.add(b.modeP[2] + b.modeP[3] + b.modeP[4] > 0.9)
        }
        settledSpeed = b.speedMean
        stationLayer.addSecond(b.speedMean, stopped ?: b.distanceSinceStop, p.yaw, p.heading, p.frame.baroAlt)
        if (stopped != null) {
            if (stops > 0) legs.add(stopped)
            stops += 1
            stationLayer.stop()
            stopLog.add(Belief.Stop(opened = p.frame.second))
        }
        // the door-close chime's leading edge, for the log of stops
        val pClose = settled.alpha[model.close_chime_state]
        if (closeArmed && pClose > 0.5) {
            closeArmed = false
            if (stopLog.isNotEmpty() && stopLog.last().closed == null) stopLog[stopLog.size - 1] = stopLog.last().copy(closed = p.frame.second)
        } else if (pClose < 0.1) {
            closeArmed = true
        }
    }

    /**
     * One forward step of the HMM into a second with log emissions `lb`, the transition
     * weighed by the acceleration measured in the second before. Returns the distance of
     * the leg just ended if this step is a door opening (and starts the next leg at 0).
     */
    private fun advance(f: Filter, lb: DoubleArray, resting: Boolean, accel: Accel?, countStops: Boolean): Double? {
        var new = DoubleArray(n)
        val newDist = DoubleArray(n)
        if (!f.started) {
            new = f.alpha.copyOf()
            f.started = true
        } else {
            val acc = model.accel
            val m = if (resting && f.prevResting) f.prevAccel else null
            for (t in trans) {
                var w = f.alpha[t.i] * t.p
                if (w == 0.0) continue
                if (m != null) {
                    val r = (m.a - m.offset) - m.sign * t.dv
                    val o = (m.a - m.offset) / acc.sd_outlier
                    w *= (1 - acc.outlier) * exp(-0.5 * (r / acc.sd) * (r / acc.sd)) / acc.sd +
                        acc.outlier * exp(-0.5 * o * o) / acc.sd_outlier
                }
                new[t.j] += w
                newDist[t.j] += w * f.dist[t.i]
            }
        }
        var top = Double.NEGATIVE_INFINITY
        for (x in lb) if (x > top) top = x
        var total = 0.0
        for (k in 0 until n) {
            if (new[k] > 0) newDist[k] = newDist[k] / new[k] + model.v[k] else newDist[k] = 0.0
            new[k] *= exp(lb[k] - top)
            total += new[k]
        }
        if (total > 0) { for (k in 0 until n) new[k] /= total } else new = model.prior.copyOf()
        f.alpha = new
        f.dist = newDist
        f.prevAccel = accel
        f.prevResting = resting

        val pOpen = new[model.open_chime_state]
        var leg: Double? = null
        if (f.armed && pOpen > 0.5) {
            f.armed = false
            var s = 0.0
            for (k in 0 until n) s += new[k] * newDist[k]
            leg = s
            f.dist = DoubleArray(n)
        } else if (pOpen < 0.1) {
            f.armed = true
        }
        return if (countStops) leg else null
    }

    private fun summarise(f: Filter, second: Int): Belief {
        val b = Belief(second = second)
        var m2 = 0.0
        val modeP = DoubleArray(6)
        val hist = FloatArray(kotlin.math.ceil(model.v[n - 1]).toInt() + 1)
        var mean = 0.0
        var dist = 0.0
        for (k in 0 until n) {
            mean += f.alpha[k] * model.v[k]
            m2 += f.alpha[k] * model.v[k] * model.v[k]
            dist += f.alpha[k] * f.dist[k]
            modeP[model.mode[k]] += f.alpha[k]
            if (k >= nSt) hist[model.v[k].toInt()] += f.alpha[k].toFloat()
        }
        b.speedMean = mean
        b.distanceSinceStop = dist
        b.modeP = modeP
        b.speedHist = hist
        b.speedSd = sqrt(max(m2 - mean * mean, 0.0))
        b.pStanding = 1 - modeP[0]
        return b
    }

    private class Motion(val accel: Accel?, val yaw: Double?, val heading: Double?)

    /**
     * Along-track acceleration (with the sign and offset to read it by, once they can be
     * told), yaw rate about the vertical, and the compass heading of the + axis, from the
     * resting stretch so far.
     */
    private fun motion(f: Frame): Motion {
        val count = sAcc.size.toDouble()
        var gSum = Vec3()
        for (a in sAcc) gSum += a
        val g = gSum / count
        val up = -g.normalized()
        val cov = Mat3()
        val horiz = ArrayList<Vec3>(sAcc.size)
        for (a in sAcc) {
            val d = a - g
            val h = d - up * (d dot up)
            horiz.add(h)
            cov.addOuter(h, h)
        }
        // principal axis: power iteration, then a fixed sign so it doesn't flip from second to second
        var axis = Vec3(1.0, 1.0, 1.0)
        for (i in 0 until 60) {
            val nxt = cov * axis
            val len = nxt.length
            if (len < 1e-18) break
            axis = nxt / len
        }
        axis -= up * (axis dot up)
        if (axis.length < 1e-9) return Motion(null, null, null)
        axis = axis.normalized()
        val big = argmaxFirst(3) { abs(axis[it]) }
        if (axis[big] < 0) axis = -axis

        // yaw about the vertical, less its median over the stretch (the gyro's bias)
        val yaws = DoubleArray(sGyro.size) { sGyro[it] dot up }
        val yawNow = yaws[yaws.size - 1]
        yaws.sort()
        val yaw = yawNow - yaws[yaws.size / 2]

        var heading: Double? = null
        f.attitude?.let { q ->
            val w = q.act(axis)  // world: x magnetic north, y west
            heading = (atan2(-w.y, w.x) * 180 / Math.PI + model.track.declination + 360) % 360.0
        }

        // Which way to read it (sign), and its zero (offset). Two ways, the first much the
        // better when it is available:
        //  (a) from a station stop inside this stretch. Door chimes are certain, so: the
        //      level while the doors are open is zero; the train braked into the stop
        //      (negative) and pulls away from it (positive).
        //  (b) otherwise, from how the acceleration goes with the filter's own speed changes.
        var aLong: Accel? = null
        val known = min(sDoors.size, horiz.size)
        val a = DoubleArray(horiz.size) { (horiz[it] dot axis) * G }
        val aNow = a[a.size - 1]
        var zeroSum = 0.0
        var zeroN = 0.0
        for (k in 0 until known) if (sDoors[k]) { zeroSum += a[k]; zeroN += 1 }
        if (zeroN >= 3) {
            val zero = zeroSum / zeroN
            var score = 0.0
            fun mean(lo: Int, hi: Int): Double? {
                val l = max(lo, 0)
                val h = min(hi, a.size)
                if (h - l < 8) return null
                var s = 0.0
                for (i in l until h) s += a[i]
                return s / (h - l) - zero
            }
            var k = 0
            while (k < known) {
                if (!sDoors[k]) { k += 1; continue }
                var end = k
                while (end < known && sDoors[end]) end += 1
                mean(k - 25, k - 3)?.let { score -= it }
                mean(end + 8, end + 30)?.let { score += it }
                k = end
            }
            if (abs(score) >= MIN_EVENT_ACCEL) aLong = Accel(aNow, if (score > 0) 1.0 else -1.0, zero)
        }
        val m = min(sDv.size, horiz.size) - 1
        if (aLong == null && m >= MIN_STRETCH - 1) {
            var sa = 0.0; var sd = 0.0; var saa = 0.0; var sdd = 0.0; var sad = 0.0
            for (k in 1..m) {
                // sDv[k] is the change INTO second k; the acceleration behind it was measured in second k - 1
                val x = a[k - 1]
                val d = sDv[k]
                sa += x; sd += d; saa += x * x; sdd += d * d; sad += x * d
            }
            val cnt = m.toDouble()
            val varA = saa / cnt - (sa / cnt) * (sa / cnt)
            val varD = sdd / cnt - (sd / cnt) * (sd / cnt)
            val cov2 = sad / cnt - sa / cnt * sd / cnt
            // The filter's speed changes are the noisy side of this pair, so judge by
            // correlation, not by the slope, and take the acceleration at face value (slope 1)
            // for the offset. Needs the train to have accelerated in this stretch.
            if (varA > MIN_ACCEL_SD * MIN_ACCEL_SD && varD > 0 && abs(cov2) / sqrt(varA * varD) >= MIN_CORRELATION) {
                val sign = if (cov2 > 0) 1.0 else -1.0
                aLong = Accel(aNow, sign, sa / cnt - sign * sd / cnt)
            }
        }
        return Motion(aLong, yaw, heading)
    }

    /**
     * Adds log p(line spectrum | speed) - log p(no lines) to the moving states, scaled so
     * that it says which speed, never whether moving.
     */
    private fun addComb(c: Model.Comb, spec: FloatArray, lb: DoubleArray): DoubleArray {
        val ll = DoubleArray(nV)
        val pooled = FloatArray(spec.size)
        for (p in c.partials) {
            var s = spec
            if (p.extra > 0) { Filters.maximum(spec, 2 * p.extra + 1, pooled); s = pooled }
            for (v in 0 until nV) {
                if (p.bin[v] < 0) continue
                val z = s[p.bin[v]].toDouble()
                var zb = 0
                while (zb < c.z_edges.size && z >= c.z_edges[zb]) zb += 1
                ll[v] += p.table[p.band[v]][zb]
            }
        }
        var top = Double.NEGATIVE_INFINITY
        for (x in ll) if (x > top) top = x
        var mean = 0.0
        for (v in 0 until nV) mean += exp(ll[v] - top)
        val norm = top + ln(mean / nV)
        for (v in 0 until nV) { ll[v] -= norm; lb[nSt + v] += ll[v] }
        return ll
    }

    /** How well a line-spectrum likelihood (per speed state) backs speed `v`: its best value within 1 m/s. */
    private fun evidence(rel: DoubleArray, v: Double): Double {
        val k = roundHalfAway(v / model.dv).toInt() - 1
        val r = (1 / model.dv).toInt()
        var best = Double.NEGATIVE_INFINITY
        for (i in max(k - r, 0)..min(max(k + r, 0), nV - 1)) best = max(best, rel[i])
        return best
    }

    private fun addLevel(q: Model.Level, x: Double, lb: DoubleArray) {
        for (k in 0 until n) {
            val z = (x - q.mu[k]) / q.sd
            lb[k] += q.weight * ln((1 - model.level_outlier) * exp(-0.5 * z * z) + model.level_outlier * model.level_floor)
        }
    }
}

/**
 * Which station: a second, small HMM over (station, direction) that advances at each
 * door opening, weighing every possible leg a -> b by its length, and (where the phone
 * gives them) the yaw, heading and pressure altitude measured along it against the map.
 */
class StationLayer(model: Model) {
    private val tr = model.track
    private val c = model.stations
    private val nS = tr.stations.size
    private var alpha = Array(nS) { doubleArrayOf(0.5 / nS, 0.5 / nS) }  // [station][0 south, 1 north]
    private var haveStop = false
    // this leg so far, one entry per second
    private val v = ArrayList<Double>()
    private val d = ArrayList<Double>()
    private val yaw = ArrayList<Double?>()
    private val heading = ArrayList<Double?>()
    private val baro = ArrayList<Double?>()

    private fun k(name: String) = c.getValue(name)

    fun addSecond(v: Double, dist: Double, yaw: Double?, heading: Double?, baro: Double?) {
        this.v.add(v); d.add(dist); this.yaw.add(yaw); this.heading.add(heading); this.baro.add(baro)
    }

    private fun robust(r: List<Double>, outlier: Double, weight: Double): Double {
        var s = 0.0
        for (x in r) s += ln((1 - outlier) * exp(-0.5 * x * x) + outlier * 0.05)
        return weight * s
    }

    private fun interp(a: DoubleArray, x: Double, step: Double): Double {
        val u = min(max(x / step, 0.0), (a.size - 1).toDouble())
        val i = min(u.toInt(), a.size - 2)
        return a[i] + (a[i + 1] - a[i]) * (u - i)
    }

    private fun trackHeading(s: Double, direction: Double) = interp(tr.heading_south, s, tr.step) + if (direction > 0) 0.0 else 180.0

    private fun wrap(x: Double) = (x + 540) % 360.0 - 180

    private fun legLogLik(sa: Double, sb: Double, dMeas: Double): Double {
        val direction = if (sb > sa) 1.0 else -1.0
        val scale = abs(sb - sa) / dMeas
        var ll = 0.0
        val ry = ArrayList<Double>()
        val rh = ArrayList<Double>()
        val rb = ArrayList<Double>()
        for (t in v.indices) {
            val pos = sa + direction * d[t] * scale
            val y = yaw[t]
            if (y != null && v[t] > k("MIN_YAW_SPEED")) ry.add((y - direction * v[t] * interp(tr.curvature, pos, tr.step)) / k("YAW_SD"))
            heading[t]?.let { rh.add(wrap(it - trackHeading(pos, direction)) / k("HEADING_SD")) }
            val b = baro[t]
            if (b != null && v[t] > k("MIN_BARO_SPEED")) rb.add(b - interp(tr.elevation, pos, tr.elev_step))
        }
        if (ry.size >= 5) ll += robust(ry, k("YAW_OUTLIER"), k("YAW_WEIGHT"))
        if (rh.size >= 5) ll += robust(rh, k("HEADING_OUTLIER"), k("HEADING_WEIGHT"))
        if (rb.size >= 20) {
            val med = rb.sorted()[rb.size / 2]
            ll += robust(rb.map { (it - med) / k("BARO_SD") }, k("BARO_OUTLIER"), k("BARO_WEIGHT"))
        }
        return ll
    }

    /** A door opening was heard: fold in the leg that just ended, and start a new one. */
    fun stop() {
        var new = Array(nS) { alpha[it].copyOf() }
        val dLast = d.lastOrNull()
        if (haveStop && dLast != null) {
            val dMeas = max(dLast, 1.0)
            val sd = hypot(k("DIST_REL") * dMeas, k("DIST_ABS"))
            val lw = Array(nS) { DoubleArray(nS) { Double.NEGATIVE_INFINITY } }
            var top = Double.NEGATIVE_INFINITY
            for (a in 0 until nS) for (b in 0 until nS) {
                if (a == b) continue
                val trueD = abs(tr.station_s[b] - tr.station_s[a])
                if (abs(trueD - dMeas) > 6 * sd) continue
                val z = (dMeas - trueD) / sd
                lw[a][b] = -0.5 * z * z - ln(sd) + (abs(b - a) - 1) * ln(k("P_SKIP")) +
                    legLogLik(tr.station_s[a], tr.station_s[b], dMeas)
                top = max(top, lw[a][b])
            }
            val w = Array(nS) { DoubleArray(nS) }
            var sum = 0.0
            if (top > Double.NEGATIVE_INFINITY) {
                for (a in 0 until nS) for (b in 0 until nS) {
                    if (lw[a][b] > Double.NEGATIVE_INFINITY) { w[a][b] = exp(lw[a][b] - top); sum += w[a][b] }
                }
            }
            val pu = k("P_UNEXPLAINED")
            val floor = pu / (nS * (nS - 1))
            new = Array(nS) { doubleArrayOf(0.0, 0.0) }
            for (a in 0 until nS) for (b in 0 until nS) {
                if (a == b) continue
                val wab = (1 - pu) * (if (sum > 0) w[a][b] / sum else 0.0) + floor
                if (b > a) new[b][0] += alpha[a][0] * wab else new[b][1] += alpha[a][1] * wab
            }
        }
        // heading around the stop itself, against the track's heading at each station
        val recent = heading.takeLast(15).filterNotNull()
        if (recent.size >= 5) {
            var sx = 0.0
            var cx = 0.0
            for (h in recent) { sx += sin(h * Math.PI / 180); cx += cos(h * Math.PI / 180) }
            val here = atan2(sx, cx) * 180 / Math.PI
            val o = k("HEADING_OUTLIER")
            for (a in 0 until nS) for (dir in 0..1) {
                val z = wrap(here - trackHeading(tr.station_s[a], if (dir == 0) 1.0 else -1.0)) / k("HEADING_SD")
                new[a][dir] *= (1 - o) * exp(-0.5 * z * z) + o * 0.05
            }
        }
        var total = 0.0
        for (row in new) total += row[0] + row[1]
        if (total > 0) alpha = Array(nS) { doubleArrayOf(new[it][0] / total, new[it][1] / total) }
        haveStop = true
        v.clear(); d.clear(); yaw.clear(); heading.clear(); baro.clear()
    }

    private class Segment(val last: Int, val next: Int?, val from: Double, val to: Double?)

    /**
     * For the hypothesis (station a, direction dir) and metres since that stop: the
     * station behind, the next one ahead that hasn't clearly been passed (an express skips
     * some), and the position between them.
     */
    private fun segment(a: Int, dir: Int, d: Double): Segment {
        val step = if (dir == 0) 1 else -1
        val here = tr.station_s[a] + step * d
        var nxt = a + step
        while (nxt in 0 until nS && step * (tr.station_s[nxt] - here) < -300) nxt += step
        if (nxt !in 0 until nS) return Segment(a, null, abs(here - tr.station_s[a]), null)
        return Segment(nxt - step, nxt, abs(here - tr.station_s[nxt - step]), max(abs(tr.station_s[nxt] - here), 0.0))
    }

    /** The most probable (station, direction), turned into "between here and there". */
    fun place(metresSinceStop: Double): Belief.Place? {
        if (!haveStop) return null
        class Ranked(val a: Int, val dir: Int, val p: Double)
        val ranked = ArrayList<Ranked>()
        for (a in 0 until nS) for (dir in 0..1) ranked.add(Ranked(a, dir, alpha[a][dir]))
        ranked.sortByDescending { it.p }
        val best = ranked[0]
        val second = ranked[1]
        if (best.p <= 0.2) return null  // one stop in, with nothing to tell the stations apart
        val seg = segment(best.a, best.dir, metresSinceStop)
        var alternative: String? = null
        if (second.p > 0.15) {
            val alt = segment(second.a, second.dir, metresSinceStop)
            alternative = tr.stations[alt.last] + (alt.next?.let { " → " + tr.stations[it] } ?: "")
        }
        val span = seg.to?.let { seg.from + it } ?: 1.0
        return Belief.Place(
            last = tr.stations[seg.last], next = seg.next?.let { tr.stations[it] },
            southbound = best.dir == 0, confidence = best.p,
            progress = if (seg.next == null) 0.0 else min(max(seg.from / max(span, 1.0), 0.0), 1.0),
            metresFromLast = seg.from, metresToNext = seg.to, alternative = alternative,
        )
    }

    /**
     * The station of every stop so far, working back from the most probable current one
     * along the legs' lengths. `legs[k]` is the distance from stop k to stop k + 1.
     */
    fun stopNames(legs: List<Double>): List<String> {
        if (!haveStop) return emptyList()
        var bestA = 0
        var bestDir = 0
        var bestP = -1.0
        for (a in 0 until nS) for (dir in 0..1) if (alpha[a][dir] > bestP) { bestA = a; bestDir = dir; bestP = alpha[a][dir] }
        val step = if (bestDir == 0) 1.0 else -1.0
        val names = arrayListOf(tr.stations[bestA])
        var s = tr.station_s[bestA]
        for (leg in legs.asReversed()) {
            s -= step * leg
            val target = s
            val nearest = argminFirst(nS) { abs(tr.station_s[it] - target) }
            names.add(tr.stations[nearest])
            s = tr.station_s[nearest]  // snap, so errors don't add up along the way
        }
        return names.asReversed()
    }

    fun top(k: Int): List<Belief.Station> {
        if (!haveStop) return emptyList()
        val all = ArrayList<Belief.Station>()
        for (a in 0 until nS) for (dir in 0..1) if (alpha[a][dir] > 0.005) all.add(Belief.Station(tr.stations[a], dir == 0, alpha[a][dir]))
        return all.sortedByDescending { it.p }.take(k)
    }
}
