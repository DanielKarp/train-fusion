package io.github.danielkarp.trainfusion.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.danielkarp.trainfusion.BuildConfig
import io.github.danielkarp.trainfusion.core.backend.Api
import io.github.danielkarp.trainfusion.core.recording.ClockSource
import io.github.danielkarp.trainfusion.core.recording.KeepRecordings
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's clocks. Uptime is SystemClock.elapsedRealtimeNanos: monotonic, never jumps,
 * and (unlike iOS's mach_absolute_time) keeps counting while the phone sleeps. It's the
 * clock sensor events and AudioRecord's BOOTTIME timestamps are on.
 */
object AndroidClock : ClockSource {
    override fun uptime(): Double = SystemClock.elapsedRealtimeNanos() / 1e9

    override fun unix(): Double {
        val now = Instant.now()
        return now.epochSecond + now.nano / 1e9
    }
}

/**
 * Where the backend is (docs/api.md), and the Identity Platform settings sign-in needs.
 * They come from BuildConfig, filled from android/config.properties (bin/android-config); a
 * build without it records and replays as usual but can't upload or sign in.
 */
object BackendConfig {
    private fun value(s: String) = s.takeIf { it.isNotBlank() && !it.endsWith(".invalid") }

    val host: String? = value(BuildConfig.API_HOST)
    val identityAPIKey: String? = value(BuildConfig.IDENTITY_API_KEY)
    /** The web OAuth client Identity Platform's Google provider is set up with (docs/deploy.md). */
    val googleWebClientID: String? = value(BuildConfig.GOOGLE_WEB_CLIENT_ID)
    /** The public site (infra/hosting.tf), where the privacy policy is. */
    val siteHost: String? = value(BuildConfig.SITE_HOST)

    /** Uploads and Train Measurements need only the backend. (On iOS one setting covers both: here sign-in also needs an
     *  Android API key, which can come later, and recordings shouldn't wait for it.) */
    val configured: Boolean get() = host != null
    /** Signing in also needs Identity Platform's API key for this app. */
    val signInConfigured: Boolean get() = host != null && identityAPIKey != null
    val baseURL: String? get() = host?.let { "https://$it" }

    /** https://<site>/privacy, or the API's own /privacy if the site isn't configured. */
    val privacyPolicy: String? get() = (siteHost?.let { "https://$it" } ?: baseURL)?.let { "$it/privacy" }

    const val NOT_CONFIGURED = Api.NOT_CONFIGURED
    const val SIGN_IN_NOT_CONFIGURED = "This build of the app can't sign in yet (no sign-in settings). It can still record and share."
}

/** Which app is calling, for Google APIs whose key is restricted to it: the package and its signing certificate's SHA-1. */
object AppIdentity {
    fun headers(context: Context): Map<String, String> = mapOf(
        "X-Android-Package" to context.packageName,
        "X-Android-Cert" to (certSha1(context) ?: ""),
    )

    @SuppressLint("PackageManagerGetSignatures")
    fun certSha1(context: Context): String? = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-1").digest(signer.toByteArray()).joinToString("") { "%02X".format(it) }
    } catch (_: Exception) {
        null
    }
}

/**
 * Small secrets: the account's refresh token and the device secret, encrypted with a key in
 * the Android Keystore that never leaves this phone (the iOS app's Keychain, "this device
 * only"). Excluded from backups (AndroidManifest.xml). Unlike the iOS Keychain, they go
 * when the app is uninstalled.
 */
class SecureStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
    private val alias = "train-fusion-secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    @Synchronized
    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun set(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).commit()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(out, Base64.NO_WRAP)).commit()
    }

    /**
     * A random secret made once per install. The server keeps only its hash, and uses it to
     * know which uploads came from this phone (to delete them on request, or link them to an
     * account on sign-in).
     */
    @get:Synchronized
    val deviceSecret: String
        get() {
            get("deviceSecret")?.let { return it }
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val secret = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            set("deviceSecret", secret)
            return secret
        }
}

