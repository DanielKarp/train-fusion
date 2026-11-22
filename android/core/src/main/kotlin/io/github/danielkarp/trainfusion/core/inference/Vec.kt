package io.github.danielkarp.trainfusion.core.inference

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** The bits of Swift's simd the model needs: 3-vectors, 3×3 matrices, quaternions. */
data class Vec3(val x: Double = 0.0, val y: Double = 0.0, val z: Double = 0.0) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    operator fun get(i: Int) = when (i) { 0 -> x; 1 -> y; else -> z }
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val lengthSquared: Double get() = this dot this
    val length: Double get() = sqrt(lengthSquared)
    fun normalized() = this / length
}

/** Row-major 3×3. */
class Mat3(val m: DoubleArray = DoubleArray(9)) {
    /** this += a bᵀ */
    fun addOuter(a: Vec3, b: Vec3) {
        for (r in 0..2) for (c in 0..2) m[3 * r + c] += a[r] * b[c]
    }

    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )
}

/** A quaternion, as simd_quatd(ix:iy:iz:r:). */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    val conjugate: Quat get() = Quat(-x, -y, -z, w)

    val inverse: Quat
        get() {
            val n = x * x + y * y + z * z + w * w
            return Quat(-x / n, -y / n, -z / n, w / n)
        }

    /** simd_act: rotates `v` by this quaternion (q v q⁻¹). */
    fun act(v: Vec3): Vec3 {
        val r = this * Quat(v.x, v.y, v.z, 0.0) * inverse
        return Vec3(r.x, r.y, r.z)
    }
}

/** Swift's `.rounded()`: to nearest, halves away from zero (Kotlin's round() goes to even). */
fun roundHalfAway(x: Double): Double = if (x >= 0) floor(x + 0.5) else ceil(x - 0.5)

/** The index of the first largest element, as Swift's `indices.max(by:)` finds it. */
inline fun argmaxFirst(n: Int, value: (Int) -> Double): Int {
    var best = 0
    for (i in 1 until n) if (value(best) < value(i)) best = i
    return best
}

/** The index of the first smallest element, as Swift's `min(by:)` finds it. */
inline fun argminFirst(n: Int, value: (Int) -> Double): Int {
    var best = 0
    for (i in 1 until n) if (value(i) < value(best)) best = i
    return best
}

internal fun Double.absValue() = abs(this)
