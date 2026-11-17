package com.varizpardaz.listener.app.guide

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Where a "fix" or "open settings" button ended up. */
enum class Opened { REQUESTED_SCREEN, FALLBACK, NOTHING }

/**
 * Opens system and maker settings screens. Every call is a best effort: a phone maker's screen may be missing, not exported or
 * renamed in a newer version, and the user must always land somewhere useful, so each opener has a fallback.
 * Nothing here needs a package-visibility declaration, because the screens are started directly instead of being looked up first.
 */
object SettingsLauncher {
    fun openAppSettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))

    /** The system's own "app notifications" page: where POST_NOTIFICATIONS is switched on below Android 13, or after two refusals. */
    fun openAppNotificationSettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) ||
            openAppSettings(context)

    fun openNotificationListenerSettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) || openAppSettings(context)

    /**
     * Asks to exempt the app from battery optimisation (one system dialog, "Allow"/"Deny"). If the phone refuses to show it,
     * the list of all apps with that setting opens instead.
     */
    @SuppressLint("BatteryLife") // the reason: this app's whole job is to stay running in the background
    fun requestIgnoreBatteryOptimizations(context: Context): Opened = when {
        start(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))) ->
            Opened.REQUESTED_SCREEN
        start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) -> Opened.FALLBACK
        openAppSettings(context) -> Opened.FALLBACK
        else -> Opened.NOTHING
    }

    /** Android 11+: the page with the switch that stops the system taking permissions away from an app that is not opened for months. */
    fun openUnusedAppPauseSettings(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 30 &&
            (start(context, Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.fromParts("package", context.packageName, null))) || openAppSettings(context))

    /** Tries each maker screen in turn, then the app's own settings page. */
    fun openMakerScreen(context: Context, components: List<SettingsComponent>): Opened {
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        for (component in components) {
            val intent = Intent().setComponent(ComponentName(component.packageName, component.className))
            component.stringExtras.forEach { (key, value) ->
                intent.putExtra(key, value.replace(MakerGuides.PACKAGE_PLACEHOLDER, context.packageName).replace(MakerGuides.LABEL_PLACEHOLDER, label))
            }
            if (start(context, intent)) return Opened.REQUESTED_SCREEN
        }
        return if (openAppSettings(context)) Opened.FALLBACK else Opened.NOTHING
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false // a screen of another app that is not exported to us
    }
}
