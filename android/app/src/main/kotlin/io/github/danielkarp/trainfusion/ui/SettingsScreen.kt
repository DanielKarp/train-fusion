package io.github.danielkarp.trainfusion.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.danielkarp.trainfusion.MainActivity
import io.github.danielkarp.trainfusion.TrainFusionApp
import io.github.danielkarp.trainfusion.core.backend.Api
import io.github.danielkarp.trainfusion.core.backend.ApiError
import io.github.danielkarp.trainfusion.core.recording.KeepRecordings
import io.github.danielkarp.trainfusion.platform.BackendConfig
import io.github.danielkarp.trainfusion.store.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** Settings' choices for sharing Train Measurements (the settings' two flags). */
private enum class MeasurementSharing(val label: String) { ASK("Ask"), ALWAYS("Always"), NEVER("Never") }

/** Settings, and the screens behind it (ios/TrainRecorder/SettingsScreen.swift). */
@Composable
fun SettingsScreen(app: TrainFusionApp, openAccount: Boolean = false) {
    /** The screen pushed over Settings: "account" or "deleteShared". */
    var pushed by rememberSaveable { mutableStateOf(if (openAccount) "account" else null) }
    when (pushed) {
        "account" -> {
            BackHandler { pushed = null }
            AccountScreen(app, onBack = { pushed = null })
            return
        }
        "deleteShared" -> {
            BackHandler { pushed = null }
            DeleteSharedScreen(app, onBack = { pushed = null })
            return
        }
    }
    val settings = app.settings
    val store = app.store
    val context = LocalContext.current
    var info by remember { mutableStateOf<SharingKind?>(null) }
    /** A "keep" setting that deletes recordings now, waiting to be confirmed. */
    var pendingKeep by remember { mutableStateOf<Pair<KeepRecordings, Int>?>(null) }

    fun apply(setting: KeepRecordings) {
        settings.keepRecordings = setting
        store.prune(app.protecting)
    }

    Column(Modifier.fillMaxSize().background(Palette.groupedBackground).statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        FormSection("Units") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                listOf(false to "Imperial", true to "Metric").forEachIndexed { i, (metric, label) ->
                    SegmentedButton(selected = settings.metricUnits == metric, onClick = { settings.metricUnits = metric },
                        shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {}) { Text(label) }
                }
            }
        }
        FormSection("Phone Data") {
            MenuRow("Keep Recordings", settings.keepRecordings.label, KeepRecordings.entries.map { it.label }) { label ->
                val setting = KeepRecordings.entries.first { it.label == label }
                val deletes = store.prunable(setting, app.protecting).size
                if (deletes > 0) pendingKeep = setting to deletes else apply(setting)
            }
        }
        FormSection("Sharing", footer = store.shareMessage) {
            val current = if (settings.shareMeasurements) MeasurementSharing.ALWAYS else if (settings.askedShareMeasurements) MeasurementSharing.NEVER else MeasurementSharing.ASK
            ShareRow("Measurements", onInfo = { info = SharingKind.MEASUREMENTS }, current = current.label, options = MeasurementSharing.entries.map { it.label }) { label ->
                val new = MeasurementSharing.entries.first { it.label == label }
                settings.shareMeasurements = new == MeasurementSharing.ALWAYS
                settings.askedShareMeasurements = new != MeasurementSharing.ASK
            }
            FormDivider()
            ShareRow("Recordings", onInfo = { info = SharingKind.RECORDINGS }, current = if (settings.askToUpload) "Ask" else "Never",
                options = listOf("Ask", "Never")) { settings.askToUpload = it == "Ask" }
            FormDivider()
            // a screen of its own, so it isn't tapped by accident
            FormLinkRow("Delete Shared Data") { pushed = "deleteShared" }
        }
        FormSection("Account") {
            FormLinkRow(app.account.user?.let { it.email ?: "Signed in" } ?: "Not signed in") { pushed = "account" }
        }
        BackendConfig.privacyPolicy?.let { url ->
            FormSection(null) {
                FormButtonRow("Privacy policy") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        }
    }

    info?.let { SharingInfoSheet(it) { info = null } }
    pendingKeep?.let { (setting, deletes) ->
        Confirm("Delete $deletes Full Recording${if (deletes == 1) "" else "s"} from this phone now?", null, "Delete",
            onConfirm = { apply(setting) }, onDismiss = { pendingKeep = null })
    }
}

/**
 * Asking for everything shared to be deleted: all Train Measurements, all recordings. Behind
 * its own row in Settings so it isn't tapped by accident (one ride's can be unshared from History).
 */
