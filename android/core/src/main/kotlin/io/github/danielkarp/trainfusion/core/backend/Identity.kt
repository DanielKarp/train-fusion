package io.github.danielkarp.trainfusion.core.backend

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URLEncoder

/**
 * Identity Platform's REST API (what the Firebase SDKs call underneath), as
 * ios/TrainRecorder/Account.swift's `Identity`. `platformHeaders` say which app is calling:
 * the API key only works from the app it's restricted to (on Android: X-Android-Package and
 * X-Android-Cert, the signing certificate's SHA-1).
 */
class Identity(private val apiKey: String?, private val platformHeaders: Map<String, String>) {
    class Failure(override val message: String, val code: String) : Exception(message) {
        /** The refresh token is no good any more: sign out. */
        val signsOut: Boolean get() = code in setOf("TOKEN_EXPIRED", "USER_DISABLED", "USER_NOT_FOUND", "INVALID_REFRESH_TOKEN")
    }

    class Tokens(val idToken: String, val refreshToken: String, val expiresIn: Double, val uid: String, val email: String?) {
        companion object {
            fun from(body: JsonObject): Tokens {
                val idToken = body.string("idToken")
                val refreshToken = body.string("refreshToken")
                val uid = body.string("localId")
                if (idToken == null || refreshToken == null || uid == null) {
                    throw Failure("Sign-in failed: an unexpected answer from the server.", "")
                }
                return Tokens(idToken, refreshToken, body.string("expiresIn")?.toDoubleOrNull() ?: 3600.0, uid, body.string("email"))
            }
        }
    }

    class Refreshed(val idToken: String, val refreshToken: String, val expiresIn: Double)

    fun signInWithIdp(postBody: String): Tokens = Tokens.from(post("accounts:signInWithIdp", buildJsonObject {
        put("postBody", postBody); put("requestUri", "http://localhost")
        put("returnSecureToken", true); put("returnIdpCredential", true)
    }))

    /** Emails a sign-in link that, through the backend's /auth/email-link page, comes back to the app. */
    fun sendEmailLink(email: String, continueUrl: String) {
        post("accounts:sendOobCode", buildJsonObject {
            put("requestType", "EMAIL_SIGNIN"); put("email", email)
            put("continueUrl", continueUrl); put("canHandleCodeInApp", true)
        })
    }

    fun signInWithEmailLink(email: String, oobCode: String): Tokens =
        Tokens.from(post("accounts:signInWithEmailLink", buildJsonObject { put("email", email); put("oobCode", oobCode) }))

    fun refresh(refreshToken: String): Refreshed {
        val body = send(
            "https://securetoken.googleapis.com/v1/token?key=${apiKey ?: ""}",
            "application/x-www-form-urlencoded",
            form(mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken)),
        )
        val id = body.string("id_token")
        val refresh = body.string("refresh_token")
        if (id == null || refresh == null) throw Failure("Couldn't refresh the sign-in.", "")
        return Refreshed(id, refresh, body.string("expires_in")?.toDoubleOrNull() ?: 3600.0)
    }

    fun post(method: String, json: JsonObject, version: String = "v1"): JsonObject =
        send("https://identitytoolkit.googleapis.com/$version/$method?key=${apiKey ?: ""}", "application/json", json.toString().toByteArray())

    private fun send(url: String, contentType: String, body: ByteArray): JsonObject {
        val response = try {
            Http.request("POST", url, platformHeaders + ("Content-Type" to contentType), body)
        } catch (e: IOException) {
            throw Failure("Couldn't reach the sign-in server: ${e.message ?: e.javaClass.simpleName}", "network")
        }
        val json = response.json()
        if (response.status != 200) {
            // {"error": {"message": "INVALID_OOB_CODE"}} (securetoken: "TOKEN_EXPIRED" etc. the same way)
            val raw = ((json["error"] as? JsonObject)?.string("message")) ?: json.string("error") ?: "unknown error"
            val code = raw.split(" ").firstOrNull() ?: ""
            throw Failure(describe(code, raw), code)
        }
        return json
    }

    companion object {
        fun describe(code: String, raw: String) = when (code) {
            "EXPIRED_OOB_CODE", "INVALID_OOB_CODE" -> "This sign-in link has expired or was already used. Ask for a new one."
            "INVALID_EMAIL" -> "That doesn't look like an email address."
            "USER_DISABLED" -> "This account has been disabled."
            "TOKEN_EXPIRED", "USER_NOT_FOUND", "INVALID_REFRESH_TOKEN" -> "the account's sign-in is no longer valid."
            else -> "Sign-in failed ($raw)."
        }

        fun form(fields: Map<String, String>): ByteArray =
            fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8").replace("+", "%20")}" }.toByteArray()

        /** The one-time code of an emailed sign-in link: in its own query, or in a link nested in it. */
        fun oobCode(url: String): String? {
            val query = url.substringAfter('?', "").substringBefore('#')
            val items = query.split("&").filter { it.isNotEmpty() }.map {
                val k = it.substringBefore('=')
                val v = java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
                k to v
            }
            items.firstOrNull { it.first == "oobCode" }?.let { return it.second }
            for ((k, v) in items) if (k in setOf("link", "continueUrl", "deep_link_id")) oobCode(v)?.let { return it }
            return null
        }
    }
}
