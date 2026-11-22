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

/** History and Settings with the app's real state (made-up rides), rendered into app/build/screenshots/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val out = File("build/screenshots").also { it.mkdirs() }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { TrainFusionTheme { Box(Modifier.fillMaxSize().background(Palette.background)) { content() } } }
        compose.waitForIdle()
        val root = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun settings() = shot("settings") { SettingsScreen(ApplicationProvider.getApplicationContext<TrainFusionApp>()) }

    @Test
    fun history() {
        val app = ApplicationProvider.getApplicationContext<TrainFusionApp>()
        // made-up rides, written where the app keeps them
        val rides = listOf(
            Ride("20000102T030405Z", started = 946_782_245.0, ended = 946_784_000.0, stoppedProperly = true, appVersion = "test",
                stops = listOf(Ride.Stop("Millbrae", 946_782_400.0), Ride.Stop("Mountain View", 946_783_900.0)), stationConfidence = 0.97,
                upload = Ride.Upload.UPLOADED, sharedAt = 946_784_100.0, train = Ride.Train(123, 312, "upper")),
            Ride("20000101T030405Z", started = 946_695_845.0, ended = 946_696_500.0, stoppedProperly = true, appVersion = "test",
                upload = Ride.Upload.DECLINED),
        )
        File(app.filesDir, "rides.json").writeText(Ride.encodeList(rides))
        app.store.load()
        shot("history") { HistoryScreen(app, canReplay = true, onReplay = {}, onUpload = {}) }
    }
}
