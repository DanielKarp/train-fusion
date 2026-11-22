package io.github.danielkarp.trainfusion.store

import android.app.Activity
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.danielkarp.trainfusion.TrainFusionApp
import io.github.danielkarp.trainfusion.core.backend.Api
import io.github.danielkarp.trainfusion.core.backend.ApiError
import io.github.danielkarp.trainfusion.core.backend.Identity
import io.github.danielkarp.trainfusion.platform.AppIdentity
import io.github.danielkarp.trainfusion.platform.BackendConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The optional account (ios/TrainRecorder/Account.swift): sign in with Google or an emailed
 * link (Identity Platform, through its REST API), registration with the backend, sign out,
 * deletion. docs/accounts-and-uploads.md. There's no Sign in with Apple on Android (no
 * system support for it; android/README.md).
 */
class Account(private val app: TrainFusionApp) {
    @Serializable
    data class User(val uid: String, val email: String?, val provider: String) {  // apple.com, google.com or email
        val providerName: String
            get() = when (provider) {
                "apple.com" -> "Apple"
                "google.com" -> "Google"
                else -> "email link"
            }
    }

    var user by mutableStateOf<User?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    /** Goes up with every successful sign-in, so the app can offer what follows one (linking what this phone shared before). */
    var signIns by mutableIntStateOf(0)
        private set

    @Volatile private var idToken: Pair<String, Long>? = null  // value, expires (ms)

    private val identity by lazy { Identity(BackendConfig.identityAPIKey, AppIdentity.headers(app)) }

    /** The backend, with this phone's device secret and the account's token when signed in. */
    val api by lazy { Api(BackendConfig.baseURL, { app.secure.deviceSecret }, { currentToken() }) }

    init {
        if (app.secure.get(REFRESH) != null) {
            user = app.settings.accountUser?.let { runCatching { Json.decodeFromString(User.serializer(), it) }.getOrNull() }
        }
    }

    /** The token to send, or null when signed out. Blocking: call off the main thread. */
    private fun currentToken(): String? {
        idToken?.let { if (it.second > System.currentTimeMillis() + 120_000) return it.first }
        if (user == null) return null
        return token()
    }

    /** A current ID token for the backend, refreshed when it's about to expire. Blocking. */
    @Synchronized
    fun token(): String {
        idToken?.let { if (it.second > System.currentTimeMillis() + 120_000) return it.first }
        val refresh = app.secure.get(REFRESH)
        if (user == null || refresh == null) throw ApiError(401, "signed_out", "You're not signed in.")
        try {
            val tokens = identity.refresh(refresh)
            app.secure.set(REFRESH, tokens.refreshToken)
            idToken = tokens.idToken to System.currentTimeMillis() + (tokens.expiresIn * 1000).toLong()
            return tokens.idToken
        } catch (f: Identity.Failure) {
            if (f.signsOut) {
                val message = "You've been signed out: ${f.message}"
                app.scope.launch { signOut(); error = message }
                throw ApiError(401, "signed_out", message)
            }
            throw ApiError(0, if (f.code == "network") "network" else f.code, f.message)
        }
    }

    // MARK: sign in

    /** Sign in with Google: the system's account chooser (Credential Manager), then Identity Platform. */
    suspend fun signInWithGoogle(activity: Activity) {
        val clientID = BackendConfig.googleWebClientID
        if (clientID == null || !BackendConfig.signInConfigured) {
            error = BackendConfig.SIGN_IN_NOT_CONFIGURED
            return
        }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientID).build())
            .build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (_: GetCredentialCancellationException) {
            return
        } catch (_: NoCredentialException) {
            error = "There's no Google account on this phone to sign in with."
            return
        } catch (e: GetCredentialException) {
            error = "Google sign-in didn't complete: ${e.message}"
            return
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            error = "Google sign-in didn't complete."
            return
        }
        val googleToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        run {
            val tokens = identity.signInWithIdp("id_token=$googleToken&providerId=google.com")
            finishSignIn(tokens, "google.com")
        }
    }

    suspend fun sendEmailLink(to: String) {
        val email = to.trim()
        val base = BackendConfig.baseURL
        if (!email.contains("@") || base == null || !BackendConfig.signInConfigured) {
            error = if (BackendConfig.signInConfigured) "That doesn't look like an email address." else BackendConfig.SIGN_IN_NOT_CONFIGURED
            return
        }
        run {
            // (no Android package in the request: the link goes through the backend's web page,
            // whose button opens the app with trainfusion://, as on iOS)
            identity.sendEmailLink(email, "$base/auth/email-link")
            withContext(Dispatchers.Main) {
                app.settings.pendingEmail = email
                notice = "We've emailed a sign-in link to $email. Open it on this phone."
            }
        }
    }

    /**
     * An emailed sign-in link, handed over by the backend's /auth/email-link page as
     * trainfusion://auth/email-link?oobCode=… Returns whether it was one.
     */
    fun handle(uri: Uri): Boolean {
        if (uri.scheme != "trainfusion" || uri.host != "auth") return false
        val code = Identity.oobCode(uri.toString())
        if (code == null) {
            error = "That sign-in link isn't complete. Ask for a new one."
            return true
        }
        val email = app.settings.pendingEmail
        if (email == null) {
            error = "Open the sign-in link on the phone where you asked for it, or ask for a new one here."
            return true
        }
        app.scope.launch {
            run {
                val tokens = identity.signInWithEmailLink(email, code)
                finishSignIn(tokens, "email")
                withContext(Dispatchers.Main) { app.settings.pendingEmail = null }
            }
        }
        return true
    }

    /** Registers with the backend (which refuses new accounts beyond the day's limit), then remembers the account. Blocking. */
    private suspend fun finishSignIn(tokens: Identity.Tokens, provider: String) {
        idToken = tokens.idToken to System.currentTimeMillis() + (tokens.expiresIn * 1000).toLong()
        try {
            api.call("POST", "v1/account", auth = Api.Auth.REQUIRED)
        } catch (e: Exception) {
            idToken = null
            throw e
        }
        app.secure.set(REFRESH, tokens.refreshToken)
        val user = User(tokens.uid, tokens.email, provider)
        withContext(Dispatchers.Main) {
            app.settings.accountUser = Json.encodeToString(User.serializer(), user)
            this@Account.user = user
            notice = null
            signIns += 1
        }
    }

    // MARK: sign out, delete

    /** Forgets the account on this phone. Rides and recordings stay. */
    fun signOut() {
        app.secure.set(REFRESH, null)
        app.settings.accountUser = null
        idToken = null
        user = null
    }

    /**
     * Deletes the account on the server (its Train Measurements and uploads are marked for
     * deletion, done by hand), then signs out. Returns whether it worked. (An account made
     * with Apple on an iPhone can't have its Apple tokens revoked from here: android/README.md.)
     */
    suspend fun deleteAccount(): Boolean {
        if (user == null) return false
        var done = false
        run {
            api.call("DELETE", "v1/account", auth = Api.Auth.REQUIRED)
            withContext(Dispatchers.Main) { signOut() }
            done = true
        }
        return done
    }

    /** Runs `body` off the main thread, showing it as busy, and its failure as `error`. */
    private suspend fun run(body: suspend () -> Unit) {
        if (!BackendConfig.signInConfigured) {
            error = BackendConfig.SIGN_IN_NOT_CONFIGURED
            return
        }
        busy = true
        error = null
        try {
            withContext(Dispatchers.IO) { body() }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            busy = false
        }
    }

    companion object {
        private const val REFRESH = "refreshToken"
    }
}
