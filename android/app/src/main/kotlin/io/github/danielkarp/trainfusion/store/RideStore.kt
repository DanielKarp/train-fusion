package io.github.danielkarp.trainfusion.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.danielkarp.trainfusion.TrainFusionApp
import io.github.danielkarp.trainfusion.core.backend.Api
import io.github.danielkarp.trainfusion.core.backend.ApiError
import io.github.danielkarp.trainfusion.core.backend.Uploads
import io.github.danielkarp.trainfusion.core.backend.bool
import io.github.danielkarp.trainfusion.core.backend.string
import io.github.danielkarp.trainfusion.core.recording.KeepRecordings
import io.github.danielkarp.trainfusion.core.recording.MAX_UPLOAD_BYTES
import io.github.danielkarp.trainfusion.core.recording.RecordingID
import io.github.danielkarp.trainfusion.core.recording.Ride
import io.github.danielkarp.trainfusion.platform.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.io.File
import java.util.Date

/** A recording's files on the phone (recordings/<id>/; docs/recorder.md). */
data class Recording(
    val id: String,  // the directory name: the start time, UTC
    val dir: File,
    val started: Date?,
    val duration: Double?,  // null if it was never stopped properly
    val bytes: Long,
) {
    val tooLargeToUpload: Boolean get() = bytes > MAX_UPLOAD_BYTES
}

/**
 * The rides on the phone (files/rides.json) and their recordings: which to keep, uploading
 * them (through [Uploader]) and sharing their Train Measurements. As the iOS app's RideStore;
 * its state is read by the screens and changed on the main thread.
 */
class RideStore(private val app: TrainFusionApp) {
    val root: File = Storage.recordings(app)
    private val file = File(app.filesDir, "rides.json")

    var rides by mutableStateOf<List<Ride>>(emptyList())  // newest first
        private set
    var recordings by mutableStateOf<Map<String, Recording>>(emptyMap())
        private set
    var sharing by mutableStateOf(false)
        private set
    var shareMessage by mutableStateOf<String?>(null)

    init {
        load()
        app.uploader.onFinished = { id, outcome -> uploadFinished(id, outcome) }
    }

    /** Reads rides.json. */
    fun load() {
        rides = try { if (file.exists()) Ride.decodeList(file.readText()) else emptyList() } catch (_: Exception) { emptyList() }
    }

    private fun save() {
        rides = rides.sortedByDescending { it.id }
        try {
            val tmp = File(file.parentFile, "rides.json.tmp")
            tmp.writeText(Ride.encodeList(rides))
            tmp.renameTo(file)
        } catch (_: Exception) {
        }
    }

    fun ride(id: String): Ride? = rides.firstOrNull { it.id == id }

    private fun update(id: String, change: (Ride) -> Ride) {
        if (rides.none { it.id == id }) return
        rides = rides.map { if (it.id == id) change(it) else it }
        save()
    }

