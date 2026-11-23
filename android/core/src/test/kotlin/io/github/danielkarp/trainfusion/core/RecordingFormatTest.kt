package io.github.danielkarp.trainfusion.core

import io.github.danielkarp.trainfusion.core.recording.GzipCsv
import io.github.danielkarp.trainfusion.core.recording.GzipWriter
import io.github.danielkarp.trainfusion.core.recording.Ntp
import io.github.danielkarp.trainfusion.core.recording.RecordingID
import io.github.danielkarp.trainfusion.core.recording.Ride
import io.github.danielkarp.trainfusion.core.recording.RideInfo
import io.github.danielkarp.trainfusion.core.recording.formatBytes
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Date

class RecordingFormatTest {
    private fun tmp() = Files.createTempDirectory("rec").toFile()

    @Test
    fun gzipCsvKeepsWhatAFileCutShortHas() {
        val dir = tmp()
        val f = File(dir, "accel.csv.gz")
        val w = GzipWriter(f, "uptime_s,x_g,y_g,z_g\n", now = { 0.0 })
        for (i in 0 until 1000) w.write("$i.5,1,2,3\n")
        w.close()
        assertEquals(4000, GzipCsv.read(f, intArrayOf(0, 1, 2, 3)).size)
        assertArrayEquals(doubleArrayOf(0.5, 3.0, 1.5, 3.0), GzipCsv.read(f, intArrayOf(0, 3)).copyOf(4), 0.0)
        // killed mid-write: the end is missing, and so is half of the last line
        val bytes = f.readBytes()
        File(dir, "cut.csv.gz").writeBytes(bytes.copyOf(bytes.size - 9))
        val cut = GzipCsv.read(File(dir, "cut.csv.gz"), intArrayOf(0, 1, 2, 3))
        assertEquals(0, cut.size % 4)
        assertTrue("most rows survive: ${cut.size / 4}", cut.size / 4 >= 950)
        // a missing file is an empty one (older recordings lack some sensors)
        assertEquals(0, GzipCsv.read(File(dir, "nope.csv.gz"), intArrayOf(0)).size)
        dir.deleteRecursively()
    }

    @Test
    fun gzipWriterFlushesSoAKilledAppLeavesData() {
        val dir = tmp()
        val f = File(dir, "gyro.csv.gz")
        var t = 0.0
        val w = GzipWriter(f, "uptime_s,x\n", now = { t })
        for (i in 0 until 100) w.write("$i,0\n")
        t = 6.0  // more than 5 s later: flushed
        w.write("100,0\n")
        // not closed: the app was killed
        assertEquals(101 * 2, GzipCsv.read(f, intArrayOf(0, 1)).size)
        w.close()
        dir.deleteRecursively()
    }

    @Test
    fun recordingIds() {
        // (made up)
        assertEquals("20000102T030405Z", RecordingID.make(Date(946782245_000L)))
        assertEquals(946782245_000L, RecordingID.date("20000102T030405Z")!!.time)
        assertNull(RecordingID.date("20000102T030405Z-3f9a0c1d"))
    }

    @Test
    fun byteCounts() {
        assertEquals("12 bytes", formatBytes(12))
        assertEquals("980 KB", formatBytes(980_000))
        assertEquals("45.2 MB", formatBytes(45_234_567))
        assertEquals("200 MB", formatBytes(200_000_000))
        assertEquals("You already have an account with a@example.com. Sign in with Apple and Google, as you did before.",
            io.github.danielkarp.trainfusion.core.backend.Identity.alreadyRegistered("a@example.com", listOf("google.com", "apple.com", "google.com")))
        assertEquals("You already have an account with this email. Sign in the way you did before.",
            io.github.danielkarp.trainfusion.core.backend.Identity.alreadyRegistered(null, listOf("phone")))
        assertEquals("1.2 GB", formatBytes(1_200_000_000))
        assertEquals("1.23 GB", formatBytes(1_234_000_000))
    }

    @Test
    fun rideInfoProblems() {
        assertNull(RideInfo.problem("123", "312"))
        assertNull(RideInfo.problem("901", "3012"))
        assertNull(RideInfo.problem("401", "312"))  // limited
        assertNull(RideInfo.problem("502", "312"))  // express
        assertNull(RideInfo.problem("601", "312"))  // weekend local
        assertNotNull(RideInfo.problem("805", "312"))  // South County: outside the project
        assertNotNull(RideInfo.problem("223", "312"))
        assertNotNull(RideInfo.problem("312", "312"))  // a car number in the trip's place
        assertNotNull(RideInfo.problem("12", "312"))
        assertNotNull(RideInfo.problem("123", "412"))
        assertNotNull(RideInfo.problem("１２３", "312"))  // not ASCII digits
    }

