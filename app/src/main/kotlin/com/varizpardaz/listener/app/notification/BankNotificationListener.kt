package com.varizpardaz.listener.app.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.varizpardaz.listener.app.ListenerApp
import com.varizpardaz.listener.app.upload.DeviceState
import com.varizpardaz.listener.core.intake.NotificationBody

/** The user's choice to read bank-app notifications. Off by default; reset when the device is unpaired. */
class NotificationSourceSetting(private val state: DeviceState) {
    var enabled: Boolean
        get() = state.notificationSourceEnabled
        set(value) {
            state.notificationSourceEnabled = value
        }
}

/**
 * Optional second source for banks that send an app notification instead of an SMS. The system only binds this service when
 * the user grants notification access, and it does nothing unless the in-app switch is on as well. For every notification it
 * first asks whether the package is one of the config's `notification_packages`; only then is the text read, so notifications
 * of other apps are never looked at. Everything after that is the same pipeline as for SMS.
 */
class BankNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val container = (application as ListenerApp).container
        if (!container.notificationSource.enabled) return
        val notification = sbn.notification ?: return
        // A persistent status entry or a group header is not a transaction.
        if (sbn.isOngoing || notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        container.intake.submitNotification(sbn.packageName, sbn.key, sbn.postTime) { textOf(notification) }
    }

    private fun textOf(notification: Notification): String? {
        val extras = notification.extras ?: return null
        return NotificationBody.compose(
            title = extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: extras.getCharSequence(Notification.EXTRA_TITLE),
            text = extras.getCharSequence(Notification.EXTRA_TEXT),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList().orEmpty(),
        )
    }
}
