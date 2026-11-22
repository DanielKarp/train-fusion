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
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
private enum class MeasurementSharing(val label: String) { ASK("Ask each ride"), ALWAYS("Always share"), NEVER("Don't ask") }

/** Settings, and the account behind it (ios/TrainRecorder/SettingsScreen.swift). */
@Composable
fun SettingsScreen(app: TrainFusionApp, openAccount: Boolean = false) {
    var showingAccount by rememberSaveable { mutableStateOf(openAccount) }
    if (showingAccount) {
        BackHandler { showingAccount = false }
        AccountScreen(app, onBack = { showingAccount = false })
        return
    }
    val settings = app.settings
    val store = app.store
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var info by remember { mutableStateOf<SharingKind?>(null) }
    var confirmDeleteMeasurements by remember { mutableStateOf(false) }
    var deleteMeasurementsMessage by remember { mutableStateOf<String?>(null) }
    /** A "keep" setting that deletes recordings now, waiting to be confirmed. */
    var pendingKeep by remember { mutableStateOf<Pair<KeepRecordings, Int>?>(null) }
    var confirmDeleteUploads by remember { mutableStateOf(false) }
    var deleteUploadsMessage by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    fun apply(setting: KeepRecordings) {
        settings.keepRecordings = setting
        store.prune(app.protecting)
    }

    Column(Modifier.fillMaxSize().background(Palette.groupedBackground).statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
        Group("Units") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                listOf(false to "Imperial", true to "Metric").forEachIndexed { i, (metric, label) ->
                    SegmentedButton(selected = settings.metricUnits == metric, onClick = { settings.metricUnits = metric },
                        shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {}) { Text(label) }
                }
            }
        }
        Group("Train Measurements", footer = deleteMeasurementsMessage ?: store.shareMessage) {
            val current = if (settings.shareMeasurements) MeasurementSharing.ALWAYS else if (settings.askedShareMeasurements) MeasurementSharing.NEVER else MeasurementSharing.ASK
            ShareRow(onInfo = { info = SharingKind.MEASUREMENTS }, current = current.label, options = MeasurementSharing.entries.map { it.label }) { label ->
                val new = MeasurementSharing.entries.first { it.label == label }
                settings.shareMeasurements = new == MeasurementSharing.ALWAYS
                settings.askedShareMeasurements = new != MeasurementSharing.ASK
            }
            Divider()
            DestructiveRow("Delete shared measurements", enabled = !store.sharing && BackendConfig.configured) { confirmDeleteMeasurements = true }
        }
        Group("Full Recordings", footer = deleteUploadsMessage) {
            ShareRow(onInfo = { info = SharingKind.RECORDINGS }, current = if (settings.askToUpload) "Ask each ride" else "Don't ask",
                options = listOf("Ask each ride", "Don't ask")) { settings.askToUpload = it == "Ask each ride" }
            Divider()
            DestructiveRow("Delete shared recordings", enabled = !working && BackendConfig.configured) { confirmDeleteUploads = true }
        }
        Group(null) {
            MenuRow("On this phone", settings.keepRecordings.label, KeepRecordings.entries.map { it.label }) { label ->
                val setting = KeepRecordings.entries.first { it.label == label }
                val deletes = store.prunable(setting, app.protecting).size
                if (deletes > 0) pendingKeep = setting to deletes else apply(setting)
            }
        }
        Group("Account") {
            Row(Modifier.fillMaxWidth().clickable { showingAccount = true }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(app.account.user?.let { it.email ?: "Signed in" } ?: "Not signed in", modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.gray2)
            }
        }
        BackendConfig.privacyPolicy?.let { url ->
            Group(null) {
                Text("Privacy policy", color = Palette.blue, modifier = Modifier.fillMaxWidth()
                    .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.padding(horizontal = 16.dp, vertical = 14.dp))
            }
        }
    }

    info?.let { SharingInfoSheet(it) { info = null } }
    pendingKeep?.let { (setting, deletes) ->
        AlertDialog(
            onDismissRequest = { pendingKeep = null },
            title = { Text("Delete $deletes recording${if (deletes == 1) "" else "s"} from this phone now?") },
            text = { Text("They're shared, or you chose not to share them. Their Train Measurements stay.") },
            confirmButton = { TextButton(onClick = { apply(setting); pendingKeep = null }) { Text("Delete", color = Palette.red) } },
            dismissButton = { TextButton(onClick = { pendingKeep = null }) { Text("Cancel") } },
        )
    }
    if (confirmDeleteMeasurements) {
        AlertDialog(
            onDismissRequest = { confirmDeleteMeasurements = false },
            title = { Text("Delete your shared Train Measurements?") },
            text = { Text("The developers delete them within 30 days. They stay on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteMeasurements = false
                    scope.launch {
                        store.deleteShared()?.let { n ->
                            deleteMeasurementsMessage = if (n == 0) "Nothing was shared." else "$n ride${if (n == 1) "'s" else "s'"} Train Measurements will be deleted within 30 days."
                        }
                    }
                }) { Text("Delete", color = Palette.red) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteMeasurements = false }) { Text("Cancel") } },
        )
    }
    if (confirmDeleteUploads) {
        AlertDialog(
            onDismissRequest = { confirmDeleteUploads = false },
            title = { Text("Delete your shared recordings?") },
            text = { Text("The developers delete them within 30 days. They stay on this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteUploads = false
                    scope.launch {
                        working = true
                        deleteUploadsMessage = try {
                            val body = withContext(Dispatchers.IO) {
                                app.account.api.call("POST", "v1/uploads/deletion-request", auth = Api.Auth.IF_SIGNED_IN, device = true)
                            }
                            val n = body["requested"]?.jsonPrimitive?.int ?: 0
                            if (n == 0) "Nothing was shared." else "$n shared recording${if (n == 1) "" else "s"} will be deleted within 30 days."
                        } catch (e: ApiError) {
                            "Couldn't delete: ${e.message}"
                        } finally {
                            working = false
                        }
                    }
                }) { Text("Delete", color = Palette.red) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteUploads = false }) { Text("Cancel") } },
        )
    }
}

