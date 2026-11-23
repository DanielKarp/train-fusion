package io.github.danielkarp.trainfusion.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.danielkarp.trainfusion.TrainFusionApp
import io.github.danielkarp.trainfusion.core.recording.RecordingID
import io.github.danielkarp.trainfusion.core.recording.Ride
import io.github.danielkarp.trainfusion.core.recording.formatBytes
import io.github.danielkarp.trainfusion.platform.BackendConfig
import io.github.danielkarp.trainfusion.store.Recording
import io.github.danielkarp.trainfusion.store.RideStore
import io.github.danielkarp.trainfusion.store.ShareStatus
import io.github.danielkarp.trainfusion.store.Uploader
import kotlinx.coroutines.launch
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The rides on the phone, newest first, then the rides that aren't on the phone any more but
 * still have something shared (`RideStore.elsewhere`), to unshare one by one. Each row: the
 * ride, its date, length and size, and whether its Train Measurements and full recording are
 * shared. Tap a row for the ride (play back, share or unshare either, delete).
 *
 * What's shared is kept on the phone and brought up to date with the server when History
 * appears, when it's pulled down, and when the app comes to the front
 * (`RideStore.syncSharing`). Anything marked for deletion counts as not shared, and sharing
 * it again undoes that. (ios/TrainRecorder/HistoryScreen.swift)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    app: TrainFusionApp,
    args: LaunchArgs = LaunchArgs(),
    /** A recording is in progress: playing back says to stop it first. */
    isRecording: Boolean,
    /** The recording being played back, if any. */
    playing: String?,
    onReplay: (Recording) -> Unit,
    onStopPlayback: () -> Unit,
    /** Upload this ride's recording: asks first. */
    onUpload: (Ride) -> Unit,
) {
    val store = app.store
    val uploader = app.uploader
    val scope = rememberCoroutineScope()
    /** The ride whose screen is open. */
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var askingMeasurements by remember { mutableStateOf<Ride?>(null) }
    var onPhoneExpanded by rememberSaveable { mutableStateOf(true) }
    var elsewhereExpanded by rememberSaveable { mutableStateOf(args.elsewhere) }  // (for screenshots)
    var refreshing by remember { mutableStateOf(false) }
    fun refresh() = scope.launch { refreshing = true; try { store.syncSharing() } finally { refreshing = false } }

    LaunchedEffect(Unit) {
        // for screenshots: --ez openride true opens the newest ride, --ez elsewhere true the newest not on the phone
        if (args.openRide && open == null) open = store.rides.firstOrNull()?.id
        if (args.elsewhere && open == null) open = store.elsewhere.firstOrNull()?.id
        store.syncSharing()
    }
    // a ride that's gone (deleted, or nothing of it shared any more): back to the list
    LaunchedEffect(store.rides.map { it.id } + store.elsewhere.map { it.id }) {
        open?.let { if (store.anyRide(it) == null) open = null }
    }

    fun canDelete(ride: Ride): Boolean = when (uploader.states[ride.id]) {
        Uploader.State.Starting, is Uploader.State.Uploading -> false
        else -> ride.id !in app.protecting
    }

    val id = open
    if (id != null) {
        BackHandler { open = null }
        val ride = store.ride(id)
        RideDetail(
            app, id, uploadState = uploader.states[id],
            replayProblem = replayProblem(store, id),
            isPlaying = playing == id, isRecording = isRecording,
            onBack = { open = null },
            onReplay = { store.recordings[id]?.let(onReplay) },
            onStopPlayback = onStopPlayback,
            onShareMeasurements = { store.ride(id)?.let { askingMeasurements = it } },
            onShareRecording = {
                val r = store.ride(id)
                val recording = store.recordings[id]
                // already said yes: just try again
                if (r != null && recording != null) { if (r.upload == Ride.Upload.WANTED) uploader.start(recording) else onUpload(r) }
            },
            onUnshare = { kind -> app.scope.launch { store.unshare(kind, id) } },
            // deleting it from the phone; what it shared then shows under "Shared, not on this phone"
            onDelete = if (ride != null && canDelete(ride)) ({
                open = null
                store.delete(ride)
                app.scope.launch { store.syncSharing() }
            }) else null,
            // (the sample's recording is a few hundred bytes of meta.json: just delete the ride)
            onDeleteRecording = if (ride != null && !ride.isSample && store.recordings[id] != null && canDelete(ride)) ({
                store.deleteRecording(id)
                app.scope.launch { store.syncSharing() }
            }) else null,
        )
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text("History", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp))
            if (store.rides.isEmpty() && store.elsewhere.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                    Icon(Icons.Filled.GraphicEq, null, tint = Palette.secondary, modifier = Modifier.size(44.dp))
                    Text("No rides", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                PullToRefreshBox(refreshing, onRefresh = { refresh() }, modifier = Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        // headers (and collapsing) only once there are two kinds of ride
                        val both = store.elsewhere.isNotEmpty()
                        if (both) item(key = "h:phone") { SectionToggle("On this phone", store.rides.size, onPhoneExpanded) { onPhoneExpanded = !onPhoneExpanded } }
                        if (onPhoneExpanded || !both) {
                            items(store.rides, key = { it.id }) { ride -> RideListRow(app, ride) { open = ride.id } }
                        }
                        if (both) {
                            item(key = "h:elsewhere") { SectionToggle("Shared, not on this phone", store.elsewhere.size, elsewhereExpanded) { elsewhereExpanded = !elsewhereExpanded } }
                            if (elsewhereExpanded) items(store.elsewhere, key = { "e:" + it.id }) { ride -> RideListRow(app, ride) { open = ride.id } }
                        }
                    }
                }
            }
        }
    }

    askingMeasurements?.let { ride ->
        RideMeasurementsConsentSheet(share = { share ->
            askingMeasurements = null
            if (share) app.scope.launch { store.share(listOf(ride.id), undoDeletion = true) }
        }, onDismiss = { askingMeasurements = null })
    }
}

