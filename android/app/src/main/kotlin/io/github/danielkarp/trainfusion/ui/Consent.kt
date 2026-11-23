package io.github.danielkarp.trainfusion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Tram
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.danielkarp.trainfusion.core.recording.formatBytes

/**
 * Asking whether to share (upload) a ride's full recording: after a ride (Share / Don't
 * share / Don't ask again) or from History (Share / Don't share). There's deliberately no
 * "always", nor anything that could read as one. The wording is the consent: keep it
 * specific about what goes, who gets it and why (docs/accounts-and-uploads.md), and the
 * same as the iOS app's (ios/TrainRecorder/UploadConsent.swift).
 */
data class UploadConsent(val rideID: String, val bytes: Long, val afterRide: Boolean) {
    data class Answer(val upload: Boolean, /** Stop asking after each ride (Settings: Full Recordings, Share: "Don't ask"). */ val stopAsking: Boolean)
}

/** One icon line of a consent question. `more`: shown when its ⓘ is tapped. */
data class ConsentRow(val icon: ImageVector, val text: String, val more: String? = null)

class ConsentChoice(val label: String, val primary: Boolean = false, val action: () -> Unit)

/** What each kind of sharing is, as icon lines: in the questions, and behind Settings' ?, so the two always say the same. */
object SharingInfo {
    const val RECORDING_PURPOSE = "To improve train tracking"
    val recordingRows = listOf(
        ConsentRow(Icons.Outlined.GraphicEq, "Audio, including nearby voices"),
        ConsentRow(Icons.Outlined.Sensors, "Motion, compass, barometer"),
        ConsentRow(Icons.Outlined.Lock, "Only this app's devs get it"),
    )

    const val MEASUREMENTS_PURPOSE = "For train operations research"
    val measurementsRows = listOf(
        ConsentRow(Icons.Outlined.Tram, "Position, speed, stops, times"),
        ConsentRow(Icons.Outlined.MicOff, "No audio"),
        ConsentRow(Icons.Outlined.Groups, "Anyone who asks gets it",
            more = "We remove your account and device IDs first. They must agree to use the data only for train operations research, and only publish aggregated statistics, but we cannot guarantee what they do after they receive the data."),
    )

    val deleteRow = ConsentRow(Icons.Outlined.Delete, "Unshare any time in History")
}

enum class SharingKind { MEASUREMENTS, RECORDINGS }

/** Settings' ?: what's shared, laid out like the question, with just an OK. */
@Composable
fun SharingInfoSheet(kind: SharingKind, onDismiss: () -> Unit) {
    when (kind) {
        SharingKind.MEASUREMENTS -> ConsentSheet("Sharing Train Measurements", SharingInfo.MEASUREMENTS_PURPOSE, SharingInfo.measurementsRows,
            listOf(ConsentChoice("OK", primary = true, onDismiss)), onDismiss)
        SharingKind.RECORDINGS -> ConsentSheet("Sharing Full Recordings", SharingInfo.RECORDING_PURPOSE, SharingInfo.recordingRows,
            listOf(ConsentChoice("OK", primary = true, onDismiss)), onDismiss)
    }
}

/** Dismissing it (swiping it down, tapping outside it) answers nothing. */
@Composable
fun UploadConsentSheet(request: UploadConsent, answer: (UploadConsent.Answer) -> Unit, onDismiss: () -> Unit) {
    ConsentSheet(
        title = "Share Full Recording?",
        subtitle = SharingInfo.RECORDING_PURPOSE + " · " + formatBytes(request.bytes),
        rows = SharingInfo.recordingRows + SharingInfo.deleteRow,
        choices = listOf(
            ConsentChoice("Share", primary = true) { answer(UploadConsent.Answer(upload = true, stopAsking = false)) },
            ConsentChoice("Don't share") { answer(UploadConsent.Answer(upload = false, stopAsking = false)) },
        ) + if (request.afterRide) listOf(ConsentChoice("Don't ask again") { answer(UploadConsent.Answer(upload = false, stopAsking = true)) }) else emptyList(),
        onDismiss = onDismiss,
    )
}

/** Sharing one ride's Train Measurements, from History: Share / Don't share. Leaves the setting as it is. */
@Composable
fun RideMeasurementsConsentSheet(share: (Boolean) -> Unit, onDismiss: () -> Unit) {
    ConsentSheet(
        title = "Share Train Measurements?",
        subtitle = SharingInfo.MEASUREMENTS_PURPOSE,
        rows = SharingInfo.measurementsRows + SharingInfo.deleteRow,
        choices = listOf(
            ConsentChoice("Share", primary = true) { share(true) },
            ConsentChoice("Don't share") { share(false) },
        ),
        onDismiss = onDismiss,
    )
}

enum class MeasurementsAnswer { ALWAYS, THIS_RIDE, NEVER }

/**
 * Asked as a recording starts, until answered for good: whether to share Train Measurements.
 * "Always" turns the setting on (Settings); "just this ride" shares the ride being recorded
 * and asks again next time; "No" turns it off for good. Dismissing it answers nothing (asked
 * again next time).
 */
@Composable
fun MeasurementsConsentSheet(answer: (MeasurementsAnswer) -> Unit, onDismiss: () -> Unit) {
    ConsentSheet(
        title = "Share Train Measurements?",
        subtitle = SharingInfo.MEASUREMENTS_PURPOSE,
        rows = SharingInfo.measurementsRows + SharingInfo.deleteRow,
        choices = listOf(
            ConsentChoice("Always share", primary = true) { answer(MeasurementsAnswer.ALWAYS) },
            ConsentChoice("Share just this ride") { answer(MeasurementsAnswer.THIS_RIDE) },
            ConsentChoice("No, and don't ask again") { answer(MeasurementsAnswer.NEVER) },
        ),
        onDismiss = onDismiss,
    )
}

/**
 * The layout the questions share: a title and what it's for, a few icon lines, then the
 * answers as full-width buttons, one per row. Sized to its content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsentSheet(title: String, subtitle: String, rows: List<ConsentRow>, choices: List<ConsentChoice>, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Palette.background) {
        ConsentContent(title, subtitle, rows, choices)
    }
}

@Composable
fun ConsentContent(title: String, subtitle: String, rows: List<ConsentRow>, choices: List<ConsentChoice>) {
    var showingMore by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 12.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.secondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in rows) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                        Icon(row.icon, contentDescription = null, tint = Palette.blue)
                    }
                    // one line each, even on small phones
                    FitText(row.text, 16.sp, Modifier.weight(1f, fill = false), minScale = 0.8f)
                    if (row.more != null) {
                        Box {
                            // a popup, so nothing in the sheet moves or resizes
                            IconButton(onClick = { showingMore = row.text }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Outlined.Info, contentDescription = "Details", tint = Palette.blue, modifier = Modifier.size(20.dp))
                            }
                            if (showingMore == row.text) {
                                Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, 80), onDismissRequest = { showingMore = null },
                                    properties = PopupProperties(focusable = true)) {
                                    Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 8.dp, tonalElevation = 2.dp, modifier = Modifier.width(300.dp)) {
                                        Text(row.more, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            for (choice in choices) {
                if (choice.primary) {
                    Button(onClick = choice.action, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                        Text(choice.label, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    }
                } else {
                    FilledTonalButton(onClick = choice.action, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                        Text(choice.label, fontSize = 17.sp, color = Palette.blue)
                    }
                }
            }
        }
    }
}
