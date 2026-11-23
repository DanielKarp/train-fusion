package io.github.danielkarp.trainfusion.core.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/** An upload in progress: the signed URL of each file (POST /v1/uploads). Kept in uploads.json. */
@Serializable
data class UploadJob(
    val recordingID: String,
    val uploadID: String,
    val expires: Double,  // unix seconds, when the URLs stop working
    val files: List<File>,
) {
    @Serializable
    data class File(
        val name: String,
        val size: Long,
        val url: String,
        val headers: Map<String, String>,
        val done: Boolean = false,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val mapSerializer = MapSerializer(String.serializer(), serializer())

        fun encodeAll(jobs: Map<String, UploadJob>): String = json.encodeToString(mapSerializer, jobs)
        fun decodeAll(text: String): Map<String, UploadJob> = json.decodeFromString(mapSerializer, text)
    }
}

/** How an attempt to upload a recording ended. */
sealed interface UploadResult {
    data object Uploaded : UploadResult
    /** Over the size limit: never to be uploaded. */
    data object TooLarge : UploadResult
    /** Over today's limit: try again at `retryAt` (unix seconds). */
    data class Waiting(val retryAt: Double) : UploadResult
    /** `network`: no answer at all (worth retrying by itself once there's a network). */
    data class Failed(val message: String, val network: Boolean = false) : UploadResult
}

/**
 * Uploads one recording through the backend (docs/api.md, "Uploads"), the way
 * ios/TrainRecorder/Uploader.swift does: the backend hands out one signed URL per file,
 * the files go up (meta.json last; a 412 means the file is already there, which counts as
 * done), then the backend is told the upload is complete and checks it. What's in flight is
 * kept (`jobs`), so an upload picks up where it was.
 */
class Uploads(
    private val api: Api,
    private val jobs: JobStore,
    private val now: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    interface JobStore {
        operator fun get(id: String): UploadJob?
        operator fun set(id: String, job: UploadJob?)
    }

    companion object {
        /** In a recording's directory once all of it is uploaded (hidden, so never uploaded). */
        const val UPLOADED_MARKER = ".uploaded"

        /** The files an upload sends: everything but hidden files (the .uploaded marker). */
        fun files(dir: File): List<File> =
            (dir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.startsWith(".") }.sortedBy { it.name }

        fun contentType(name: String) = when (name.substringAfterLast('.', "")) {
            "m4a" -> "audio/mp4"
            "gz" -> "application/gzip"
            "csv" -> "text/csv"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }

    /**
     * Uploads `dir` (recording `id`), or carries on uploading it. `progress` gets the
     * fraction done. Blocking.
     */
    fun run(dir: File, id: String, progress: (Double) -> Unit = {}): UploadResult {
        val files = files(dir).map { it.name to it.length() }
        val current = jobs[id]?.takeIf { job ->
            // still good for an hour, and for these same files
            job.expires > now() + 3600 && job.files.map { "${it.name}:${it.size}" }.toSet() == files.map { "${it.first}:${it.second}" }.toSet()
        }
        var job = current ?: try {
            requestJob(id, files)
        } catch (e: ApiError) {
            return when (e.code) {
                "rate_limited" -> UploadResult.Waiting(now() + (e.retryAfter ?: 3600.0))
                "too_large" -> UploadResult.TooLarge
                else -> UploadResult.Failed(e.message, network = e.status == 0)
            }
        }
        jobs[id] = job

        val total = maxOf(job.files.sumOf { it.size }, 1L)
        fun doneBytes(j: UploadJob) = j.files.filter { it.done }.sumOf { it.size }
        progress(doneBytes(job).toDouble() / total)
        // the data files, then meta.json once they're all in
        val order = job.files.filter { it.name != "meta.json" } + job.files.filter { it.name == "meta.json" }
        for (file in order) {
            if (job.files.first { it.name == file.name }.done) continue
            val before = doneBytes(job)
            val status = try {
                Http.putFile(file.url, file.headers, File(dir, file.name), { sent -> progress((before + minOf(sent, file.size)).toDouble() / total) })
            } catch (e: IOException) {
                return UploadResult.Failed("Sharing failed.", network = true)
            }
            if (status in 200..299 || status == 412) {  // 412: already there
                job = job.copy(files = job.files.map { if (it.name == file.name) it.copy(done = true) else it })
                jobs[id] = job
            } else {
                // the URL was refused (e.g. expired): ask for new ones next time
                if (status == 400 || status == 403) jobs[id] = null
                return UploadResult.Failed("Sharing failed.")
            }
        }
        return try {
            api.call("POST", "v1/uploads/${job.uploadID}/complete", auth = Api.Auth.IF_SIGNED_IN, device = true)
            File(dir, UPLOADED_MARKER).createNewFile()
            jobs[id] = null
            progress(1.0)
            UploadResult.Uploaded
        } catch (e: ApiError) {
            if (e.code == "incomplete") {
                // start over with fresh URLs next time (files that are there answer 412)
                jobs[id] = null
                UploadResult.Failed("Sharing didn't finish.")
            } else {
                UploadResult.Failed("Sharing didn't finish.", network = e.status == 0)
            }
        }
    }

    private fun requestJob(id: String, files: List<Pair<String, Long>>): UploadJob {
        val body = api.call("POST", "v1/uploads", buildJsonObject {
            put("recording_id", id)
            put("files", buildJsonArray {
                files.forEach { (name, size) ->
                    add(buildJsonObject { put("name", name); put("size", size); put("content_type", contentType(name)) })
                }
            })
        }, auth = Api.Auth.IF_SIGNED_IN, device = true)
        val bad = ApiError(0, "bad_answer", "The server's answer didn't make sense.")
        val uploadID = body.string("upload_id") ?: throw bad
        val urls = (body["files"] ?: throw bad).jsonArray
        val sizes = files.toMap()
        return UploadJob(
            recordingID = id, uploadID = uploadID,
            expires = body.number("expires_unix_s") ?: (now() + 3600 * 12),
            files = urls.map { e ->
                val f = e as? JsonObject ?: throw bad
                val name = f.string("name") ?: throw bad
                val url = f.string("url") ?: throw bad
                val size = sizes[name] ?: throw bad
                val headers = (f["headers"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" } ?: emptyMap()
                UploadJob.File(name, size, url, headers)
            },
        )
    }
}
