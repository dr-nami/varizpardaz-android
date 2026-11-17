package com.varizpardaz.listener.app.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Brings the service back after a reboot and after the app was updated. Both broadcasts are exempt from Android's
 * background-start limits for a `specialUse` foreground service. Only phones that hold credentials start anything.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!File(context.filesDir, "credentials.bin").exists()) return // never paired: stay quiet
        MonitorService.ensureRunning(context)
    }
}
