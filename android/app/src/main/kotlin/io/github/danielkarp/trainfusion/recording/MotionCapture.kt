package io.github.danielkarp.trainfusion.recording

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.danielkarp.trainfusion.core.inference.Quat
import io.github.danielkarp.trainfusion.core.inference.Vec3
import io.github.danielkarp.trainfusion.core.recording.GzipWriter
import io.github.danielkarp.trainfusion.platform.AndroidClock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The accelerometer, gyroscope and magnetometer, the barometer, and a fusion of the motion
 * sensors ("device motion"), each to its own .csv.gz with the iOS app's columns and units
 * (ios/TrainRecorder/MotionCapture.swift, docs/recorder.md), stamped with the sensor's own
 * sample time (uptime seconds).
 *
 * Android's conventions differ from iOS's, so samples are converted to iOS's as they're
 * written, so that a recording is a recording whichever phone made it:
 *  - acceleration: Android gives m/s² with a phone lying face up reading z = +9.81; iOS
 *    gives g with it reading z = −1. So x_g = −a / 9.80665 (and the same for gravity and
 *    user acceleration in device_motion);
 *  - the "raw" gyroscope and magnetometer are Android's *uncalibrated* sensors (with the
 *    bias still in, as iOS's raw ones), where the phone has them; the calibrated ones go
 *    into device_motion, as iOS's;
 *  - pressure: hPa to kPa; the relative altitude, which Android doesn't give, is the
 *    standard atmosphere's from the first reading;
 *  - device_motion's attitude: Android's rotation vector turns phone axes into east, north,
 *    up; it's rotated into iOS's xMagneticNorthZVertical (x magnetic north, y west, z up).
 *    Android has no single fused sample: each row is a rotation-vector sample with the
 *    latest gravity, user acceleration, calibrated gyro and calibrated field beside it.
 */
class MotionCapture(context: Context, private val dir: File) {
    companion object {
        const val RATE_HZ = 100.0
        private const val G = 9.80665
        /** ENU -> NWU (iOS's xMagneticNorthZVertical): a rotation of -90° about z. */
        private val ENU_TO_NWU = Quat(0.0, 0.0, -sqrt(0.5), sqrt(0.5))
    }

    private val manager = context.getSystemService(SensorManager::class.java)

    /** One file and the thread its sensors deliver on. */
    private inner class Stream(val name: String, file: String, header: String) {
        val thread = HandlerThread("sensor-$name").apply { start() }
        val handler = Handler(thread.looper)
        val writer = GzipWriter(File(dir, file), header)
        @Volatile var count = 0
        @Volatile var firstLatency: Double? = null
        /** Added to a sensor's timestamps to put them on the uptime clock (0 unless the sensor uses another). */
        private val offsets = HashMap<Int, Double>()
        val bases = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val line = StringBuilder(256)

        /** Uptime of an event: SensorEvent.timestamp should be on elapsedRealtimeNanos; a sensor on the other monotonic clock is moved onto it. */
        fun uptime(e: SensorEvent): Double {
            val t = e.timestamp / 1e9
            val offset = offsets.getOrPut(e.sensor.type) {
                val boot = SystemClock.elapsedRealtimeNanos() / 1e9
                val mono = System.nanoTime() / 1e9
                when {
                    abs(boot - t) < 1.0 -> { bases[typeName(e.sensor.type)] = "boottime"; 0.0 }
                    abs(mono - t) < 1.0 -> { bases[typeName(e.sensor.type)] = "monotonic"; boot - mono }
                    else -> { bases[typeName(e.sensor.type)] = "unknown"; 0.0 }
                }
            }
            return t + offset
        }

        fun add(t: Double, vararg values: Double) {
            line.setLength(0)
            appendFixed(line, t)
            for (v in values) { line.append(','); appendFixed(line, v) }
            line.append('\n')
            writer.write(line.toString())
            if (count == 0) firstLatency = AndroidClock.uptime() - t
            count += 1
        }

        fun close() {
            handler.post { writer.close() }
            thread.quitSafely()
            thread.join(2000)
        }
    }

