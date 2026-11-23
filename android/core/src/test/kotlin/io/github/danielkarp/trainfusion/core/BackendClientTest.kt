package io.github.danielkarp.trainfusion.core

import io.github.danielkarp.trainfusion.core.backend.Api
import io.github.danielkarp.trainfusion.core.backend.ApiError
import io.github.danielkarp.trainfusion.core.backend.UploadJob
import io.github.danielkarp.trainfusion.core.backend.UploadResult
import io.github.danielkarp.trainfusion.core.backend.Uploads
import io.github.danielkarp.trainfusion.core.backend.bool
import io.github.danielkarp.trainfusion.core.backend.string
import io.github.danielkarp.trainfusion.core.recording.Ride
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

/**
 * The backend client against the real backend code running locally with in-memory fakes
 * (android/tools/fake_backend.py; skipped unless TRAIN_FUSION_TEST_BACKEND is its URL):
 * sharing measurements, uploading a recording, accounts, linking, deletion requests.
 */
class BackendClientTest {
    private val base: String? = System.getenv("TRAIN_FUSION_TEST_BACKEND")?.takeIf { it.isNotBlank() }  // (the build passes "" when unset)
    private val device = "t" + Random.nextLong().toULong().toString(36).padEnd(40, 'x')
    private var token: String? = null
    private val api by lazy { Api(base, { device }, { token }) }

    private fun recording(id: String): File {
        val dir = Files.createTempDirectory("rec").toFile().resolve(id).also { it.mkdirs() }
        File(dir, "audio_000.m4a").writeBytes(Random.nextBytes(300_000))
        File(dir, "accel.csv.gz").writeBytes(Random.nextBytes(20_000))
        File(dir, "clock.csv").writeText("uptime_s,unix_s,ntp_offset_s,ntp_round_trip_s\n1,2,,\n")
        File(dir, "meta.json").writeText("{}")
        return dir
    }

    private fun id(): String = "2000%02d%02dT%06dZ".format(Random.nextInt(1, 13), Random.nextInt(1, 29), Random.nextInt(0, 235959))

    @Test
    fun sharesUploadsLinksAndAsksForDeletion() {
        assumeTrue("set TRAIN_FUSION_TEST_BACKEND to a fake backend's URL", base != null)

        // measurements, without an account
        val ride = Ride(id(), started = 1.0, ended = 2.0, appVersion = "test", stops = listOf(Ride.Stop("Example", 1.5)))
        api.call("PUT", "v1/measurements/${ride.id}", ride.apiBody(), Api.Auth.IF_SIGNED_IN, device = true)
        api.call("PUT", "v1/measurements/${ride.id}", ride.apiBody(), Api.Auth.IF_SIGNED_IN, device = true)  // replaced
        val listed = api.call("GET", "v1/measurements", device = true)["measurements"]!!.jsonArray.map { it as JsonObject }
        assertEquals(listOf(ride.id), listed.map { it.string("id") })
        assertEquals(false, listed[0].bool("linked"))

        // a recording, without an account
        val jobs = HashMap<String, UploadJob>()
        val store = object : Uploads.JobStore {
            override fun get(id: String) = jobs[id]
            override fun set(id: String, job: UploadJob?) { if (job == null) jobs.remove(id) else jobs[id] = job }
        }
        val dir = recording(id())
        var last = 0.0
        val result = Uploads(api, store).run(dir, dir.name) { last = it }
        assertEquals(UploadResult.Uploaded, result)
        assertEquals(1.0, last, 0.0)
        assertTrue(File(dir, Uploads.UPLOADED_MARKER).exists())
        assertTrue("forgotten once done", jobs.isEmpty())
        val uploads = api.call("GET", "v1/uploads", device = true)["uploads"]!!.jsonArray.map { it as JsonObject }
        assertEquals("complete", uploads.single().string("status"))
        // an upload cut off half way carries on where it was
        val dir2 = recording(id())
        val failed = Uploads(api, store).run(dir2, dir2.name) { if (it > 0.3) throw IOException("connection dropped") }
        assertTrue("$failed", failed is UploadResult.Failed && failed.network)
        assertTrue("kept to carry on with", jobs[dir2.name]!!.files.any { it.done })
        assertEquals(UploadResult.Uploaded, Uploads(api, store).run(dir2, dir2.name))

        // signing in: register, link what this phone shared before, share linked
        token = "token:u${device.take(8)}:someone@example.com"
        val created = api.call("POST", "v1/account", auth = Api.Auth.REQUIRED)
        assertEquals("true", created["created"]!!.jsonPrimitive.content)
        val claimed = api.call("POST", "v1/claim", auth = Api.Auth.REQUIRED, device = true)
        assertEquals(2, claimed["uploads"]!!.jsonPrimitive.int)
        assertEquals(1, claimed["measurements"]!!.jsonPrimitive.int)
        assertTrue(api.call("GET", "v1/measurements", device = true)["measurements"]!!.jsonArray.all { (it as JsonObject).bool("linked") == true })

        // deletion requests: measurements (then not shared again), uploads
        assertEquals(1, api.call("POST", "v1/measurements/deletion-request", auth = Api.Auth.IF_SIGNED_IN, device = true)["requested"]!!.jsonPrimitive.int)
        try {
            api.call("PUT", "v1/measurements/${ride.id}", ride.apiBody(), Api.Auth.IF_SIGNED_IN, device = true)
            fail("put while waiting to be deleted")
        } catch (e: ApiError) {
            assertEquals("deletion_pending", e.code)
            assertEquals(409, e.status)
        }
        assertEquals(2, api.call("POST", "v1/uploads/deletion-request", auth = Api.Auth.IF_SIGNED_IN, device = true)["requested"]!!.jsonPrimitive.int)

        // deleting the account
        val deleted = api.call("DELETE", "v1/account", auth = Api.Auth.REQUIRED)
        assertTrue(deleted.containsKey("uploads_pending_deletion"))
        try {
            api.call("POST", "v1/claim", auth = Api.Auth.REQUIRED, device = true)
            fail("claim after deleting the account")
        } catch (e: ApiError) {
            assertEquals("not_registered", e.code)
        }
    }

    @Test
    fun errorsCarryTheServersCodeAndMessage() {
        assumeTrue("set TRAIN_FUSION_TEST_BACKEND to a fake backend's URL", base != null)
        try {
            Api(base, { "short" }, { null }).call("GET", "v1/uploads", device = true)
            fail("a bad device secret")
        } catch (e: ApiError) {
            assertEquals(400, e.status)
            assertEquals("bad_device_secret", e.code)
            assertTrue(e.message.isNotBlank())
        }
        try {
            Api("http://127.0.0.1:9", { device }, { null }).call("GET", "v1/uploads", device = true)
            fail("no server")
        } catch (e: ApiError) {
            assertEquals(0, e.status)
            assertEquals("network", e.code)
        }
    }
}
