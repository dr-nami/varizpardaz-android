package com.varizpardaz.listener.app.monitor

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteException
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Telephony
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.varizpardaz.listener.core.heartbeat.DeviceSnapshot
import com.varizpardaz.listener.core.rescan.InboxReader
import com.varizpardaz.listener.core.rescan.InboxSms
import com.varizpardaz.listener.core.wire.Permissions

object PermissionState {
    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun receiveSms(context: Context) = granted(context, Manifest.permission.RECEIVE_SMS)

    fun readSms(context: Context) = granted(context, Manifest.permission.READ_SMS)

    fun postNotifications(context: Context) =
        if (Build.VERSION.SDK_INT >= 33) granted(context, Manifest.permission.POST_NOTIFICATIONS) else NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun notificationListener(context: Context) =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun ignoringBatteryOptimizations(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** True when the app is set to "Restricted" in its battery settings (Android 9+); below that the setting does not exist. */
    fun backgroundRestricted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    /**
     * Whether the user has exempted the app from "pause app activity if unused", which otherwise takes the SMS permission back
     * after months without the app being opened. `null` below Android 11, where the setting does not exist.
     */
    fun exemptFromUnusedAppPause(context: Context): Boolean? =
        if (Build.VERSION.SDK_INT >= 30) context.packageManager.isAutoRevokeWhitelisted else null

    fun all(context: Context) = Permissions(
        receiveSms = receiveSms(context),
        readSms = readSms(context),
        notificationListener = notificationListener(context),
        postNotifications = postNotifications(context),
        ignoreBatteryOptimizations = ignoringBatteryOptimizations(context),
    )
}

/** Battery, network, permissions and SIMs for the heartbeat. Reads state only; nothing here needs a dangerous permission. */
class DeviceSnapshotProvider(
    private val context: Context,
    private val sims: SimRegistry,
    /** The in-app switch for bank-app notifications. */
    private val notificationSourceEnabled: () -> Boolean,
) {
    fun snapshot(): DeviceSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return DeviceSnapshot(
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = battery?.let { status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL },
            network = network(),
            // The channel counts as available only if the system grants access and the user switched it on.
            permissions = PermissionState.all(context).let { it.copy(notificationListener = it.notificationListener && notificationSourceEnabled()) },
            sims = sims.all(),
        )
    }

    private fun network(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }
}

/** Reads `content://sms/inbox` for a rescan window. Needs READ_SMS; returns nothing without it. */
class SmsInboxReader(private val context: Context) : InboxReader {
    override fun read(fromMillis: Long, toMillis: Long): List<InboxSms> {
        if (!PermissionState.readSms(context)) return emptyList()
        // Some phones leave date_sent at 0; their arrival time (`date`) is the best clock they offer.
        val selection = "(date_sent BETWEEN ? AND ?) OR (date_sent = 0 AND date BETWEEN ? AND ?)"
        val args = arrayOf(fromMillis, toMillis, fromMillis, toMillis).map(Long::toString).toTypedArray()
        return try {
            query(arrayOf("address", "body", "date_sent", "date", "sub_id"), selection, args)
        } catch (_: IllegalArgumentException) {
            query(arrayOf("address", "body", "date_sent", "date"), selection, args) // no sub_id column on this phone
        } catch (_: SQLiteException) {
            query(arrayOf("address", "body", "date_sent", "date"), selection, args)
        }
    }

    private fun query(projection: Array<String>, selection: String, args: Array<String>): List<InboxSms> {
        val rows = ArrayList<InboxSms>()
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, selection, args, "date ASC")?.use { cursor ->
            val address = cursor.getColumnIndexOrThrow("address")
            val body = cursor.getColumnIndexOrThrow("body")
            val dateSent = cursor.getColumnIndexOrThrow("date_sent")
            val date = cursor.getColumnIndexOrThrow("date")
            val sub = cursor.getColumnIndex("sub_id")
            while (cursor.moveToNext()) {
                val sent = cursor.getLong(dateSent).takeIf { it > 0 } ?: cursor.getLong(date)
                rows += InboxSms(
                    address = cursor.getString(address),
                    body = cursor.getString(body),
                    dateSentMillis = sent,
                    subscriptionId = if (sub >= 0 && !cursor.isNull(sub)) cursor.getInt(sub).takeIf { it >= 0 } else null,
                )
            }
        }
        return rows
    }
}