    private val accel = Stream("accel", "accel.csv.gz", "uptime_s,x_g,y_g,z_g\n")
    private val gyro = Stream("gyro", "gyro.csv.gz", "uptime_s,x_rad_s,y_rad_s,z_rad_s\n")
    private val mag = Stream("mag", "mag.csv.gz", "uptime_s,x_ut,y_ut,z_ut\n")
    private val baro = Stream("baro", "baro.csv.gz", "uptime_s,pressure_kpa,relative_altitude_m\n")
    private val fused = Stream(
        "device_motion", "device_motion.csv.gz",
        "uptime_s,qx,qy,qz,qw,rot_x_rad_s,rot_y_rad_s,rot_z_rad_s,gravity_x_g,gravity_y_g,gravity_z_g," +
            "user_accel_x_g,user_accel_y_g,user_accel_z_g,mag_x_ut,mag_y_ut,mag_z_ut,mag_accuracy,heading_deg\n",
    )
    private val streams = listOf(accel, gyro, mag, baro, fused)
    private var referenceFrame = "none"
    private val used = HashMap<String, Sensor>()
    private val listeners = ArrayList<SensorEventListener>()

    /** Also gets the samples the model uses, as they arrive. Set before `start`. */
    @Volatile var inference: LiveInference? = null

    private fun listen(stream: Stream, sensor: Sensor?, periodUs: Int, role: String, onEvent: (SensorEvent) -> Unit) {
        sensor ?: return
        used[role] = sensor
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) = onEvent(e)
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        listeners.add(l)
        manager.registerListener(l, sensor, periodUs, stream.handler)
    }

    fun start() {
        val period = (1_000_000 / RATE_HZ).toInt()
        val inference = inference
        listen(accel, manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), period, "accel") { e ->
            val t = accel.uptime(e)
            val (x, y, z) = Triple(-e.values[0] / G, -e.values[1] / G, -e.values[2] / G)
            accel.add(t, x, y, z)
            inference?.accel(t, x, y, z)
        }
        // raw: the uncalibrated gyro (bias in), as iOS's; the calibrated one if there's no other
        val rawGyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED) ?: manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        listen(gyro, rawGyro, period, "gyro") { e ->
            val t = gyro.uptime(e)
            val (x, y, z) = Triple(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
            gyro.add(t, x, y, z)
            inference?.gyro(t, x, y, z)
        }
        // the raw field at the sensor: the earth's plus the phone's own and whatever is nearby
        val rawMag = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) ?: manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        listen(mag, rawMag, period, "mag") { e ->
            mag.add(mag.uptime(e), e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
        }
        // about one sample a second, as iOS's
        var firstAltitude: Double? = null
        listen(baro, manager.getDefaultSensor(Sensor.TYPE_PRESSURE), 1_000_000, "baro") { e ->
            val t = baro.uptime(e)
            val hPa = e.values[0]
            val altitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa).toDouble()
            val first = firstAltitude ?: altitude.also { firstAltitude = it }
            baro.add(t, hPa / 10.0, altitude - first)
            inference?.baro(t, hPa / 10.0)
        }

        // device motion: the attitude, and beside it the latest of the other fused sensors
        // (all delivered on this stream's thread, so no locking)
        val rotation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val attitudeSensor = rotation ?: manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        referenceFrame = if (rotation != null) "xMagneticNorthZVertical" else if (attitudeSensor != null) "xArbitraryZVertical" else "none"
        var rot = Vec3()
        var gravity = Vec3(0.0, 0.0, -1.0)
        var user = Vec3()
        var field = Vec3()
        var fieldAccuracy = -1
        listen(fused, manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), period, "device_motion_rotation_rate") { e ->
            rot = Vec3(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
        }
        listen(fused, manager.getDefaultSensor(Sensor.TYPE_GRAVITY), period, "device_motion_gravity") { e ->
            gravity = Vec3(-e.values[0] / G, -e.values[1] / G, -e.values[2] / G)
        }
        listen(fused, manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), period, "device_motion_user_accel") { e ->
            user = Vec3(-e.values[0] / G, -e.values[1] / G, -e.values[2] / G)
        }
        listen(fused, manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), period, "device_motion_field") { e ->
            field = Vec3(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
            // iOS: -1 uncalibrated, 0 low, 1 medium, 2 high
            fieldAccuracy = when (e.accuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 2
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 1
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 0
                else -> -1
            }
        }
        val q4 = FloatArray(4)
        val r9 = FloatArray(9)
        val orientation = FloatArray(3)
        listen(fused, attitudeSensor, period, "device_motion_attitude") { e ->
            val t = fused.uptime(e)
            SensorManager.getQuaternionFromVector(q4, e.values)  // w, x, y, z
            val q = ENU_TO_NWU * Quat(q4[1].toDouble(), q4[2].toDouble(), q4[3].toDouble(), q4[0].toDouble())
            SensorManager.getRotationMatrixFromVector(r9, e.values)
            SensorManager.getOrientation(r9, orientation)
            val heading = (Math.toDegrees(orientation[0].toDouble()) + 360) % 360
            fused.add(t, q.x, q.y, q.z, q.w, rot.x, rot.y, rot.z, gravity.x, gravity.y, gravity.z, user.x, user.y, user.z,
                field.x, field.y, field.z, fieldAccuracy.toDouble(), heading)
            inference?.attitude(t, q, gravity)
        }
    }

    fun stop() {
        listeners.forEach { manager.unregisterListener(it) }
        listeners.clear()
        streams.forEach { it.close() }
    }

    val accelCount get() = accel.count
    val gyroCount get() = gyro.count
    val magCount get() = mag.count
    val baroCount get() = baro.count
    val fusedCount get() = fused.count

    val meta: JsonObject
        get() = buildJsonObject {
            put("requested_rate_hz", RATE_HZ)
            put("device_motion_reference_frame", referenceFrame)
            for (s in streams) {
                put("${s.name}_samples", s.count)
                put("${s.name}_first_sample_latency_s", s.firstLatency?.let(::JsonPrimitive) ?: JsonNull)
            }
            // which Android sensor each column came from, and which clock its timestamps were on
            put("sensors", buildJsonObject {
                for ((role, sensor) in used.toSortedMap()) put(role, buildJsonObject {
                    put("type", typeName(sensor.type)); put("name", sensor.name); put("vendor", sensor.vendor)
                    put("version", sensor.version); put("resolution", sensor.resolution.toDouble())
                    put("max_range", sensor.maximumRange.toDouble()); put("min_delay_us", sensor.minDelay)
                })
            })
            put("timestamp_clocks", buildJsonObject { streams.flatMap { it.bases.entries }.sortedBy { it.key }.forEach { put(it.key, it.value) } })
        }
}

