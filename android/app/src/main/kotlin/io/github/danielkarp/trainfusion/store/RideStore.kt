package io.github.danielkarp.trainfusion.store

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.danielkarp.trainfusion.TrainFusionApp
import io.github.danielkarp.trainfusion.core.backend.Api
import io.github.danielkarp.trainfusion.core.backend.ApiError
import io.github.danielkarp.trainfusion.core.backend.Uploads
import io.github.danielkarp.trainfusion.core.backend.bool
import io.github.danielkarp.trainfusion.core.backend.number
import io.github.danielkarp.trainfusion.core.backend.string
import io.github.danielkarp.trainfusion.core.inference.Replay
import io.github.danielkarp.trainfusion.core.inference.SampleRide
import io.github.danielkarp.trainfusion.core.recording.KeepRecordings
import io.github.danielkarp.trainfusion.core.recording.MAX_UPLOAD_BYTES
import io.github.danielkarp.trainfusion.core.recording.RecordingID
import io.github.danielkarp.trainfusion.core.recording.Ride
import io.github.danielkarp.trainfusion.core.recording.formatBytes
import io.github.danielkarp.trainfusion.platform.BackendConfig
import io.github.danielkarp.trainfusion.platform.Storage
import io.github.danielkarp.trainfusion.recording.SharedModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
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

/** Whether one kind of thing (Train Measurements, or the Full Recording) is shared. */
sealed interface ShareStatus {
    data object Shared : ShareStatus
    data object NotShared : ShareStatus
    data class Working(val fraction: Double?) : ShareStatus  // sharing; uploading, with the fraction done
    data object Unsharing : ShareStatus
    data class Waiting(val message: String) : ShareStatus  // will be shared without asking again
    data class Failed(val message: String) : ShareStatus
    data class Unavailable(val message: String) : ShareStatus  // not shared, and can't be from here

    /** Whether a Share button makes sense. */
    val canShare: Boolean get() = this is NotShared || this is Failed
    val canUnshare: Boolean get() = this is Shared

    /** For the ride's screen. */
    val text: String
        get() = when (this) {
            Shared -> "Shared"
            NotShared -> "Not shared"
            is Working -> fraction?.let { "Sharing… ${(it * 100).toInt()}%" } ?: "Sharing…"
            Unsharing -> "Unsharing…"
            is Waiting -> message
            is Failed -> message
            is Unavailable -> message
        }
}

/**
 * The rides on the phone (files/rides.json) and their recordings: which to keep, uploading
 * them (through [Uploader]) and sharing their Train Measurements; and what's shared of rides
 * no longer on the phone (`elsewhere`), to unshare one by one. As the iOS app's RideStore;
 * its state is read by the screens and changed on the main thread.
 */
class RideStore(private val app: TrainFusionApp) {
    enum class Shared { MEASUREMENTS, RECORDING }

    val root: File = Storage.recordings(app)
    private val file = File(app.filesDir, "rides.json")
    private val elsewhereFile = File(app.filesDir, "shared-elsewhere.json")

    var rides by mutableStateOf<List<Ride>>(emptyList())  // newest first
        private set
    var recordings by mutableStateOf<Map<String, Recording>>(emptyMap())
        private set
    var sharing by mutableStateOf(false)
        private set
    var shareMessage by mutableStateOf<String?>(null)
    /** Rides whose measurements are being shared right now, and why the last try failed. */
    var sharingNow by mutableStateOf<Set<String>>(emptySet())
        private set
    var shareFailures by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    /**
     * What's shared (from this phone, or linked to the account) of rides that aren't on the
     * phone any more, newest first: made from what the server has (its measurements, if
     * shared), so they can be unshared one by one. Kept in shared-elsewhere.json.
     */
    var elsewhere by mutableStateOf<List<Ride>>(emptyList())
        private set
    /** What's being unshared right now: `unshareKey`s. */
    var unsharing by mutableStateOf<Set<String>>(emptySet())
        private set
    private var syncing = false

    companion object {
        fun unshareKey(kind: Shared, id: String) = (if (kind == Shared.MEASUREMENTS) "m:" else "r:") + id
        private const val SAMPLE_INSTALLED = "sampleInstalled"
        // (here, not with the other properties: `init` below uses it, before those after it are set)
        private val pretty = Json { prettyPrint = true }
    }

