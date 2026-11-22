package io.github.danielkarp.trainfusion.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.danielkarp.trainfusion.TrainFusionApp
import io.github.danielkarp.trainfusion.core.recording.RideInfo
import io.github.danielkarp.trainfusion.store.Recording
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/** Launch options for testing from a computer (adb; android/README.md), as the iOS app's launch arguments. */
data class LaunchArgs(
    val autoReplay: String? = null,
    val replaySpeed: String? = null,
    val dump: Boolean = false,
    val replaySkips: String? = null,
    val tab: String? = null,  // "history" or "settings", for screenshots
    val askUpload: Boolean = false,
    val askShare: Boolean = false,
    val editRide: Boolean = false,
    val ride: String? = null,  // trip,car,position
    val expandSensors: Boolean = false,
    val account: Boolean = false,
)

enum class Tab { TRACK, RECORDINGS, SETTINGS }

/**
 * Three screens behind a tab bar. "Measure" shows what the model believes: about the
 * recording in progress, or about a recording being replayed. "History" lists the rides on
 * the phone; its replay buttons are the way into replay, and tapping the Measure tab again
 * is the way out. "Settings" has the settings and the account.
 */
@Composable
fun MainScreen(app: TrainFusionApp, args: LaunchArgs, tabRequest: Tab?, onTabHandled: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.TRACK) }
    val scope = rememberCoroutineScope()
    val recorder = app.recorder
    val store = app.store
    val replay = app.replay

    LaunchedEffect(tabRequest) {
        if (tabRequest != null) { tab = tabRequest; onTabHandled() }
    }
    LaunchedEffect(Unit) {
        app.refresh()
        autoReplay(app, args)
        when (args.tab) { "history" -> tab = Tab.RECORDINGS; "settings" -> tab = Tab.SETTINGS }
        if (args.askUpload) store.rides.firstOrNull()?.let { app.consent = UploadConsent(it.id, store.recordings[it.id]?.bytes ?: 0, afterRide = true) }
        if (args.autoReplay != null) tab = Tab.TRACK
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        app.refresh()
        store.resumeUploads()
        scope.launch { store.sharePendingMeasurements() }
    }
    LaunchedEffect(app.account.signIns) { if (app.account.signIns > 0) app.offerAfterSignIn() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.TRACK -> TrackScreen(app, args)
                Tab.RECORDINGS -> HistoryScreen(app, canReplay = !recorder.isRecording,
                    onReplay = { recording -> replay.play(recording); tab = Tab.TRACK },
                    onUpload = { ride -> app.consent = UploadConsent(ride.id, store.recordings[ride.id]?.bytes ?: 0, afterRide = false) })
                Tab.SETTINGS -> SettingsScreen(app, openAccount = args.account)
            }
        }
        NavigationBar {
            NavigationBarItem(selected = tab == Tab.TRACK, onClick = {
                // tapping Measure while already on it, in replay: back to recording mode
                if (tab == Tab.TRACK && replay.recording != null) replay.exit()
                tab = Tab.TRACK
            }, icon = { Icon(Icons.Filled.Tram, null) }, label = { Text("Measure") })
            NavigationBarItem(selected = tab == Tab.RECORDINGS, onClick = { tab = Tab.RECORDINGS },
                icon = { Icon(Icons.Filled.History, null) }, label = { Text("History") })
            NavigationBarItem(selected = tab == Tab.SETTINGS, onClick = { tab = Tab.SETTINGS },
                icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
        }
    }

    app.consent?.let { request ->
        UploadConsentSheet(request, answer = { answer ->
            app.consent = null
            if (answer.stopAsking) app.settings.askToUpload = false
            store.decide(request.rideID, answer.upload, app.protecting)
        }, onDismiss = { app.consent = null })
    }
    app.unlinked?.let { found ->
        val n = found.measurements + found.uploads
        val parts = buildList {
            if (found.measurements > 0) add(if (found.measurements == 1) "1 ride's Train Measurements" else "${found.measurements} rides' Train Measurements")
            if (found.uploads > 0) add(if (found.uploads == 1) "1 full recording" else "${found.uploads} full recordings")
        }.joinToString(" and ")
        AlertDialog(
            onDismissRequest = { app.unlinked = null },
            title = { Text("Link to your account?") },
            text = { Text("This phone shared $parts before you signed in. Link ${if (n == 1) "it" else "them"} to your account, so you'll be able to see ${if (n == 1) "it" else "them"} with it later?") },
            confirmButton = { TextButton(onClick = { app.unlinked = null; app.linkToAccount() }) { Text("Link") } },
            dismissButton = { TextButton(onClick = { app.unlinked = null }) { Text("Not now") } },
        )
    }
}