/**
 * The rider's settings (SharedPreferences, with the iOS app's keys and defaults), observable
 * by Compose. docs/accounts-and-uploads.md says what they mean.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private inner class Pref<T>(private val key: String, initial: T, private val write: SharedPreferences.Editor.(String, T) -> Unit) {
        var state by mutableStateOf(initial)
        fun set(v: T) {
            state = v
            prefs.edit().apply { write(key, v) }.apply()
        }
    }

    private val askToUploadPref = Pref("askToUpload", prefs.getBoolean("askToUpload", true)) { k, v -> putBoolean(k, v) }
    private val keepPref = Pref("keepRecordings", KeepRecordings.of(prefs.getString("keepRecordings", null))) { k, v -> putString(k, v.raw) }
    private val sharePref = Pref("shareMeasurements", prefs.getBoolean("shareMeasurements", false)) { k, v -> putBoolean(k, v) }
    private val askedSharePref = Pref("askedShareMeasurements", prefs.getBoolean("askedShareMeasurements", false)) { k, v -> putBoolean(k, v) }
    private val metricPref = Pref("metricUnits", prefs.getBoolean("metricUnits", false)) { k, v -> putBoolean(k, v) }

    /** Ask after each ride whether to upload its recording. */
    var askToUpload: Boolean get() = askToUploadPref.state; set(v) = askToUploadPref.set(v)
    /** How many recordings to keep on the phone. */
    var keepRecordings: KeepRecordings get() = keepPref.state; set(v) = keepPref.set(v)
    /** Share each ride's Train Measurements when it ends (linked to the account if signed in). */
    var shareMeasurements: Boolean get() = sharePref.state; set(v) = sharePref.set(v)
    /** Whether the rider has been asked about sharing Train Measurements for good. */
    var askedShareMeasurements: Boolean get() = askedSharePref.state; set(v) = askedSharePref.set(v)
    /** Kilometres and km/h rather than miles and mph. */
    var metricUnits: Boolean get() = metricPref.state; set(v) = metricPref.set(v)

    /** Recordings whose Train Measurements are to be shared when they end although the setting is off ("Share just this ride"). */
    fun shareOnce(id: String) {
        prefs.edit().putStringSet("shareMeasurementsOnce", (prefs.getStringSet("shareMeasurementsOnce", emptySet())!! + id)).apply()
    }

    /** Whether `id` was to be shared once; forgets it. */
    fun takeShareOnce(id: String): Boolean {
        val ids = prefs.getStringSet("shareMeasurementsOnce", emptySet())!!
        if (id !in ids) return false
        prefs.edit().putStringSet("shareMeasurementsOnce", ids - id).apply()
        return true
    }

    var pendingEmail: String?
        get() = prefs.getString("account.pendingEmail", null)
        set(v) { prefs.edit().apply { if (v == null) remove("account.pendingEmail") else putString("account.pendingEmail", v) }.apply() }

    var accountUser: String?
        get() = prefs.getString("account.user", null)
        set(v) { prefs.edit().apply { if (v == null) remove("account.user") else putString("account.user", v) }.commit() }
}

/** Where recordings live: like the iOS app's Documents/recordings, visible over USB on a computer (Android 11+). */
object Storage {
    fun recordings(context: Context): File {
        // Android/data/<package>/files/recordings: private to the app, but a computer can copy
        // it over USB (as Finder can the iOS app's). Before Android 11 other apps with storage
        // access could read that folder too, so there it's the app's internal storage.
        val base = if (Build.VERSION.SDK_INT >= 30) context.getExternalFilesDir(null) ?: context.filesDir else context.filesDir
        return File(base, "recordings").also { it.mkdirs() }
    }

    fun replayDumps(context: Context): File {
        val base = if (Build.VERSION.SDK_INT >= 30) context.getExternalFilesDir(null) ?: context.filesDir else context.filesDir
        return File(base, "replay_dump").also { it.mkdirs() }
    }
}
