package com.varizpardaz.listener.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.varizpardaz.listener.app.ListenerApp

/** The system installer's answers for an update session started by [ApkInstaller]. Not exported: only our own pending intent reaches it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = (context.applicationContext as ListenerApp).container.updates
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system wants the user to confirm. The app is on screen (the user just tapped the button), so it may start the dialog.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } else {
                    updates.onInstallResult(PackageInstaller.STATUS_FAILURE)
                }
            }
            else -> updates.onInstallResult(status)
        }
    }
}