    @Test
    fun rideFromMetaUsesTheBestNtpRow() {
        val dir = tmp()
        File(dir, "clock.csv").writeText(
            "uptime_s,unix_s,ntp_offset_s,ntp_round_trip_s\n" +
                "100.000000,1000.000000,,\n" +
                "101.000000,1001.000000,0.500000,0.200000\n" +
                "102.000000,1002.000000,0.250000,0.020000\n",
        )
        File(dir, "meta.json").writeText(
            """{"started_uptime_s": 100.0, "started_unix_s": 1000.0, "ended_uptime_s": 700.0, "complete": true,
               "app_version": "0.1 (1)", "ride": {"trip_number": 123, "car_number": 312, "car_position": "upper"},
               "inferred": {"direction": "southbound", "station_confidence": 0.95, "stops": [
                 {"station": "Example", "doors_opened_uptime_s": 200.0, "doors_closed_uptime_s": 230.0},
                 {"station": "Other", "doors_opened_uptime_s": 400.0, "doors_closed_uptime_s": null}]}}""",
        )
        val ride = Ride.read(dir, "20000102T030405Z")
        assertEquals(1000.25, ride.started!!, 1e-9)  // 100 + (1002 + 0.25 - 102)
        assertEquals(1600.25, ride.ended!!, 1e-9)
        assertTrue(ride.stoppedProperly)
        assertEquals(Ride.Train(123, 312, "upper"), ride.train)
        assertEquals(1100.25, ride.stops[0].doorsOpened, 1e-9)
        assertNull(ride.stops[1].doorsClosed)
        assertEquals("Example → Other", ride.title)
        val body = ride.apiBody()
        assertEquals("southbound", body["direction"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["stops"]!!.jsonArray[1].jsonObject["doors_closed_unix_s"])
        assertEquals(312, body["train"]!!.jsonObject["car_number"]!!.jsonPrimitive.content.toInt())
        // and it survives rides.json
        assertEquals(listOf(ride), Ride.decodeList(Ride.encodeList(listOf(ride))))
        assertEquals("Unknown ride", Ride("x").title)
        assertEquals("Sample ride", Ride("x", sample = true).title)
        // the short names, as History shows them
        assertEquals("San Francisco → San Jose", Ride("x", stationConfidence = 0.95,
            stops = listOf(Ride.Stop("San Francisco 4th & King Street", 1.0), Ride.Stop("San Jose Diridon", 2.0))).title)
        assertEquals("A (unsure)", Ride("x", stops = listOf(Ride.Stop("A", 1.0)), stationConfidence = 0.5).title)
        dir.deleteRecursively()
    }

    @Test
    fun ntpReply() {
        val request = Ntp.request()
        val reply = ByteArray(48)
        reply[0] = 0x24  // version 4, mode 4 (server)
        reply[1] = 2  // stratum
        System.arraycopy(request, 40, reply, 24, 8)
        // the server received at unix 1000.5 and sent at 1000.6 (seconds since 1900)
        fun put(at: Int, unix: Double) {
            val ntp = unix + 2_208_988_800.0
            val s = ntp.toLong()
            val frac = ((ntp - s) * 4_294_967_296.0).toLong()
            for (i in 0 until 4) reply[at + i] = (s shr (24 - 8 * i)).toByte()
            for (i in 0 until 4) reply[at + 4 + i] = (frac shr (24 - 8 * i)).toByte()
        }
        put(32, 1000.5)
        put(40, 1000.6)
        // sent at uptime 10 (clock 1000.0), received at uptime 10.3
        val s = Ntp.parse(request, reply, 48, 10.0, 1000.0, 10.3)!!
        assertEquals(10.3, s.uptime, 1e-9)
        assertEquals(1000.3, s.unix, 1e-6)
        assertEquals(0.2, s.roundTrip, 1e-6)  // 0.3 elapsed, 0.1 at the server
        assertEquals(0.4, s.offset, 1e-6)  // ((1000.5 - 1000) + (1000.6 - 1000.3)) / 2
        // a reply to someone else's request
        reply[24] = (reply[24] + 1).toByte()
        assertNull(Ntp.parse(request, reply, 48, 10.0, 1000.0, 10.3))
    }
}
