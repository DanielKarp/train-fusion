package io.github.danielkarp.trainfusion.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.github.danielkarp.trainfusion.core.inference.Belief
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Measure screen's cards and a consent question, rendered on the JVM (Robolectric's
 * native graphics) from made-up beliefs, into app/build/screenshots/: for looking at layout
 * changes without a phone. Nothing here comes from a recording.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File("build/screenshots").also { it.mkdirs() }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            TrainFusionTheme {
                Column(Modifier.width(393.dp).background(Palette.background).padding(16.dp)) { content() }
            }
        }
        compose.waitForIdle()
        // (captureToImage waits for a frame on the thread that would draw it: draw it here instead)
        val view = compose.activity.window.decorView
        val content = (view.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0)
        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        content.draw(Canvas(bitmap))
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val moving = Belief(
        second = 1234, speedMean = 24.6, speedSd = 1.1, acceleration = 0.3, stops = 3,
        modeP = doubleArrayOf(0.97, 0.01, 0.0, 0.0, 0.0, 0.02), phoneP = doubleArrayOf(0.95, 0.04, 0.01),
        speedHist = FloatArray(39) { k -> if (k in 22..26) 0.2f else 0f }, pStanding = 0.03,
        distanceSinceStop = 1800.0, legs = listOf(2900.0, 3100.0),
        stopLog = listOf(Belief.Stop(300, 330, "Hillsdale"), Belief.Stop(600, 628, "Belmont"), Belief.Stop(900, 931, "San Carlos")),
        place = Belief.Place("San Carlos", "Redwood City", southbound = true, confidence = 0.97, progress = 0.55,
            metresFromLast = 1800.0, metresToNext = 1450.0, alternative = null),
        stations = listOf(Belief.Station("San Carlos", true, 0.97), Belief.Station("Belmont", true, 0.02)),
        accelInUse = true, accelReading = 0.28, toneEvidence = 2.3, wheelEvidence = 0.9,
    )

    @Test
    fun waiting() = shot("measure-waiting") { BeliefView(null, null, null, "Listening…", Units(false)) }

    @Test
    fun movingBetweenStations() = shot("measure-moving") { BeliefView(moving, 1_700_000_000_000L, null, "Listening…", Units(false), expandSensors = true) }

    @Test
    fun atAStation() = shot("measure-station") {
        BeliefView(moving.copy(modeP = doubleArrayOf(0.02, 0.0, 0.0, 0.95, 0.03, 0.0), distanceSinceStop = 5.0, speedMean = 0.0, stops = 4,
            stopLog = moving.stopLog + Belief.Stop(1220, null, "Redwood City"),
            place = moving.place!!.copy(last = "Redwood City", next = "Menlo Park", progress = 0.0, metresFromLast = 5.0, metresToNext = 3000.0)),
            1_700_000_000_000L, null, "Listening…", Units(true))
    }

    @Test
    fun unsure() = shot("measure-unsure") {
        BeliefView(moving.copy(place = moving.place!!.copy(confidence = 0.55, alternative = "Belmont → Hillsdale")), null, null, "", Units(false))
    }

    @Test
    fun shareQuestion() = shot("consent-measurements") {
        ConsentContent("Share Train Measurements?", SharingInfo.MEASUREMENTS_PURPOSE, SharingInfo.measurementsRows + SharingInfo.deleteRow,
            listOf(ConsentChoice("Always share", primary = true) {}, ConsentChoice("Share just this ride") {}, ConsentChoice("No, and don't ask again") {}))
    }

    @Test
    fun uploadQuestion() = shot("consent-upload") {
        ConsentContent("Share full recording?", SharingInfo.RECORDING_PURPOSE + " · 45.2 MB", SharingInfo.recordingRows + SharingInfo.deleteRow,
            listOf(ConsentChoice("Share", primary = true) {}, ConsentChoice("Don't share") {}, ConsentChoice("Don't ask again") {}))
    }
}

