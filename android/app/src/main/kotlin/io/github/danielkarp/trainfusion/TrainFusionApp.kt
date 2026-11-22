package io.github.danielkarp.trainfusion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.danielkarp.trainfusion.core.backend.Api
import io.github.danielkarp.trainfusion.core.backend.bool
import io.github.danielkarp.trainfusion.core.recording.Ride
import io.github.danielkarp.trainfusion.platform.SecureStore
import io.github.danielkarp.trainfusion.ui.UploadConsent
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import io.github.danielkarp.trainfusion.platform.Settings
import io.github.danielkarp.trainfusion.recording.AacEncoder
import io.github.danielkarp.trainfusion.recording.Recorder
import io.github.danielkarp.trainfusion.replay.ReplayPlayer
import io.github.danielkarp.trainfusion.store.Account
import io.github.danielkarp.trainfusion.store.RideStore
import io.github.danielkarp.trainfusion.store.Uploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The app's long-lived parts, made once per process: they outlive the screen (a recording
 * runs in a foreground service, an upload in WorkManager), as the iOS app's singletons do.
 */
class TrainFusionApp : Application() {
    /** For work that outlives a screen (sharing measurements, finishing a recording). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: Settings private set
    lateinit var secure: SecureStore private set
    lateinit var account: Account private set
    lateinit var uploader: Uploader private set
    lateinit var store: RideStore private set
    lateinit var recorder: Recorder private set
    lateinit var replay: ReplayPlayer private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        secure = SecureStore(this)
        account = Account(this)
        uploader = Uploader(this)
        store = RideStore(this)
        recorder = Recorder(this)
        replay = ReplayPlayer(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a ride is being recorded"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_UPLOADS, "Uploads", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while a recording you chose to share is uploading"
            setShowBadge(false)
        })
        // How many frames the AAC encoder delays audio by (the .m4a's edit list skips them):
        // measured once per encoder, here so that the first recording doesn't wait for it.
        scope.launch(Dispatchers.Default) { AacEncoder.primingFrames(this@TrainFusionApp) }
    }

    // MARK: what the screens ask about (ios/TrainRecorder/ContentView.swift)

    /** The upload question being asked (after a ride, or from History). */
    var consent by mutableStateOf<UploadConsent?>(null)

    /** After signing in: what this phone shared before (to offer linking it to the account). */
    data class Unlinked(val measurements: Int, val uploads: Int)
    var unlinked by mutableStateOf<Unlinked?>(null)

    /** Recordings that mustn't be deleted from under the app. */
    val protecting: Set<String> get() = setOfNotNull(replay.recording?.id, recorder.activeID)

    fun refresh() {
        store.refresh(excluding = recorder.activeID)
        store.prune(protecting)
    }

    /** A recording has been stopped (with the app's button or the notification's): ask about uploading it, unless asking is off. */
    fun recordingEnded(id: String) {
        val ride = store.rideEnded(id) ?: return refresh()
        if (ride.upload == Ride.Upload.UNDECIDED && settings.askToUpload) {
            consent = UploadConsent(id, store.recordings[id]?.bytes ?: 0, afterRide = true)
        } else {
            store.prune(protecting)
        }
    }

    /** Offers to link what this phone shared before signing in (its device secret's) to the account. */
    suspend fun offerAfterSignIn() {
        if (account.user == null) return
        val (uploads, measurements) = withContext(Dispatchers.IO) {
            val u = runCatching { account.api.call("GET", "v1/uploads", device = true) }.getOrNull()
                ?.let { it["uploads"] as? JsonArray }?.mapNotNull { it as? JsonObject }
                ?.count { it.bool("linked") == false && it.bool("deletion_requested") != true } ?: 0
            val m = runCatching { account.api.call("GET", "v1/measurements", device = true) }.getOrNull()
                ?.let { it["measurements"] as? JsonArray }?.mapNotNull { it as? JsonObject }
                ?.count { it.bool("linked") == false } ?: 0
            u to m
        }
        if (uploads + measurements > 0) unlinked = Unlinked(measurements, uploads)
    }

    fun linkToAccount() {
        scope.launch(Dispatchers.IO) {
            runCatching { account.api.call("POST", "v1/claim", auth = Api.Auth.REQUIRED, device = true) }
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_UPLOADS = "uploads"
        lateinit var instance: TrainFusionApp private set
    }
}
