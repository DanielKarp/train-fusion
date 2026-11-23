package io.github.danielkarp.trainfusion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.danielkarp.trainfusion.ui.LaunchArgs
import io.github.danielkarp.trainfusion.ui.MainScreen
import io.github.danielkarp.trainfusion.ui.Palette
import io.github.danielkarp.trainfusion.ui.Tab
import io.github.danielkarp.trainfusion.ui.TrainFusionTheme

/** The one screen (ios/TrainRecorder/TrainRecorderApp.swift); everything else lives in [TrainFusionApp]. */
class MainActivity : ComponentActivity() {
    private var tabRequest by mutableStateOf<Tab?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as TrainFusionApp
        val args = launchArgs(intent)
        handle(intent)
        setContent {
            TrainFusionTheme {
                Box(Modifier.fillMaxSize().background(Palette.background)) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                        MainScreen(app, args, tabRequest) { tabRequest = null }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** An emailed sign-in link (trainfusion://auth/email-link?oobCode=…): to Settings, where the account is. */
    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if ((application as TrainFusionApp).account.handle(uri)) tabRequest = Tab.SETTINGS
    }

    /**
     * For testing from a computer, as the iOS app's launch arguments, e.g.
     *   adb shell am start -n io.github.danielkarp.trainfusion/.MainActivity \
     *     --es autoreplay <recording id> --es replayspeed max --ez dump true
     */
    private fun launchArgs(intent: Intent?): LaunchArgs {
        val e = intent?.extras ?: return LaunchArgs()
        return LaunchArgs(
            autoReplay = e.getString("autoreplay"),
            replaySpeed = e.getString("replayspeed"),
            dump = e.getBoolean("dump"),
            replaySkips = e.getString("replayskips"),
            tab = e.getString("tab"),
            askUpload = e.getBoolean("askupload"),
            askShare = e.getBoolean("askshare"),
            editRide = e.getBoolean("editride"),
            ride = e.getString("ride"),
            expandSensors = e.getBoolean("expandsensors"),
            account = e.getBoolean("account"),
            record = e.getBoolean("record"),
            openRide = e.getBoolean("openride"),
            elsewhere = e.getBoolean("elsewhere"),
        )
    }
}