@Composable
private fun DeleteSharedScreen(app: TrainFusionApp, onBack: () -> Unit) {
    val store = app.store
    var confirmDeleteMeasurements by remember { mutableStateOf(false) }
    var deleteMeasurementsMessage by remember { mutableStateOf<String?>(null) }
    var confirmDeleteUploads by remember { mutableStateOf(false) }
    var deleteUploadsMessage by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Palette.groupedBackground).statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        FormTopBar("Delete Shared Data", back = "Settings", onBack = onBack)
        Text("Delete everything shared from this phone, and linked to your account. To delete just one ride, open it in History.",
            fontSize = 15.sp, color = Palette.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
        FormSection(null, footer = deleteMeasurementsMessage ?: store.shareMessage) {
            FormButtonRow("Delete all shared Train Measurements", enabled = !store.sharing && BackendConfig.configured, destructive = true) {
                confirmDeleteMeasurements = true
            }
        }
        FormSection(null, footer = deleteUploadsMessage) {
            FormButtonRow("Delete all shared Full Recordings", enabled = !working && BackendConfig.configured, destructive = true) {
                confirmDeleteUploads = true
            }
        }
    }

    if (confirmDeleteMeasurements) {
        Confirm("Delete all shared Train Measurements?", null, "Delete", onDismiss = { confirmDeleteMeasurements = false }, onConfirm = {
            app.scope.launch {
                store.deleteShared()?.let { n ->
                    deleteMeasurementsMessage = if (n == 0) "Nothing was shared." else "$n ride${if (n == 1) "'s" else "s'"} Train Measurements will be deleted within 30 days."
                }
            }
        })
    }
    if (confirmDeleteUploads) {
        Confirm("Delete all shared Full Recordings?", null, "Delete", onDismiss = { confirmDeleteUploads = false }, onConfirm = {
            app.scope.launch {
                working = true
                try {
                    val body = withContext(Dispatchers.IO) {
                        app.account.api.call("POST", "v1/uploads/deletion-request", auth = Api.Auth.IF_SIGNED_IN, device = true)
                    }
                    val n = body["requested"]?.jsonPrimitive?.int ?: 0
                    deleteUploadsMessage = if (n == 0) "Nothing was shared." else "$n shared Full Recording${if (n == 1) "" else "s"} will be deleted within 30 days."
                    store.syncSharing()  // History shows them as not shared
                } catch (e: ApiError) {
                    deleteUploadsMessage = "Couldn't delete: ${e.message}"
                } finally {
                    working = false
                }
            }
        })
    }
}

/** A label with its value as a menu on the same line (as a Picker's). */
@Composable
private fun MenuRow(label: String, current: String, options: List<String>, extra: (@Composable () -> Unit)? = null, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, maxLines = 1)
        extra?.invoke()
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) {
                Text(current, color = Palette.secondary, maxLines = 1)
                Icon(Icons.Filled.UnfoldMore, null, tint = Palette.secondary, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (o in options) DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(o) })
            }
        }
    }
}

/**
 * What's shared ("Measurements", "Recordings"), with a ? that shows what that is (like the
 * question that asks), and the choice as a menu on the same line.
 */
@Composable
private fun ShareRow(title: String, onInfo: () -> Unit, current: String, options: List<String>, onSelect: (String) -> Unit) {
    MenuRow(title, current, options, extra = {
        IconButton(onClick = onInfo, modifier = Modifier.size(28.dp)) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, "What's shared", tint = Palette.blue, modifier = Modifier.size(20.dp))
        }
    }, onSelect = onSelect)
}

/** Signing in (Google, email link), and once signed in: the account, sign out, delete account. */
@Composable
private fun AccountScreen(app: TrainFusionApp, onBack: () -> Unit) {
    val account = app.account
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    var confirmDeleteAccount by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { account.error = null } }

    Column(Modifier.fillMaxSize().background(Palette.groupedBackground).statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        FormTopBar("Account", back = "Settings", onBack = onBack)
        val user = account.user
        if (user != null) {
            SignedIn(app, user, onDelete = { confirmDeleteAccount = true })
        } else {
            Text("Optional. Links your shared Train Measurements and Full Recordings to an account so that you can view them on the web or download them.",
                style = MaterialTheme.typography.bodyMedium, color = Palette.secondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
            // white with a thin outline, as Google's own buttons are
            OutlinedButton(
                onClick = { (context as? MainActivity)?.let { activity -> scope.launch { account.signInWithGoogle(activity) } } },
                enabled = !account.busy, shape = CircleShape, border = BorderStroke(1.dp, Palette.separator),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 50.dp),
            ) { Text("Sign in with Google", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground) }
            FormSection("Or with an email link", footer = account.notice) {
                TextField(email, { email = it }, placeholder = { Text("Email address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth())
                FormDivider()
                Text("Email me a sign-in link", color = if (email.isEmpty() || account.busy) Palette.blue.copy(alpha = 0.4f) else Palette.blue,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = email.isNotEmpty() && !account.busy) { scope.launch { account.sendEmailLink(email) } }
                        .padding(horizontal = 16.dp, vertical = 14.dp))
            }
        }
        account.error?.let { FormSection(null) { Text(it, color = Palette.red, modifier = Modifier.padding(16.dp)) } }
        if (account.busy) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }

    if (confirmDeleteAccount) {
        // (the title says that what it shared goes too: the one thing that isn't obvious)
        Confirm("Delete your account and what it shared?", null, "Delete account", onDismiss = { confirmDeleteAccount = false }, onConfirm = {
            app.scope.launch { if (account.deleteAccount()) app.store.syncSharing() }
        })
    }
}

@Composable
private fun SignedIn(app: TrainFusionApp, user: Account.User, onDelete: () -> Unit) {
    FormSection(null) {
        FormValueRow("Signed in", user.email ?: "–")
        FormDivider()
        FormValueRow("With", user.providerName)
        FormDivider()
        FormButtonRow("Sign out") { app.account.signOut() }
    }
    FormSection(null) { FormButtonRow("Delete account", enabled = !app.account.busy, destructive = true, onClick = onDelete) }
}