private fun typeName(type: Int) = when (type) {
    Sensor.TYPE_ACCELEROMETER -> "accelerometer"
    Sensor.TYPE_GYROSCOPE -> "gyroscope"
    Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "gyroscope_uncalibrated"
    Sensor.TYPE_MAGNETIC_FIELD -> "magnetic_field"
    Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "magnetic_field_uncalibrated"
    Sensor.TYPE_PRESSURE -> "pressure"
    Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
    Sensor.TYPE_GAME_ROTATION_VECTOR -> "game_rotation_vector"
    Sensor.TYPE_GRAVITY -> "gravity"
    Sensor.TYPE_LINEAR_ACCELERATION -> "linear_acceleration"
    else -> "type_$type"
}

/** `v` with six decimals, as "%.6f" writes it (without String.format's cost at 100 Hz). */
internal fun appendFixed(sb: StringBuilder, v: Double) {
    if (v.isNaN() || v.isInfinite()) { sb.append(String.format(java.util.Locale.US, "%.6f", v)); return }
    val scaled = Math.round(abs(v) * 1_000_000.0)
    if (v < 0 && scaled != 0L) sb.append('-')
    sb.append(scaled / 1_000_000)
    sb.append('.')
    val frac = (scaled % 1_000_000).toString()
    for (i in frac.length until 6) sb.append('0')
    sb.append(frac)
}