/** A grouped section, as an iOS Form's: a header, rows on a card, a footer. */
@Composable
private fun Group(header: String?, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (header != null) Text(header.uppercase(), fontSize = 13.sp, color = Palette.secondary, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
        Column(Modifier.fillMaxWidth().background(Palette.background, RoundedCornerShape(12.dp)), content = content)
        if (footer != null) Text(footer, fontSize = 13.sp, color = Palette.secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.separator, thickness = 0.5.dp)

@Composable
private fun DestructiveRow(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(text, color = if (enabled) Palette.red else Palette.red.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp))
}

/** A label with its value as a menu on the same line (as a Picker's). */
@Composable
private fun MenuRow(label: String, current: String, options: List<String>, extra: (@Composable () -> Unit)? = null, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label)
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

/** "Share", with a ? that shows what's shared (like the question that asks), and the choice as a menu on the same line. */
@Composable
private fun ShareRow(onInfo: () -> Unit, current: String, options: List<String>, onSelect: (String) -> Unit) {
    MenuRow("Share", current, options, extra = {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Settings", tint = Palette.blue) }
            Text("Account", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
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
            Group("Or with an email link", footer = account.notice) {
                TextField(email, { email = it }, placeholder = { Text("Email address") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth())
                Divider()
                Text("Email me a sign-in link", color = if (email.isEmpty() || account.busy) Palette.blue.copy(alpha = 0.4f) else Palette.blue,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = email.isNotEmpty() && !account.busy) { scope.launch { account.sendEmailLink(email) } }
                        .padding(horizontal = 16.dp, vertical = 14.dp))
            }
        }
        account.error?.let { Group(null) { Text(it, color = Palette.red, modifier = Modifier.padding(16.dp)) } }
        if (account.busy) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }

    if (confirmDeleteAccount) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAccount = false },
            title = { Text("Delete your account?") },
            text = { Text("Its Train Measurements and full recordings are deleted within 30 days. Everything on this phone stays. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAccount = false
                    scope.launch { if (account.deleteAccount()) app.store.reconcileShared() }
                }) { Text("Delete account", color = Palette.red) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAccount = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SignedIn(app: TrainFusionApp, user: Account.User, onDelete: () -> Unit) {
    Group(null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("Signed in", modifier = Modifier.weight(1f))
            Text(user.email ?: "–", color = Palette.secondary)
        }
        Divider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("With", modifier = Modifier.weight(1f))
            Text(user.providerName, color = Palette.secondary)
        }
        Divider()
        Text("Sign out", color = Palette.blue, modifier = Modifier.fillMaxWidth().clickable { app.account.signOut() }.padding(horizontal = 16.dp, vertical = 14.dp))
    }
    Group(null) { DestructiveRow("Delete account", enabled = !app.account.busy, onClick = onDelete) }
}
