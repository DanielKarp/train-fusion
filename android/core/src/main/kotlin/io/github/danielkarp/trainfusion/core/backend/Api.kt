package io.github.danielkarp.trainfusion.core.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** An error the backend (or the network) gave, with a message to show (docs/api.md: `{"error", "message"}`). */
class ApiError(
    val status: Int,  // 0: no answer
    val code: String,
    override val message: String,
    val retryAfter: Double? = null,
    val missing: List<String> = emptyList(),
) : Exception(message)

/** One HTTP exchange: status and body. Blocking; call it off the main thread. */
object Http {
    class Response(val status: Int, val body: ByteArray) {
        val text: String get() = body.toString(Charsets.UTF_8)
        fun json(): JsonObject = try {
            Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
    }

    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 30_000,
    ): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            } else if (method == "POST" || method == "PUT") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            val status = c.responseCode
            val stream: InputStream? = if (status >= 400) c.errorStream else c.inputStream
            return Response(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            c.disconnect()
        }
    }

    /**
     * PUTs a file, streamed, with exactly these headers (a signed URL's); `progress` gets the
     * bytes sent so far. Returns the status.
     */
    fun putFile(url: String, headers: Map<String, String>, file: File, progress: (Long) -> Unit = {}, timeoutMs: Int = 60_000): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.doOutput = true
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val size = file.length()
            c.setFixedLengthStreamingMode(size)
            file.inputStream().use { input ->
                c.outputStream.use { out ->
                    val buf = ByteArray(1 shl 16)
                    var sent = 0L
                    while (true) {
                        val k = input.read(buf)
                        if (k < 0) break
                        out.write(buf, 0, k)
                        sent += k
                        progress(sent)
                        if (Thread.currentThread().isInterrupted) throw IOException("cancelled")
                    }
                }
            }
            val status = c.responseCode
            (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() }
            return status
        } finally {
            c.disconnect()
        }
    }
}

/**
 * Calls to the backend (docs/api.md), as ios/TrainRecorder/Backend.swift's `API`. `token`
 * gives the account's current ID token (refreshing it if need be), or null when signed out;
 * `deviceSecret` the device secret.
 */
class Api(
    private val baseUrl: String?,
    private val deviceSecret: () -> String,
    private val token: () -> String?,
) {
    enum class Auth { NONE, REQUIRED, IF_SIGNED_IN }

    val configured: Boolean get() = baseUrl != null

    fun call(
        method: String, path: String, json: JsonElement? = null, auth: Auth = Auth.NONE, device: Boolean = false,
        query: Map<String, String> = emptyMap(),
    ): JsonObject {
        val base = baseUrl ?: throw ApiError(0, "not_configured", NOT_CONFIGURED)
        val headers = HashMap<String, String>()
        val body = json?.toString()?.toByteArray()
        if (body != null) headers["Content-Type"] = "application/json"
        when (auth) {
            Auth.NONE -> {}
            Auth.REQUIRED -> headers["Authorization"] = "Bearer ${token() ?: throw ApiError(401, "signed_out", "You're not signed in.")}"
            Auth.IF_SIGNED_IN -> token()?.let { headers["Authorization"] = "Bearer $it" }
        }
        if (device) headers["X-Device-Secret"] = deviceSecret()
        val response = try {
            val q = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") {
                "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
            }
            Http.request(method, base.trimEnd('/') + "/" + path + q, headers, body)
        } catch (e: IOException) {
            throw ApiError(0, "network", "Couldn't reach the server: ${e.message ?: e.javaClass.simpleName}")
        }
        return check(response)
    }

    companion object {
        const val NOT_CONFIGURED = "This build of the app isn't connected to a server, so it can't upload or sign in."

        /** The JSON body of a 2xx answer, or an [ApiError]. */
        fun check(response: Http.Response): JsonObject {
            val body = response.json()
            if (response.status !in 200..299) {
                throw ApiError(
                    status = response.status,
                    code = body.string("error") ?: "http_${response.status}",
                    message = body.string("message") ?: "The server answered ${response.status}.",
                    retryAfter = (body["retry_after_s"] as? JsonPrimitive)?.doubleOrNull,
                    missing = (body["missing"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
                )
            }
            return body
        }
    }
}

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