    init {
        load()
        installSample()
        app.uploader.onFinished = { id, outcome -> uploadFinished(id, outcome) }
    }

    /** Reads rides.json and shared-elsewhere.json. */
    fun load() {
        rides = try { if (file.exists()) Ride.decodeList(file.readText()) else emptyList() } catch (_: Exception) { emptyList() }
        elsewhere = try { if (elsewhereFile.exists()) Ride.decodeList(elsewhereFile.readText()) else emptyList() } catch (_: Exception) { emptyList() }
    }

    private fun save() {
        rides = rides.sortedByDescending { it.id }
        write(file, Ride.encodeList(rides))
    }

    private fun write(f: File, text: String) {
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(f)
        } catch (_: Exception) {
        }
    }

    fun ride(id: String): Ride? = rides.firstOrNull { it.id == id }

    /** On the phone, or only shared. */
    fun anyRide(id: String): Ride? = ride(id) ?: elsewhere.firstOrNull { it.id == id }

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
            // (the sample's ride is always read afresh: its meta.json follows the generator)
            var ride = list.firstOrNull { it.id == id }?.takeIf { !it.isSample } ?: Ride.read(dir, id)
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

    /**
     * The first time the app runs, a sample ride to play back ([SampleRide]): just a meta.json
     * saying so, since it's generated as it plays. Deleted like any other ride, and not put
     * back. Later launches rewrite its meta.json (keeping its date), so its stops and length
     * follow the generator as it changes.
     */
    private fun installSample() {
        val model = SharedModel.get(app).getOrNull() ?: return
        fun write(sample: SampleRide, dir: File, started: Double) {
            dir.mkdirs()
            try { File(dir, "meta.json").writeText(pretty.encodeToString(JsonElement.serializer(), sample.meta(started))) } catch (_: Exception) {}
        }
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean(SAMPLE_INSTALLED, false)) {
            for (dir in root.listFiles().orEmpty()) {
                if (!dir.isDirectory || !Replay.isSample(dir)) continue
                val started = try {
                    Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject["started_unix_s"]?.jsonPrimitive?.doubleOrNull
                } catch (_: Exception) { null } ?: continue
                write(SampleRide(model), dir, started)
            }
            return
        }
        prefs.edit().putBoolean(SAMPLE_INSTALLED, true).apply()
        val sample = SampleRide(model)
        val started = System.currentTimeMillis() / 1000.0 - sample.duration - 3600  // as if ridden an hour ago
        write(sample, File(root, RecordingID.make(Date((started * 1000).toLong()))), started)
    }

    /**
     * The rider answered: upload or not. Uploading one whose upload is marked for deletion
     * uploads it afresh (the marked upload is still deleted).
     */
    fun decide(id: String, upload: Boolean, protecting: Set<String>) {
        if (upload && ride(id)?.isSample == true) return  // made up: never shared
        if (upload) {
            update(id) { it.copy(upload = Ride.Upload.WANTED, recordingDeletionRequested = null) }
            recordings[id]?.let {
                File(it.dir, Uploads.UPLOADED_MARKER).delete()
                app.uploader.start(it)
            }
        } else if (ride(id)?.upload != Ride.Upload.UPLOADED) {  // "no" to sharing again leaves it as it was
            update(id) { it.copy(upload = Ride.Upload.DECLINED) }
        }
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

    /** Deletes just the ride's Full Recording from the phone; the ride (its Train Measurements) stays. One that wasn't uploaded counts as declined. */
    fun deleteRecording(id: String) {
        val recording = recordings[id] ?: return
        recording.dir.deleteRecursively()
        app.uploader.forget(id)
        recordings = recordings - id
        update(id) { if (it.upload != Ride.Upload.UPLOADED) it.copy(upload = Ride.Upload.DECLINED) else it }
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

    /**
     * Share these rides' measurements (linked to the account if signed in). `undoDeletion`:
     * the rider asked to share these rides (from History), so measurements marked for
     * deletion are shared again; never for automatic sharing.
     */
    suspend fun share(ids: List<String>, undoDeletion: Boolean = false) {
        if (ids.isEmpty()) return
        sharing = true
        shareMessage = null
        try {
            for (id in ids) {
                val ride = ride(id) ?: continue
                if (ride.isSample || id in sharingNow) continue
                sharingNow = sharingNow + id
                shareFailures = shareFailures - id
                try {
                    withContext(Dispatchers.IO) {
                        app.account.api.call("PUT", "v1/measurements/$id", ride.apiBody(), Api.Auth.IF_SIGNED_IN, device = true,
                            query = if (undoDeletion) mapOf("undo_deletion" to "true") else emptyMap())
                    }
                    update(id) { it.copy(sharedAt = System.currentTimeMillis() / 1000.0, sharePending = null, measurementsDeletionRequested = null) }
                } catch (e: ApiError) {
                    if (e.code == "deletion_pending") {
                        // waiting to be deleted: not shared again unless asked for
                        update(id) { it.copy(sharePending = null, sharedAt = null, measurementsDeletionRequested = true) }
                    } else {
                        // left pending (if it was): tried again when the app is next opened
                        shareMessage = "Couldn't share: ${e.message}"
                        shareFailures = shareFailures + (id to shareMessage!!)
                        return
                    }
                } finally {
                    sharingNow = sharingNow - id
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
            rides = rides.map {
                it.copy(sharedAt = null, sharePending = null,
                    measurementsDeletionRequested = if (it.sharedAt != null) true else it.measurementsDeletionRequested)
            }
            save()
            syncSharing()
            return body["requested"]?.jsonPrimitive?.int ?: 0
        } catch (e: ApiError) {
            shareMessage = "Couldn't delete: ${e.message}"
            return null
        } finally {
            sharing = false
        }
    }

    /** Asks for one ride's shared measurements, or every upload of its recording, to be deleted (by hand, within 30 days). Returns whether the request went through. */
    suspend fun unshare(kind: Shared, id: String): Boolean {
        val key = unshareKey(kind, id)
        if (key in unsharing) return false
        unsharing = unsharing + key
        shareMessage = null
        try {
            withContext(Dispatchers.IO) {
                when (kind) {
                    Shared.MEASUREMENTS -> app.account.api.call("POST", "v1/measurements/deletion-request", auth = Api.Auth.IF_SIGNED_IN,
                        device = true, query = mapOf("ride_id" to id))
                    Shared.RECORDING -> app.account.api.call("POST", "v1/uploads/deletion-request", auth = Api.Auth.IF_SIGNED_IN,
                        device = true, query = mapOf("recording_id" to id))
                }
            }
            when (kind) {
                Shared.MEASUREMENTS -> update(id) { it.copy(sharedAt = null, sharePending = null, measurementsDeletionRequested = true) }
                Shared.RECORDING -> update(id) { it.copy(recordingDeletionRequested = true) }
            }
        } catch (e: ApiError) {
            shareMessage = "Couldn't unshare: ${e.message}"
            return false
        } finally {
            unsharing = unsharing - key
        }
        syncSharing()
        return true
    }

    /**
     * Brings what's shared up to date with the server (this phone's measurements and uploads,
     * and the account's if signed in): each ride's flags, and `elsewhere`. E.g. after asking
     * for deletion, deleting the account, or the developers deleting what was asked for.
     * Kept on the phone, so History shows the last known state offline.
     */
    suspend fun syncSharing() {
        if (!BackendConfig.configured || syncing) return
        syncing = true
        try {
            val (measurements, uploads) = withContext(Dispatchers.IO) {
                try {
                    val m = app.account.api.call("GET", "v1/measurements", auth = Api.Auth.IF_SIGNED_IN, device = true)["measurements"] as? JsonArray
                    val u = app.account.api.call("GET", "v1/uploads", auth = Api.Auth.IF_SIGNED_IN, device = true)["uploads"] as? JsonArray
                    if (m == null || u == null) null else m.mapNotNull { it as? JsonObject } to u.mapNotNull { it as? JsonObject }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {  // offline, or anything else: tried again next time
                    null
                }
            } ?: return

            class OnServer(val body: JsonObject, val marked: Boolean)
            val server = HashMap<String, OnServer>()
            for (m in measurements) {
                val id = m.string("id") ?: continue
                server[id] = OnServer(m, m.bool("deletion_requested") == true)
            }
            val now = System.currentTimeMillis() / 1000.0
            rides = rides.map { ride ->
                if (ride.id in sharingNow) return@map ride
                // not listed: never shared, or deleted by the developers
                val s = server[ride.id]
                ride.copy(
                    measurementsDeletionRequested = if (s?.marked == true) true else null,
                    sharedAt = s?.let { if (it.marked) null else it.body.number("shared_at_unix_s") ?: ride.sharedAt ?: now },
                )
            }

            // a recording may have been uploaded more than once (again after a deletion request)
            val live = HashSet<String>()
            val marked = HashSet<String>()
            for (u in uploads) {
                if (u.string("status") != "complete") continue
                val id = u.string("recording_id") ?: continue
                if (u.bool("deletion_requested") == true) marked.add(id) else live.add(id)
            }
            rides = rides.map { ride ->
                when {
                    ride.id in live -> ride.copy(upload = Ride.Upload.UPLOADED, recordingDeletionRequested = null)
                    ride.id in marked && ride.upload != Ride.Upload.WANTED ->  // WANTED: being uploaded again
                        ride.copy(upload = Ride.Upload.UPLOADED, recordingDeletionRequested = true)
                    // not listed: never uploaded, or uploaded before uploads went through the backend (the
                    // server doesn't know those): left as it is
                    else -> ride
                }
            }
            save()

            // rides not on the phone with something still shared
            val onPhone = rides.map { it.id }.toSet()
            val sharedIDs = (server.filterValues { !it.marked }.keys + live) - onPhone
            elsewhere = sharedIDs.sortedDescending().map { id ->
                val s = server[id]
                (s?.let { Ride.fromServer(id, it.body) } ?: Ride(id)).copy(
                    sharedAt = s?.let { if (it.marked) null else it.body.number("shared_at_unix_s") ?: now },
                    measurementsDeletionRequested = if (s?.marked == true) true else null,
                    upload = if (id in live || id in marked) Ride.Upload.UPLOADED else Ride.Upload.DECLINED,
                    recordingDeletionRequested = if (id !in live && id in marked) true else null,
                )
            }
            write(elsewhereFile, Ride.encodeList(elsewhere))
        } finally {
            syncing = false
        }
    }

    // MARK: what's shared, for History

    fun measurementsStatus(ride: Ride): ShareStatus = when {
        ride.isSample -> ShareStatus.Unavailable("Sample: not shareable")
        unshareKey(Shared.MEASUREMENTS, ride.id) in unsharing -> ShareStatus.Unsharing
        ride.id in sharingNow -> ShareStatus.Working(null)
        ride.measurementsShared -> ShareStatus.Shared
        shareFailures[ride.id] != null -> ShareStatus.Failed(shareFailures.getValue(ride.id))
        ride(ride.id) == null -> ShareStatus.Unavailable("Not shared")  // only what the server has: can't be shared from here
        ride.sharePending == true && app.settings.shareMeasurements -> ShareStatus.Waiting("Waiting to share")
        else -> ShareStatus.NotShared
    }

    fun recordingStatus(ride: Ride, state: Uploader.State?): ShareStatus {
        if (ride.isSample) return ShareStatus.Unavailable("Sample: not shareable")
        if (unshareKey(Shared.RECORDING, ride.id) in unsharing) return ShareStatus.Unsharing
        when (state) {
            Uploader.State.Starting -> return ShareStatus.Working(null)
            is Uploader.State.Uploading -> return ShareStatus.Working(state.fraction)
            is Uploader.State.Waiting -> return ShareStatus.Waiting(state.message)
            is Uploader.State.Failed -> return ShareStatus.Failed(state.message)
            null -> {}
        }
        if (ride.recordingShared) return ShareStatus.Shared
        if (ride.upload == Ride.Upload.WANTED) return ShareStatus.Waiting("Waiting to share")
        if (recordings[ride.id] == null) {
            return ShareStatus.Unavailable(if (ride(ride.id) == null || ride.recordingDeletionRequested == true) "Not shared" else "Not on this phone")
        }
        if (ride.upload == Ride.Upload.TOO_LARGE) return ShareStatus.Unavailable("Too large (over ${formatBytes(MAX_UPLOAD_BYTES)})")
        return ShareStatus.NotShared
    }
}
