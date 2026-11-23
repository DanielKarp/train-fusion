package io.github.danielkarp.trainfusion.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.danielkarp.trainfusion.TrainFusionApp
import io.github.danielkarp.trainfusion.core.recording.Ride
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** History, Settings and the idle Measure screen with the app's real state (made-up rides), rendered into app/build/screenshots/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File("build/screenshots").also { it.mkdirs() }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { TrainFusionTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { content() } } }
        compose.waitForIdle()
        save(name)
    }

    private fun save(name: String) {
        val root = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun settings() = shot("settings") { SettingsScreen(ApplicationProvider.getApplicationContext<TrainFusionApp>()) }

    /** Made-up rides, written where the app keeps them: two on the phone, one only shared. */
    private fun rides(app: TrainFusionApp) {
        val rides = listOf(
            Ride("20000102T030405Z", started = 946_782_245.0, ended = 946_784_000.0, stoppedProperly = true, appVersion = "test",
                stops = listOf(Ride.Stop("Millbrae", 946_782_400.0), Ride.Stop("Mountain View", 946_783_900.0)), stationConfidence = 0.97,
                upload = Ride.Upload.UPLOADED, sharedAt = 946_784_100.0, train = Ride.Train(123, 312, "upper")),
            Ride("20000101T030405Z", started = 946_695_845.0, ended = 946_696_500.0, stoppedProperly = true, appVersion = "test",
                upload = Ride.Upload.DECLINED),
        )
        val elsewhere = listOf(
            Ride("19991231T030405Z", started = 946_609_445.0, ended = 946_611_000.0, stoppedProperly = true,
                stops = listOf(Ride.Stop("Palo Alto", 946_609_600.0), Ride.Stop("San Jose Diridon", 946_610_900.0)), stationConfidence = 0.95,
                upload = Ride.Upload.DECLINED, sharedAt = 946_611_100.0),
        )
        File(app.filesDir, "rides.json").writeText(Ride.encodeList(rides))
        File(app.filesDir, "shared-elsewhere.json").writeText(Ride.encodeList(elsewhere))
        app.store.load()
    }

    @Test
    fun history() {
        val app = ApplicationProvider.getApplicationContext<TrainFusionApp>()
        rides(app)
        shot("history") {
            HistoryScreen(app, isRecording = false, playing = null, onReplay = {}, onStopPlayback = {}, onUpload = {})
        }
        compose.onNodeWithText("On this phone").assertExists()
        compose.onNodeWithText("Shared: Measurements, Recording").assertExists()
        compose.onNodeWithText("Palo Alto → San Jose").assertDoesNotExist()  // folded away at first
        compose.onNodeWithText("Shared, not on this phone").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Palo Alto → San Jose").assertExists()
        save("history")
    }

    @Test
    fun sharedElsewhere() {
        val app = ApplicationProvider.getApplicationContext<TrainFusionApp>()
        rides(app)
        shot("history-elsewhere") {
            HistoryScreen(app, LaunchArgs(elsewhere = true), isRecording = false, playing = null, onReplay = {}, onStopPlayback = {}, onUpload = {})
        }
        compose.onNodeWithText("Unshare").assertExists()  // its measurements
        compose.onNodeWithText("Delete Ride").assertDoesNotExist()  // not on the phone
    }

    @Test
    fun ride() {
        val app = ApplicationProvider.getApplicationContext<TrainFusionApp>()
        rides(app)
        shot("history-ride") {
            HistoryScreen(app, LaunchArgs(openRide = true), isRecording = false, playing = null, onReplay = {}, onStopPlayback = {}, onUpload = {})
        }
        compose.onNodeWithText("Train Measurements").assertExists()
        compose.onNodeWithText("Trip 123 · Car 312 · upper").assertExists()
        compose.onNodeWithText("Delete Ride").assertExists()
    }

    @Test
    fun measureIdle() {
        val app = ApplicationProvider.getApplicationContext<TrainFusionApp>()
        shot("measure-idle") { MainScreen(app, LaunchArgs(), null) {} }
        compose.onNodeWithText("Not recording").assertExists()
        compose.onNodeWithText("Not on train? Play sample ride").assertExists()  // (installed on first run)
    }

    @Test
    fun deleteShared() {
        val app = ApplicationProvider.getApplicationContext<TrainFusionApp>()
        shot("settings-delete-shared") { SettingsScreen(app) }
        compose.onNodeWithText("Delete Shared Data").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Delete all shared Full Recordings").assertExists()
        save("settings-delete-shared")
    }
}