/**
 * For testing from a computer: --es autoreplay <recording id> [--es replayspeed <x>|max]
 * [--ez dump true] [--es replayskips n,n,p] (skips: next / previous stop, one every two
 * seconds, to exercise the controls).
 */
private suspend fun autoReplay(app: TrainFusionApp, args: LaunchArgs) {
    val recording = args.autoReplay?.let { app.store.recordings[it] } ?: return
    args.replaySpeed?.let { app.replay.speed = it.toDoubleOrNull() ?: 0.0 }
    app.replay.play(recording, dump = args.dump)
    for (skip in args.replaySkips?.split(",").orEmpty()) {
        delay(2000)
        if (skip == "n") app.replay.nextStop() else app.replay.previousStop()
    }
}

/** A thin header (record controls, or replay controls when a recording is being replayed) over the model's beliefs. */
@Composable
private fun TrackScreen(app: TrainFusionApp, args: LaunchArgs) {
    val recorder = app.recorder
    val replay = app.replay
    val units = Units(app.settings.metricUnits)
    var editingRide by remember { mutableStateOf(args.editRide) }
    /** Asking about sharing Train Measurements, once the first recording has started. */
    var askingShare by remember { mutableStateOf(args.askShare) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // for screenshots from a computer: --es ride <trip>,<car>,<position>
        val parts = args.ride?.split(",")
        if (parts != null && parts.size == 3 && RideInfo.problem(parts[0], parts[1]) == null) {
            RideInfo.Position.entries.firstOrNull { it.raw == parts[2] }?.let { recorder.setRide(RideInfo(parts[0].toInt(), parts[1].toInt(), it)) }
        }
    }

    // the microphone (and, on Android 13+, notifications: the one saying it's recording) before the first recording
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.scope.launch {  // (not the screen's scope: leaving the screen mustn't cancel it half way)
            recorder.start()
            // the first recording: ask while it runs (the answer applies when it ends)
            if (recorder.isRecording && !app.settings.askedShareMeasurements) askingShare = true
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            val recording = replay.recording
            if (recording != null) ReplayHeader(app, recording) else RecordHeader(app, onEditRide = { editingRide = true }, onRecord = {
                if (recorder.isRecording) {
                    app.scope.launch { recorder.stop() }
                } else {
                    val wanted = buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissions.launch(wanted.toTypedArray())
                }
            })
        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                replay.recording != null -> BeliefView(replay.store.belief, replay.recording?.started?.time, replay.store.message,
                    "Loading the recording…", units, args.expandSensors)
                recorder.isRecording -> {
                    BeliefView(recorder.inference.belief, recorder.startedAt, recorder.inference.message, "Listening…", units, args.expandSensors)
                    recorder.live.audio.error?.let { Text(it, fontSize = 13.sp, color = Palette.red) }
                }
                else -> Text(
                    if (recorder.starting) "Starting…" else if (recorder.busy) "Saving…" else "Tap the red button to start measuring a ride.\nOr replay an earlier one from History.",
                    style = MaterialTheme.typography.bodyLarge, color = Palette.secondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 40.dp),
                )
            }
            recorder.error?.let { Text(it, fontSize = 13.sp, color = Palette.red, textAlign = TextAlign.Center) }
        }
    }

    if (editingRide) RideSheet(recorder.ride, onSave = { recorder.setRide(it) }, onDismiss = { editingRide = false })
    if (askingShare) {
        MeasurementsConsentSheet(answer = { answer ->
            when (answer) {
                MeasurementsAnswer.ALWAYS -> { app.settings.shareMeasurements = true; app.settings.askedShareMeasurements = true }
                MeasurementsAnswer.THIS_RIDE -> recorder.activeID?.let { app.settings.shareOnce(it) }  // asked again next time
                MeasurementsAnswer.NEVER -> { app.settings.shareMeasurements = false; app.settings.askedShareMeasurements = true }
            }
            askingShare = false
        }, onDismiss = { askingShare = false })  // swiping it away or tapping outside answers nothing: asked again next recording
    }
}

@Composable
private fun RecordHeader(app: TrainFusionApp, onEditRide: () -> Unit, onRecord: () -> Unit) {
    val recorder = app.recorder
    Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
        // in the middle whatever is on either side: the train this is, tap to edit
        TextButton(onClick = onEditRide, modifier = Modifier.padding(horizontal = 62.dp)) {
            val ride = recorder.ride
            if (ride != null) {
                // grey, not the button's colour: quiet once it's filled in
                FitText("Trip ${ride.trip} · Car ${ride.car}" + if (ride.position == RideInfo.Position.UNSPECIFIED) "" else " · ${ride.position.raw}",
                    15.sp, minScale = 0.7f, style = TextStyle(fontFeatureSettings = "tnum"), color = Palette.secondary)
            } else {
                FitText("Enter trip and car number", 15.sp, minScale = 0.7f, color = Palette.blue)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            recorder.startedAt?.let { ElapsedTimer(it) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRecord, enabled = !recorder.busy) {
                Icon(if (recorder.isRecording) Icons.Filled.StopCircle else Icons.Outlined.RadioButtonChecked,
                    contentDescription = if (recorder.isRecording) "Stop recording" else "Start recording",
                    tint = Palette.red, modifier = Modifier.size(34.dp))
            }
        }
    }
}