/** Why this ride can't be played back, or null if it can. */
private fun replayProblem(store: RideStore, id: String): String? {
    val recording = store.recordings[id] ?: return "The Full Recording isn't on this phone any more."
    if (recording.duration == null) return "This ride wasn't stopped properly, so it can't be played back."
    return null
}

// MARK: the list

/** A section's header that folds it away: its title, how many rides, and a chevron. */
@Composable
private fun SectionToggle(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val angle by animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")
    Row(Modifier.fillMaxWidth().background(Palette.background).clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(count.toString(), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.secondary, style = TextStyle(fontFeatureSettings = "tnum"))
        Spacer(Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, if (expanded) "Collapse" else "Expand", tint = Palette.secondary, modifier = Modifier.rotate(angle))
    }
}

@Composable
private fun RideListRow(app: TrainFusionApp, ride: Ride, onClick: () -> Unit) {
    val store = app.store
    val recording = store.recordings[ride.id]
    val measurements = store.measurementsStatus(ride)
    val recordingStatus = store.recordingStatus(ride, app.uploader.states[ride.id])
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FitText(ride.title, 17.sp, minScale = 0.7f, weight = FontWeight.Medium)
            Text(summary(ride, recording), fontSize = 13.sp, style = TextStyle(fontFeatureSettings = "tnum"), color = Palette.secondary)
            SharingLine(measurements, recordingStatus)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.gray2)
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.separator, thickness = 0.5.dp)
}

/** "Not Shared" / "Shared: Measurements, Recording", then anything going on. */
@Composable
private fun SharingLine(measurements: ShareStatus, recording: ShareStatus) {
    val both = listOf("Measurements" to measurements, "Recording" to recording)
    val shared = both.filter { it.second.canUnshare }.map { it.first }
    val secondary = Palette.secondary
    val activities = both.mapNotNull { (name, status) -> activity(name, status) }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = secondary)) { append(if (shared.isEmpty()) "Not Shared" else "Shared: " + shared.joinToString(", ")) }
        for ((activity, color) in activities) {
            withStyle(SpanStyle(color = secondary)) { append(" · ") }
            withStyle(SpanStyle(color = color)) { append(activity) }
        }
    }
    Text(text, fontSize = 13.sp, style = TextStyle(fontFeatureSettings = "tnum"))
}

