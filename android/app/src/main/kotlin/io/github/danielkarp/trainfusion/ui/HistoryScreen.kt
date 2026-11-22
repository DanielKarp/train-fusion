package io.github.danielkarp.trainfusion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.danielkarp.trainfusion.TrainFusionApp
import io.github.danielkarp.trainfusion.core.recording.MAX_UPLOAD_BYTES
import io.github.danielkarp.trainfusion.core.recording.RecordingID
import io.github.danielkarp.trainfusion.core.recording.Ride
import io.github.danielkarp.trainfusion.core.recording.formatBytes
import io.github.danielkarp.trainfusion.store.Recording
import io.github.danielkarp.trainfusion.store.Uploader
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The rides on the phone, newest first, each with upload and replay while its recording is
 * still here. Swipe left to delete a ride from the phone (ios/TrainRecorder/HistoryScreen.swift).
 */
@Composable
fun HistoryScreen(app: TrainFusionApp, canReplay: Boolean, onReplay: (Recording) -> Unit, onUpload: (Ride) -> Unit) {
    val store = app.store
    val uploader = app.uploader
    var deleting by remember { mutableStateOf<Ride?>(null) }
    fun busy(ride: Ride) = when (uploader.states[ride.id]) { Uploader.State.Starting, is Uploader.State.Uploading -> true; else -> false }

    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        if (store.rides.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.GraphicEq, null, tint = Palette.secondary, modifier = Modifier.size(44.dp))
                Text("No rides", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(store.rides, key = { it.id }) { ride ->
                    val recording = store.recordings[ride.id]
                    val row = @Composable {
                        RideRow(
                            ride, recording, uploader.states[ride.id],
                            onUpload = {
                                // already said yes: just try again
                                if (recording != null) { if (ride.upload == Ride.Upload.WANTED) uploader.start(recording) else onUpload(ride) }
                            },
                            onReplay = if (recording?.duration == null || !canReplay) null else ({ onReplay(recording) }),
                        )
                    }
                    if (!busy(ride) && ride.id !in app.protecting) {
                        val state = rememberSwipeToDismissBoxState(confirmValueChange = {
                            if (it == SwipeToDismissBoxValue.EndToStart) deleting = ride
                            false  // the row stays until the deletion is confirmed
                        })
                        SwipeToDismissBox(state, enableDismissFromStartToEnd = false, backgroundContent = {
                            Box(Modifier.fillMaxSize().background(Palette.red).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                                Icon(Icons.Filled.Delete, "Delete", tint = androidx.compose.ui.graphics.Color.White)
                            }
                        }) { Box(Modifier.background(Palette.background)) { row() } }
                    } else {
                        row()
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
        }
    }

    deleting?.let { ride ->
        val hasRecording = store.recordings[ride.id] != null
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this ride from the phone?") },
            text = {
                Text("Its timings" + (if (hasRecording) " and recording" else "") + " are deleted from this phone." +
                    (if (ride.upload == Ride.Upload.UNDECIDED && hasRecording) " The recording hasn't been uploaded." else "") +
                    " What's shared stays shared (delete it in Settings).")
            },
            confirmButton = { TextButton(onClick = { store.delete(ride); deleting = null }) { Text("Delete", color = Palette.red) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RideRow(ride: Ride, recording: Recording?, state: Uploader.State?, onUpload: () -> Unit, onReplay: (() -> Unit)?) {
    val status: Pair<String, Boolean>? = when {
        state is Uploader.State.Waiting -> state.message to false
        state is Uploader.State.Failed -> state.message to true
        ride.upload == Ride.Upload.TOO_LARGE && recording != null -> "Too large to upload (over ${formatBytes(MAX_UPLOAD_BYTES)})" to false
        ride.upload == Ride.Upload.WANTED && state == null -> "Waiting to upload" to false
        else -> null
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FitText(ride.title, 16.sp, minScale = 0.7f, weight = FontWeight.Medium)
            Text(detail(ride, recording), fontSize = 13.sp, style = TextStyle(fontFeatureSettings = "tnum"), color = Palette.secondary)
            ride.train?.let { train ->
                Text("Trip ${train.trip} · Car ${train.car}" + if (train.position == "unspecified") "" else " · ${train.position}",
                    fontSize = 13.sp, style = TextStyle(fontFeatureSettings = "tnum"), color = Palette.secondary)
            }
            status?.let { (text, problem) -> Text(text, fontSize = 13.sp, color = if (problem) Palette.red else Palette.secondary) }
        }
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            when (state) {
                Uploader.State.Starting -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                is Uploader.State.Uploading -> CircularProgressIndicator(progress = { state.fraction.toFloat() }, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                else -> when {
                    ride.upload == Ride.Upload.UPLOADED -> Icon(Icons.Filled.CloudDone, "Uploaded", tint = Palette.green)
                    recording != null && ride.upload != Ride.Upload.TOO_LARGE -> IconButton(onClick = onUpload) { Icon(Icons.Outlined.CloudUpload, "Upload", tint = Palette.blue) }
                }
            }
        }
        if (onReplay != null) IconButton(onClick = onReplay) { Icon(Icons.Outlined.PlayCircle, "Replay", tint = Palette.blue) }
    }
}

private val dateFormat: SimpleDateFormat
    get() = SimpleDateFormat("MMM d h:mma", Locale.getDefault()).apply {  // Jan 5 9:05am
        dateFormatSymbols = DateFormatSymbols(Locale.getDefault()).apply { amPmStrings = arrayOf("am", "pm") }
    }

private fun detail(ride: Ride, recording: Recording?): String {
    val parts = ArrayList<String>()
    parts.add(RecordingID.date(ride.id)?.let { dateFormat.format(it) } ?: ride.id)
    val started = ride.started
    val ended = ride.ended
    if (started != null && ended != null && ride.stoppedProperly) {
        val s = (ended - started).roundToLong()
        parts.add(if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60))
    } else if (recording != null) {
        parts.add("not stopped properly")
    }
    parts.add(if (recording != null) formatBytes(recording.bytes) else "recording not kept")
    if (ride.upload == Ride.Upload.UPLOADED) parts.add("uploaded")
    if (ride.sharedAt != null) parts.add("measurements shared")
    return parts.joinToString(" · ")
}
