package io.github.danielkarp.trainfusion.store

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.danielkarp.trainfusion.R
import io.github.danielkarp.trainfusion.TrainFusionApp
import io.github.danielkarp.trainfusion.core.backend.UploadJob
import io.github.danielkarp.trainfusion.core.backend.UploadResult
import io.github.danielkarp.trainfusion.core.backend.Uploads
import io.github.danielkarp.trainfusion.platform.BackendConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Uploads recordings through the backend (docs/api.md, "Uploads"), as
 * ios/TrainRecorder/Uploader.swift does: the backend hands out one signed URL per file, the
 * files go up (meta.json last), and then the backend is told the upload is complete
 * (core's [Uploads]). On iOS a background URL session carries the files; here it's
 * WorkManager, so an upload carries on with the app closed, waits for a network, and
 * picks up where it was after the phone restarts. What's in flight is saved
 * (files/uploads.json); files already in the bucket answer 412 and count as done.
 */
class Uploader(private val app: TrainFusionApp) {
    enum class Outcome { UPLOADED, TOO_LARGE }

    sealed interface State {
        data object Starting : State
        data class Uploading(val fraction: Double) : State
        data class Waiting(val message: String) : State  // over today's limit: retried later
        data class Failed(val message: String) : State
    }

    /** By recording id. */
    val states = mutableStateMapOf<String, State>()
    /** Told when a recording is all uploaded, or turns out too large. */
    var onFinished: ((String, Outcome) -> Unit)? = null
    private val retryAt = HashMap<String, Long>()
    private val work by lazy { WorkManager.getInstance(app) }

    /** uploads.json: the jobs in flight (used from the worker's thread too). */
    val jobs = object : Uploads.JobStore {
        private val file = File(app.filesDir, "uploads.json")
        private val map: MutableMap<String, UploadJob> =
            try { if (file.exists()) UploadJob.decodeAll(file.readText()).toMutableMap() else HashMap() } catch (_: Exception) { HashMap() }

        @Synchronized override fun get(id: String): UploadJob? = map[id]

        @Synchronized override fun set(id: String, job: UploadJob?) {
            if (job == null) map.remove(id) else map[id] = job
            try {
                val tmp = File(file.parentFile, "uploads.json.tmp")
                tmp.writeText(UploadJob.encodeAll(map))
                tmp.renameTo(file)
            } catch (_: Exception) {
            }
        }
    }

    /** Upload `recording`, or carry on uploading it. `automatically`: a retry nobody asked for, which waits out a limit. */
    fun start(recording: Recording, automatically: Boolean = false) {
        val id = recording.id
        when (states[id]) {
            State.Starting, is State.Uploading -> return
            else -> {}
        }
        if (automatically && (retryAt[id] ?: 0) > System.currentTimeMillis()) return
        if (!BackendConfig.configured) {
            states[id] = State.Failed(BackendConfig.NOT_CONFIGURED)
            return
        }
        states[id] = State.Starting
        enqueue(id, delayMillis = 0, policy = if (automatically) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(id: String, delayMillis: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag("upload")
            .build()
        work.enqueueUniqueWork("upload-$id", policy, request)
    }

    /** Stop uploading this recording and forget about it (it's being deleted). */
    fun forget(id: String) {
        work.cancelUniqueWork("upload-$id")
        jobs[id] = null
        states.remove(id)
    }

    // MARK: from the worker (on the main thread)

    internal fun progress(id: String, fraction: Double) {
        states[id] = State.Uploading(fraction)
    }

    internal fun finished(id: String, result: UploadResult) {
        when (result) {
            UploadResult.Uploaded -> { states.remove(id); onFinished?.invoke(id, Outcome.UPLOADED) }
            UploadResult.TooLarge -> { states.remove(id); onFinished?.invoke(id, Outcome.TOO_LARGE) }
            is UploadResult.Waiting -> {
                val at = (result.retryAt * 1000).toLong()
                retryAt[id] = at
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
                states[id] = State.Waiting("Daily limit reached; retries $time")
                // (on iOS it's retried when the app is next opened after that; here, at that time)
                enqueue(id, delayMillis = at - System.currentTimeMillis() + 5000, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
            }
            is UploadResult.Failed -> states[id] = State.Failed(result.message)
        }
    }
}

/** One recording's upload, run by WorkManager (as a foreground job: uploads can take a while). */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TrainFusionApp
        val id = inputData.getString("id") ?: return Result.failure()
        val dir = File(app.store.root, id)
        if (!dir.isDirectory) return Result.failure()
        try { setForeground(foregroundInfo()) } catch (_: Exception) {}  // not allowed now: it runs anyway
        var last = 0L
        val result = withContext(Dispatchers.IO) {
            Uploads(app.account.api, app.uploader.jobs).run(dir, id) { fraction ->
                val now = System.currentTimeMillis()
                if (now - last > 500 || fraction >= 1.0) {
                    last = now
                    app.scope.launch { app.uploader.progress(id, fraction) }
                }
            }
        }
        withContext(Dispatchers.Main) { app.uploader.finished(id, result) }
        return if (result is UploadResult.Failed && result.network && runAttemptCount < 5) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, TrainFusionApp.CHANNEL_UPLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Uploading a recording you chose to share")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