@Composable
private fun activity(name: String, status: ShareStatus): Pair<String, Color>? = when (status) {
    is ShareStatus.Working -> (status.fraction?.let { "Sharing $name ${(it * 100).toInt()}%" } ?: "Sharing $name…") to Palette.blue
    ShareStatus.Unsharing -> "Unsharing $name…" to Palette.blue
    is ShareStatus.Waiting -> "$name waiting to share" to Palette.orange
    is ShareStatus.Failed -> "Couldn't share $name" to Palette.red
    else -> null
}

private val rowDateFormat: SimpleDateFormat
    get() = SimpleDateFormat("MMM d h:mma", Locale.getDefault()).apply {  // Jan 5 9:05am
        dateFormatSymbols = DateFormatSymbols(Locale.getDefault()).apply { amPmStrings = arrayOf("am", "pm") }
    }

/** 12:34, or 1:02:03; null if it wasn't stopped properly. */
private fun duration(ride: Ride): String? {
    val started = ride.started ?: return null
    val ended = ride.ended ?: return null
    if (!ride.stoppedProperly) return null
    val s = (ended - started).roundToLong()
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** Date · length · size (while the recording is on the phone), for the row. */
private fun summary(ride: Ride, recording: Recording?): String {
    val parts = ArrayList<String>()
    parts.add(RecordingID.date(ride.id)?.let { rowDateFormat.format(it) } ?: ride.id)
    val d = duration(ride)
    if (d != null) parts.add(d) else if (recording != null) parts.add("not stopped properly")
    if (recording != null && !ride.isSample) parts.add(formatBytes(recording.bytes))  // nothing when it isn't on the phone
    return parts.joinToString(" · ")
}

private fun trainText(ride: Ride): String? =
    ride.train?.let { "Trip ${it.trip} · Car ${it.car}" + if (it.position == "unspecified") "" else " · ${it.position}" }

// MARK: one ride

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RideDetail(
    app: TrainFusionApp,
    id: String,
    uploadState: Uploader.State?,
    replayProblem: String?,
    isPlaying: Boolean,
    isRecording: Boolean,
    onBack: () -> Unit,
    onReplay: () -> Unit,
    onStopPlayback: () -> Unit,
    onShareMeasurements: () -> Unit,
    onShareRecording: () -> Unit,
    /** Done once confirmed (the confirmations are asked here, from their buttons). */
    onUnshare: (RideStore.Shared) -> Unit,
    onDelete: (() -> Unit)?,
    onDeleteRecording: (() -> Unit)?,
) {
    val store = app.store
    val scope = rememberCoroutineScope()
    val ride = store.anyRide(id) ?: return  // gone (deleted, or nothing of it shared any more): on its way back to the list
    val recording = store.recordings[id]
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteRecording by remember { mutableStateOf(false) }
    var confirmUnshare by remember { mutableStateOf<RideStore.Shared?>(null) }
    /** Play back was tapped while recording: saying to stop it first. */
    var mustStopRecording by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Palette.groupedBackground).statusBarsPadding()) {
        FormTopBar(ride.title, back = "History", onBack = onBack)
        PullToRefreshBox(refreshing, onRefresh = {
            scope.launch { refreshing = true; try { store.syncSharing() } finally { refreshing = false } }
        }, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                FormSection(null) {
                    val date = RecordingID.date(ride.id)?.let {
                        SimpleDateFormat(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMdjmm"), Locale.getDefault()).format(it)
                    } ?: ride.id
                    FormValueRow("Date", date)
                    val d = duration(ride)
                    if (d != null || recording != null) { FormDivider(); FormValueRow("Duration", d ?: "not stopped properly") }
                    trainText(ride)?.let { FormDivider(); FormValueRow("Train", it) }
                }
                if (recording != null) {  // only while there's something to play
                    FormSection(null, footer = if (!isPlaying) replayProblem else null) {
                        if (isPlaying) {
                            IconButtonRow("Stop playback", Icons.Filled.Stop, enabled = true, onClick = onStopPlayback)
                        } else {
                            IconButtonRow("Play back", Icons.Filled.PlayArrow, enabled = replayProblem == null) {
                                if (isRecording) mustStopRecording = true else onReplay()
                            }
                        }
                    }
                }
                // freeing space (the ride stays) apart from deleting the ride (at the bottom)
                if (onDeleteRecording != null && recording != null) {
                    FormSection("Phone Data") {
                        FormButtonRow("Delete Full Recording (${formatBytes(recording.bytes)})", destructive = true,
                            detail = "Keeps the ride in History") { confirmDeleteRecording = true }
                    }
                }
                if (!ride.isSample) {  // (made up: never shared)
                    FormSection("Sharing") {
                        SharingRow(RideStore.Shared.MEASUREMENTS, store.measurementsStatus(ride), onShareMeasurements) { confirmUnshare = it }
                        FormDivider()
                        SharingRow(RideStore.Shared.RECORDING, store.recordingStatus(ride, uploadState), onShareRecording) { confirmUnshare = it }
                    }
                }
                if (onDelete != null) {
                    FormSection(null) { FormButtonRow("Delete Ride", destructive = true, centered = true) { confirmDelete = true } }
                }
            }
        }
    }

    if (mustStopRecording) {
        AlertDialog(onDismissRequest = { mustStopRecording = false }, title = { Text("Stop recording first") },
            confirmButton = { TextButton(onClick = { mustStopRecording = false }) { Text("OK") } })
    }
    if (confirmDelete && onDelete != null) {
        Confirm("Delete this ride from the phone?", if (ride.measurementsShared || ride.recordingShared) "Shared data stays shared." else null,
            "Delete", onConfirm = onDelete, onDismiss = { confirmDelete = false })
    }
    if (confirmDeleteRecording && onDeleteRecording != null) {
        // (deleting the recording leaves the measurements alone: only its being shared matters)
        Confirm("Delete this ride's Full Recording from the phone?", if (ride.recordingShared) "Shared data stays shared." else null,
            "Delete", onConfirm = onDeleteRecording, onDismiss = { confirmDeleteRecording = false })
    }
    confirmUnshare?.let { kind ->
        Confirm(if (kind == RideStore.Shared.RECORDING) "Unshare this ride's Full Recording?" else "Unshare this ride's Train Measurements?", null,
            "Unshare", onConfirm = { onUnshare(kind) }, onDismiss = { confirmUnshare = null })
    }
}

