package io.github.danielkarp.trainfusion.core.inference

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Power spectrum of a real signal, |X_k|² for k = 0...n/2, n a power of two. The iOS app
 * uses vDSP; this is a plain radix-2 FFT (in double precision, so it agrees with numpy at
 * least as well as vDSP's single precision does).
 */
class PowerFFT(val n: Int) {
    private val re = DoubleArray(n)
    private val im = DoubleArray(n)
    private val cosT: DoubleArray
    private val sinT: DoubleArray
    private val rev: IntArray

    init {
        require(n > 0 && n and (n - 1) == 0)
        cosT = DoubleArray(n / 2) { cos(-2 * PI * it / n) }
        sinT = DoubleArray(n / 2) { sin(-2 * PI * it / n) }
        val bits = Integer.numberOfTrailingZeros(n)
        rev = IntArray(n) { Integer.reverse(it) ushr (32 - bits) }
        if (n == 1) rev[0] = 0
    }

    /** `x`: n samples (already windowed / zero-padded). `out`: n/2 + 1 values. */
    fun power(x: FloatArray, out: FloatArray) {
        for (i in 0 until n) {
            re[rev[i]] = x[i].toDouble()
            im[rev[i]] = 0.0
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val c = cosT[k]
                    val s = sinT[k]
                    val l = j + half
                    val tr = re[l] * c - im[l] * s
                    val ti = re[l] * s + im[l] * c
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
        for (k in 0..n / 2) out[k] = (re[k] * re[k] + im[k] * im[k]).toFloat()
    }
}

/**
 * scipy.signal.spectrogram's power spectral density in dB for one segment: mean removed,
 * windowed, zero-padded to nfft; 10 log10(density + 1e-16).
 */
class SegmentSpectrum(private val window: FloatArray, nfft: Int, fs: Float) {
    private val fft = PowerFFT(nfft)
    private val scale: Float
    private val buf = FloatArray(nfft)
    private val pow = FloatArray(nfft / 2 + 1)
    val bins = nfft / 2 + 1

    init {
        var sumSq = 0f
        for (w in window) sumSq += w * w
        scale = 1 / (fs * sumSq)
    }

    /** dB of the first `out.size` bins of the segment x[offset ..< offset + window.size]. */
    fun db(x: FloatArray, offset: Int, out: FloatArray) {
        val m = window.size
        var mean = 0.0
        for (i in 0 until m) mean += x[offset + i]
        val mu = (mean / m).toFloat()
        for (i in 0 until m) buf[i] = (x[offset + i] - mu) * window[i]
        for (i in m until buf.size) buf[i] = 0f
        fft.power(buf, pow)
        val last = bins - 1
        for (k in out.indices) {
            val s = pow[k] * scale * (if (k == 0 || k == last) 1 else 2)
            out[k] = 10 * log10(s + 1e-16f)
        }
    }
}

/** A cascade of biquads (scipy's "sos" rows: b0 b1 b2 a0 a1 a2), run sample by sample. */
class SOSFilter(private val c: List<DoubleArray>) {
    private val z1 = DoubleArray(c.size)
    private val z2 = DoubleArray(c.size)

    fun step(x: Double): Double {
        var y = x
        for (s in c.indices) {
            val b = c[s]
            val out = b[0] * y + z1[s]
            z1[s] = b[1] * y - b[4] * out + z2[s]
            z2[s] = b[2] * y - b[5] * out
            y = out
        }
        return y
    }
}

object Filters {
    /** Running median over `size` (odd) neighbours, the edges repeating the end values. */
    fun median(x: FloatArray, size: Int, out: FloatArray) {
        val n = x.size
        val h = size / 2
        val win = FloatArray(size)
        for (k in -h..h) win[k + h] = x[(k).coerceIn(0, n - 1)]
        win.sort()
        out[0] = win[h]
        for (i in 1 until n) {
            val leaving = x[(i - 1 - h).coerceIn(0, n - 1)]
            val entering = x[(i + h).coerceAtMost(n - 1)]
            if (leaving != entering) {
                // remove `leaving`, then insert `entering`, keeping the window sorted
                val at = lowerBound(win, size, leaving)
                System.arraycopy(win, at + 1, win, at, size - at - 1)
                val to = lowerBound(win, size - 1, entering)
                System.arraycopy(win, to, win, to + 1, size - 1 - to)
                win[to] = entering
            }
            out[i] = win[h]
        }
    }

    private fun lowerBound(a: FloatArray, count: Int, v: Float): Int {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (a[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Running maximum over `size` (odd) neighbours, the edges mirrored (d c b a | a b c d). */
    fun maximum(x: FloatArray, size: Int, out: FloatArray) {
        val n = x.size
        val h = size / 2
        for (i in 0 until n) {
            var m = Float.NEGATIVE_INFINITY
            for (k in (i - h)..(i + h)) {
                val j = if (k < 0) -k - 1 else if (k >= n) 2 * n - 1 - k else k
                if (x[j] > m) m = x[j]
            }
            out[i] = m
        }
    }
}

/** A growable float buffer that can drop its front. */
class FloatBuf(capacity: Int = 1024) {
    var data = FloatArray(capacity)
        private set
    var size = 0
        private set

    operator fun get(i: Int) = data[i]

    fun append(x: FloatArray, offset: Int = 0, count: Int = x.size - offset) {
        ensure(size + count)
        System.arraycopy(x, offset, data, size, count)
        size += count
    }

    fun append(v: Float) {
        ensure(size + 1)
        data[size++] = v
    }

    fun appendZeros(count: Int) {
        ensure(size + count)
        java.util.Arrays.fill(data, size, size + count, 0f)
        size += count
    }

    fun removeFirst(count: Int) {
        val k = count.coerceAtMost(size)
        System.arraycopy(data, k, data, 0, size - k)
        size -= k
    }

    private fun ensure(n: Int) {
        if (n > data.size) data = data.copyOf(maxOf(n, data.size * 2))
    }
}

/** 48 kHz -> 16 kHz: low-pass FIR, keep every third sample; fed in pieces. */
class Decimator(private val taps: FloatArray) {
    private val pending = FloatBuf(65536)

    fun process(x: FloatArray, offset: Int, count: Int, out: FloatBuf) {
        pending.append(x, offset, count)
        val n = (pending.size - taps.size) / 3 + 1
        if (pending.size < taps.size || n <= 0) return
        val p = pending.data
        // vDSP_desamp: out[k] = sum_p x[3k + p] * taps[p]
        for (k in 0 until n) {
            var s = 0f
            val base = 3 * k
            for (j in taps.indices) s += p[base + j] * taps[j]
            out.append(s)
        }
        pending.removeFirst(3 * n)
    }
}
