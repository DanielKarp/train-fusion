package io.github.danielkarp.trainfusion.recording

import io.github.danielkarp.trainfusion.core.recording.Ntp
import io.github.danielkarp.trainfusion.core.recording.NtpSample
import io.github.danielkarp.trainfusion.platform.AndroidClock
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Writes clock.csv (ios/TrainRecorder/Clock.swift's ClockLog): every 10 s, what the system
 * clock reads at an uptime; and, at the start, every 2 min and at the end, an NTP
 * measurement of how far off the system clock is.
 */
class ClockLog(dir: File) {
    companion object {
        const val HEADER = "uptime_s,unix_s,ntp_offset_s,ntp_round_trip_s\n"
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "clock-log") }
    private var out: FileOutputStream? = FileOutputStream(File(dir, "clock.csv")).also { it.write(HEADER.toByteArray()) }
    private var ticks = 0
    private var timer: ScheduledFuture<*>? = null
    @Volatile var latestNTP: NtpSample? = null
        private set

    init {
        timer = executor.scheduleWithFixedDelay({ tick() }, 0, 10, TimeUnit.SECONDS)
    }

    private fun tick() {
        val (uptime, unix) = AndroidClock.now()
        write(String.format(Locale.US, "%.6f,%.6f,,\n", uptime, unix))
        // NTP on the first three ticks, then every 2 minutes
        if (ticks < 3 || ticks % 12 == 0) measure()
        ticks += 1
    }

    private fun measure() {
        val s = Ntp.query(AndroidClock) ?: return
        latestNTP = s
        write(String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f\n", s.uptime, s.unix, s.offset, s.roundTrip))
    }

    private fun write(row: String) {
        try { out?.write(row.toByteArray()) } catch (_: Exception) {}  // unbuffered: each row is on disk at once
    }

    /** Stops the periodic rows and closes the file; with `finalNTP`, after one last NTP measurement (up to 3 s). Blocking. */
    fun stop(finalNTP: Boolean) {
        timer?.cancel(false)
        try {
            executor.submit {
                if (finalNTP) measure()
                val (uptime, unix) = AndroidClock.now()
                write(String.format(Locale.US, "%.6f,%.6f,,\n", uptime, unix))
                try { out?.close() } catch (_: Exception) {}
                out = null
            }.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        executor.shutdown()
    }
}