/** Time since `startedMillis`, ticking: 0:12, 1:02:03 (SwiftUI's Text(_, style: .timer)). */
@Composable
private fun ElapsedTimer(startedMillis: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedMillis) {
        while (true) { now = System.currentTimeMillis(); delay(1000 - now % 1000) }
    }
    val s = ((now - startedMillis) / 1000).coerceAtLeast(0)
    Text(if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60),
        fontSize = 20.sp, style = TextStyle(fontFeatureSettings = "tnum"))
}

@Composable
private fun ReplayHeader(app: TrainFusionApp, recording: Recording) {
    val replay = app.replay
    val total = (recording.duration ?: 0.0).toInt()
    fun clock(s: Int) = String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${clock(replay.position)} / ${clock(total)}", fontSize = 13.sp, style = TextStyle(fontFeatureSettings = "tnum"))
            Spacer(Modifier.weight(1f))
            Text(recording.started?.let { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(it) } ?: recording.id,
                fontSize = 13.sp, color = Palette.secondary, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = { replay.previousStop() }) { Icon(Icons.Filled.SkipPrevious, "Previous stop") }
            IconButton(onClick = { replay.toggle() }) { Icon(if (replay.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (replay.playing) "Pause" else "Play") }
            IconButton(onClick = { replay.nextStop() }) { Icon(Icons.Filled.SkipNext, "Next stop") }
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { menu = true }) {
                    Text(if (replay.speed > 0) "${replay.speed.toInt()}×" else "max", fontSize = 17.sp, style = TextStyle(fontFeatureSettings = "tnum"))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (s in listOf(1.0, 4.0, 16.0, 64.0, 0.0)) {
                        DropdownMenuItem(text = { Text(if (s > 0) "${s.toInt()}×" else "max") }, onClick = { replay.speed = s; menu = false })
                    }
                }
            }
        }
    }
}

/** Trip number, car number and where in the car. Save refuses bad numbers; Clear forgets what was saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RideSheet(ride: RideInfo?, onSave: (RideInfo?) -> Unit, onDismiss: () -> Unit) {
    var trip by remember { mutableStateOf(ride?.trip?.toString() ?: "") }
    var car by remember { mutableStateOf(ride?.car?.toString() ?: "") }
    var position by remember { mutableStateOf(ride?.position ?: RideInfo.Position.UNSPECIFIED) }
    var problem by remember { mutableStateOf<String?>(null) }
    val tripFocus = remember { FocusRequester() }
    val carFocus = remember { FocusRequester() }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun save() {
        val t = trip.trim()
        val c = car.trim()
        problem = RideInfo.problem(t, c)
        if (problem == null) {
            onSave(RideInfo(t.toInt(), c.toInt(), position))
            onDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Palette.background) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Text("This train", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                TextButton(onClick = { save() }) { Text("Save", fontWeight = FontWeight.SemiBold) }
            }
            OutlinedTextField(trip, { new ->
                val old = trip
                trip = new
                // a full trip number moves on to the car
                if (new.length == 3 && old.length == 2) carFocus.requestFocus()
            }, label = { Text("Trip number") }, placeholder = { Text("123") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().focusRequester(tripFocus))
            OutlinedTextField(car, { car = it }, label = { Text("Car number") }, placeholder = { Text("312") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().focusRequester(carFocus))
            problem?.let { Text(it, color = Palette.red, fontSize = 13.sp) }
            Text("Where in the car", fontSize = 13.sp, color = Palette.secondary)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val all = RideInfo.Position.entries
                all.forEachIndexed { i, p ->
                    SegmentedButton(selected = position == p, onClick = { position = p }, shape = SegmentedButtonDefaults.itemShape(i, all.size), icon = {}) {
                        Text(if (p == RideInfo.Position.UNSPECIFIED) "Unset" else p.raw.replaceFirstChar { it.uppercase() }, maxLines = 1, fontSize = 13.sp)
                    }
                }
            }
            if (ride != null) {
                TextButton(onClick = { onSave(null); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Clear", color = Palette.red) }
            }
        }
    }
    LaunchedEffect(Unit) { if (trip.isEmpty()) runCatching { tripFocus.requestFocus() } }
}
