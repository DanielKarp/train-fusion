package io.github.danielkarp.trainfusion.core.recording

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * Time. Every measurement in a recording is stamped with `uptime`: seconds on the phone's
 * monotonic clock (on Android, SystemClock.elapsedRealtimeNanos, the clock the sensors and
 * AudioRecord's BOOTTIME timestamps use). [ClockSource] reads it and the wall clock;
 * ClockLog (in the app) records how they map.
 */
interface ClockSource {
    fun uptime(): Double
    fun unix(): Double

    /** The monotonic clock and the system's wall clock (unix seconds), read together. */
    fun now(): Pair<Double, Double> {
        val before = uptime()
        val unix = unix()
        val after = uptime()
        return (before + after) / 2 to unix
    }
}

/** One NTP exchange: at `uptime` the system clock read `unix`, and true time was `unix + offset` (to within about `roundTrip / 2`). */
data class NtpSample(val uptime: Double, val unix: Double, val offset: Double, val roundTrip: Double)

object Ntp {
    /** As the iOS app, so recordings from both say the same. */
    const val SERVER = "time.apple.com"
    private val random = SecureRandom()

    /** Client request: version 4, mode 3, and 8 random bytes as the transmit timestamp. */
    fun request(): ByteArray {
        val r = ByteArray(48)
        r[0] = 0x23
        val nonce = ByteArray(8).also(random::nextBytes)
        System.arraycopy(nonce, 0, r, 40, 8)
        return r
    }

    /**
     * The reply to `request`, sent at (sentUptime, sentUnix) and received at
     * `receivedUptime`; null if it isn't a good one.
     */
    fun parse(request: ByteArray, reply: ByteArray, length: Int, sentUptime: Double, sentUnix: Double, receivedUptime: Double): NtpSample? {
        if (length < 48) return null
        if (reply[0].toInt() and 0x07 != 4) return null  // mode: server
        if (reply[1].toInt() and 0xff !in 1..15) return null  // stratum 0 is "go away"
        // the server echoes our transmit timestamp as its "originate" field
        for (i in 0 until 8) if (reply[24 + i] != request[40 + i]) return null
        val serverReceived = timestamp(reply, 32)
        val serverSent = timestamp(reply, 40)
        val elapsed = receivedUptime - sentUptime
        val unixAtReceive = sentUnix + elapsed
        return NtpSample(
            uptime = receivedUptime,
            unix = unixAtReceive,
            offset = ((serverReceived - sentUnix) + (serverSent - unixAtReceive)) / 2,
            roundTrip = elapsed - (serverSent - serverReceived),
        )
    }

    /** One SNTP query (blocking, up to `timeoutMs`); null on timeout or a bad reply. */
    fun query(clock: ClockSource, server: String = SERVER, timeoutMs: Int = 3000): NtpSample? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(server)
            val request = request()
            val (sentUptime, sentUnix) = clock.now()
            socket.send(DatagramPacket(request, request.size, address, 123))
            val buf = ByteArray(128)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            parse(request, buf, packet.length, sentUptime, sentUnix, clock.uptime())
        }
    } catch (_: SocketTimeoutException) {
        null
    } catch (_: Exception) {
        null
    }

    /** A 64-bit NTP timestamp (seconds since 1900, 32.32 fixed point) as unix seconds. */
    fun timestamp(b: ByteArray, at: Int): Double {
        var seconds = 0L
        var fraction = 0L
        for (i in 0 until 4) seconds = (seconds shl 8) or (b[at + i].toLong() and 0xff)
        for (i in 4 until 8) fraction = (fraction shl 8) or (b[at + i].toLong() and 0xff)
        return seconds.toDouble() - 2_208_988_800.0 + fraction.toDouble() / 4_294_967_296.0
    }
}
