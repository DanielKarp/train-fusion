package io.github.danielkarp.trainfusion.recording

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.danielkarp.trainfusion.MainActivity
import io.github.danielkarp.trainfusion.R
import io.github.danielkarp.trainfusion.TrainFusionApp
import kotlinx.coroutines.launch

/**
 * Keeps the app running while it records, with the screen off or the app in the
 * background (iOS: the audio background mode). Android requires a foreground service for
 * that, and so a notification for as long as it records; its Stop button stops the
 * recording. It holds a wake lock so that the sensors keep delivering with the screen off.
 * Swiping the app away doesn't stop the recording (on iOS it does): the notification shows
 * it's still going, and stops it.
 */
class RecordingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as TrainFusionApp
        if (intent?.action == ACTION_STOP) {
            app.scope.launch { app.recorder.stop() }
            return START_NOT_STICKY
        }
        val started = intent?.getLongExtra(EXTRA_STARTED, System.currentTimeMillis()) ?: System.currentTimeMillis()
        // (Android 10 has no microphone type: there, any foreground service may use the microphone)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification(started), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification(started))
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrainFusion:recording")
                .apply { acquire(12 * 3600 * 1000L) }
        }
        return START_NOT_STICKY
    }

    private fun notification(started: Long): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, TrainFusionApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recording a ride")
            .setContentText("Audio and motion stay on this phone unless you choose to share them.")
            .setWhen(started)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "stop"
        private const val EXTRA_STARTED = "started"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, startedMillis: Long) {
            context.startForegroundService(Intent(context, RecordingService::class.java).putExtra(EXTRA_STARTED, startedMillis))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}
