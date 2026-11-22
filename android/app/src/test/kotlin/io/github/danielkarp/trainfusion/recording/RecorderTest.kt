package io.github.danielkarp.trainfusion.recording

import android.Manifest
import android.content.Intent
import android.media.MediaFormat
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.danielkarp.trainfusion.TrainFusionApp
import io.github.danielkarp.trainfusion.core.recording.Ride
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowMediaCodec
import java.io.File
import kotlin.random.Random

/**
 * A whole recording, start to stop, through the real Recorder, AudioCapture, MotionCapture
 * and ClockLog, with Robolectric's stand-ins for the microphone (noise, in real time) and the
 * AAC encoder (a fixed-size "access unit" per input buffer). What a phone does underneath
 * isn't tested here; that the app's own code gets through a recording and its stop is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecorderTest {
    @Test
    fun aRecordingStartsStopsAndIsComplete() {
        ShadowMediaCodec.addEncoder(MediaFormat.MIMETYPE_AUDIO_AAC, ShadowMediaCodec.CodecConfig(16384, 4096) { input, output ->
            if (input.hasRemaining()) {
                input.position(input.limit())
                output.put(ByteArray(200) { 0x21 })
            }
        })
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInFloatArray(audioData: FloatArray, offsetInFloats: Int, sizeInFloats: Int, isBlocking: Boolean): Int {
                Thread.sleep(sizeInFloats * 1000L / 48000)  // as a microphone: a buffer's worth of time
                for (i in 0 until sizeInFloats) audioData[offsetInFloats + i] = 0.01f * (Random.nextFloat() - 0.5f)
                return sizeInFloats
            }
        })
        val app = ApplicationProvider.getApplicationContext<TrainFusionApp>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val recorder = app.recorder

        runBlocking { recorder.start() }
        assertNull(recorder.error)
        val id = recorder.activeID
        assertNotNull(id)
        // the notification and the wake lock (what the recorder asked for)
        val started = shadowOf(app).nextStartedService
        assertEquals(RecordingService::class.java.name, started.component?.className)
        Robolectric.buildService(RecordingService::class.java, started).create().startCommand(0, 1)

        Thread.sleep(2500)
        recorder.setRide(io.github.danielkarp.trainfusion.core.recording.RideInfo(123, 312, io.github.danielkarp.trainfusion.core.recording.RideInfo.Position.UPPER))
        runBlocking { recorder.stop() }
        shadowOf(Looper.getMainLooper()).idle()

        assertNull("no error: ${recorder.error}", recorder.error)
        assertNull(recorder.activeID)
        val dir = File(app.store.root, id!!)
        val meta = Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
        assertEquals("true", meta["complete"]!!.jsonPrimitive.content)
        val segment = meta["audio"]!!.jsonObject["segments"]!!.jsonArray[0].jsonObject
        assertEquals("audio_000.m4a", segment["file"]!!.jsonPrimitive.content)
        assertTrue("frames: $segment", segment["frames"]!!.jsonPrimitive.long > 48000)
        assertTrue(File(dir, "audio_000.m4a").length() > 0)
        for (f in listOf("audio_timing.csv.gz", "accel.csv.gz", "gyro.csv.gz", "mag.csv.gz", "baro.csv.gz", "device_motion.csv.gz", "clock.csv")) {
            assertTrue(f, File(dir, f).exists())
        }
        // the ride, and the question about uploading it
        val ride = app.store.ride(id)!!
        assertTrue(ride.stoppedProperly)
        assertEquals(Ride.Train(123, 312, "upper"), ride.train)
        assertEquals(Ride.Upload.UNDECIDED, ride.upload)
        assertEquals(id, app.consent?.rideID)

        // the notification's Stop with nothing recording does nothing
        Robolectric.buildService(RecordingService::class.java, Intent(app, RecordingService::class.java).setAction("stop")).create().startCommand(0, 2)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
