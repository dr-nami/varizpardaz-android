package com.varizpardaz.listener.app.monitor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.varizpardaz.listener.app.ListenerApp
import com.varizpardaz.listener.app.MainActivity
import com.varizpardaz.listener.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps the heartbeat running while the screen is off. Type `specialUse` (declared in the manifest
 * with an explanation): no standard type fits "listen for bank messages and report to the merchant's server". It shows a
 * persistent Persian notification and stops itself when the phone is no longer paired.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.monitor_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    // The constant is a plain int that is inlined at compile time; ServiceCompat passes it on only where the OS knows the type.
    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must be called promptly after startForegroundService, before anything else can fail.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (loop?.isActive != true) {
            val engine = (application as ListenerApp).container.monitor
            loop = scope.launch {
                engine.run()
                stopSelf() // not paired (any more)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(getString(R.string.monitor_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTIFICATION_ID = 1001

        /**
         * Starts the service. From the background Android 12 and newer refuse this unless the app has an exemption (boot, app
         * update, being visible); a refusal is swallowed, because the service is started again the next time one of those happens.
         */
        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            } catch (_: RuntimeException) {
                // ForegroundServiceStartNotAllowedException (API 31+) and friends
            }
        }
    }
}