@Composable
private fun IconButtonRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) Palette.blue else Palette.blue.copy(alpha = 0.4f)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(text, color = color)
    }
}

/** One kind of sharing: its name, its state in words, and Share or Unshare. */
@Composable
private fun SharingRow(kind: RideStore.Shared, status: ShareStatus, onShare: () -> Unit, onUnshare: (RideStore.Shared) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (kind == RideStore.Shared.MEASUREMENTS) "Train Measurements" else "Full Recording")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (status) {
                    is ShareStatus.Working -> status.fraction?.let { f ->
                        CircularProgressIndicator(progress = { f.toFloat() }, modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    } ?: CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    ShareStatus.Unsharing -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    else -> {}
                }
                Text(status.text, fontSize = 15.sp, color = when {
                    status is ShareStatus.Failed -> Palette.red
                    status.canUnshare -> Palette.green
                    else -> Palette.secondary
                })
            }
        }
        if (status.canShare) {
            FilledTonalButton(onClick = onShare, enabled = BackendConfig.configured) {
                Text(if (status is ShareStatus.Failed) "Try again" else "Share", color = Palette.blue)
            }
        } else if (status.canUnshare) {
            FilledTonalButton(onClick = { onUnshare(kind) }, enabled = BackendConfig.configured,
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Palette.red.copy(alpha = 0.12f))) {
                Text("Unshare", color = Palette.red)
            }
        }
    }
}

/** A question before deleting or unsharing: the title says what goes. */
@Composable
internal fun Confirm(title: String, message: String?, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(action, color = Palette.red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