    /**
     * Rescan the disk. `active`: the recording in progress, which isn't a ride yet.
     * Recordings without a ride (not stopped properly, or copied onto the phone) get one.
     */
    fun refresh(excluding: String?) {
        val found = HashMap<String, Recording>()
        val list = rides.toMutableList()
        for (dir in root.listFiles().orEmpty()) {
            if (!dir.isDirectory || dir.name == excluding || dir.name.startsWith(".")) continue
            val id = dir.name
            val bytes = dir.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }
            var ride = list.firstOrNull { it.id == id } ?: Ride.read(dir, id)
            val duration = if (ride.stoppedProperly) ride.ended?.let { e -> ride.started?.let { e - it } } else null
            val recording = Recording(id, dir, RecordingID.date(id), duration, bytes)
            found[id] = recording
            if (File(dir, Uploads.UPLOADED_MARKER).exists()) {
                ride = ride.copy(upload = Ride.Upload.UPLOADED)
            } else if (recording.tooLargeToUpload && ride.upload != Ride.Upload.UPLOADED) {
                ride = ride.copy(upload = Ride.Upload.TOO_LARGE)
            }
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) list[i] = ride else list.add(ride)
        }
        recordings = found
        rides = list
        save()
    }

    /**
     * A recording has just been stopped: make its ride, decide about uploading (the caller
     * asks if it's undecided), and share its measurements if sharing is on.
     */
    fun rideEnded(id: String): Ride? {
        refresh(excluding = null)
        val recording = recordings[id] ?: return null
        if (ride(id) == null) return null
        var ride = Ride.read(recording.dir, id)
        ride = ride.copy(upload = when {
            recording.tooLargeToUpload -> Ride.Upload.TOO_LARGE
            app.settings.askToUpload -> Ride.Upload.UNDECIDED
            else -> Ride.Upload.DECLINED
        })
        if (app.settings.takeShareOnce(id) || app.settings.shareMeasurements) ride = ride.copy(sharePending = true)
        update(id) { ride }
        if (ride.sharePending == true) app.scope.launch { share(listOf(id)) }
        return ride
    }

    /** The rider answered: upload or not. */
    fun decide(id: String, upload: Boolean, protecting: Set<String>) {
        update(id) { it.copy(upload = if (upload) Ride.Upload.WANTED else Ride.Upload.DECLINED) }
        if (upload) recordings[id]?.let { app.uploader.start(it) }
        prune(protecting)
    }

    private fun uploadFinished(id: String, outcome: Uploader.Outcome) {
        update(id) { it.copy(upload = if (outcome == Uploader.Outcome.UPLOADED) Ride.Upload.UPLOADED else Ride.Upload.TOO_LARGE) }
        prune(emptySet())
    }

    /** Start (or retry) every upload the rider asked for that isn't done, e.g. after a launch. */
    fun resumeUploads() {
        for (ride in rides) if (ride.upload == Ride.Upload.WANTED) recordings[ride.id]?.let { app.uploader.start(it, automatically = true) }
    }

    /** Deletes the ride from the phone: its recording and its timings. */
    fun delete(ride: Ride) {
        recordings[ride.id]?.dir?.deleteRecursively()
        app.uploader.forget(ride.id)
        recordings = recordings - ride.id
        rides = rides.filter { it.id != ride.id }
        save()
    }

    // MARK: keeping recordings

    /** The recordings `keep` would delete now: the settled ones beyond the newest N. */
    fun prunable(keep: KeepRecordings, protecting: Set<String>): List<Recording> {
        val count = keep.count ?: return emptyList()
        val withRecordings = rides.filter { recordings[it.id] != null }  // newest first
        return withRecordings.mapIndexedNotNull { i, ride ->
            if (i >= count && ride.settled && ride.id !in protecting) recordings[ride.id] else null
        }
    }

    /** Deletes what the "Keep recordings" setting says to. `protecting`: e.g. the one being replayed. */
    fun prune(protecting: Set<String>) {
        for (recording in prunable(app.settings.keepRecordings, protecting)) {
            recording.dir.deleteRecursively()
            recordings = recordings - recording.id
        }
    }

    // MARK: sharing Train Measurements

    /** Share these rides' measurements (linked to the account if signed in). */
    suspend fun share(ids: List<String>) {
        if (ids.isEmpty()) return
        sharing = true
        shareMessage = null
        try {
            for (id in ids) {
                val ride = ride(id) ?: continue
                try {
                    withContext(Dispatchers.IO) { app.account.api.call("PUT", "v1/measurements/$id", ride.apiBody(), Api.Auth.IF_SIGNED_IN, device = true) }
                    update(id) { it.copy(sharedAt = System.currentTimeMillis() / 1000.0, sharePending = null) }
                } catch (e: ApiError) {
                    if (e.code == "deletion_pending") {
                        update(id) { it.copy(sharePending = null) }  // waiting to be deleted: not shared again
                    } else {
                        // left pending: tried again when the app is next opened
                        shareMessage = "Couldn't share: ${e.message}"
                        return
                    }
                }
            }
        } finally {
            sharing = false
        }
    }

    /** Rides that ended with sharing on but haven't been shared yet. */
    suspend fun sharePendingMeasurements() {
        if (!app.settings.shareMeasurements) return
        share(rides.filter { it.sharePending == true && it.sharedAt == null }.map { it.id })
    }

    /**
     * Asks for every measurement shared from this phone or linked to the account to be
     * deleted (the developers do it by hand, within 30 days). Returns how many, or null if it
     * failed (`shareMessage` says why).
     */
    suspend fun deleteShared(): Int? {
        sharing = true
        shareMessage = null
        try {
            val body = withContext(Dispatchers.IO) {
                app.account.api.call("POST", "v1/measurements/deletion-request", auth = Api.Auth.IF_SIGNED_IN, device = true)
            }
            rides = rides.map { it.copy(sharedAt = null, sharePending = null) }
            save()
            return body["requested"]?.jsonPrimitive?.int ?: 0
        } catch (e: ApiError) {
            shareMessage = "Couldn't delete: ${e.message}"
            return null
        } finally {
            sharing = false
        }
    }

    /**
     * Marks as shared exactly the rides the server still has from this phone, not waiting to
     * be deleted (e.g. after the account, and so its measurements, were deleted).
     */
    suspend fun reconcileShared() {
        val body = try {
            withContext(Dispatchers.IO) { app.account.api.call("GET", "v1/measurements", device = true) }
        } catch (_: ApiError) {
            return
        }
        val found = (body["measurements"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return
        val ids = found.filter { it.bool("deletion_requested") != true }.mapNotNull { it.string("id") }.toSet()
        rides = rides.map { if (it.id in ids) it else it.copy(sharedAt = null) }
        save()
    }
}
