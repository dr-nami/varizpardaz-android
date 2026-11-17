package com.varizpardaz.listener.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.varizpardaz.listener.app.guide.SettingsLauncher
import com.varizpardaz.listener.app.monitor.PermissionState
import com.varizpardaz.listener.app.status.ChecklistId

/** What each "fix" button does, and whether a permission dialog was refused (which is when the restricted-settings hint matters). */
class ChecklistActions(val fix: (ChecklistId) -> Unit, val permissionRefused: Boolean)

/**
 * A fix button asks for the permission with the system dialog. If the system will not show that dialog any more (two refusals,
 * or Android 13+ "restricted settings" on a sideloaded app), the same button opens the app's settings page instead.
 */
@Composable
fun rememberChecklistActions(onChanged: () -> Unit, openBatteryGuide: () -> Unit): ChecklistActions {
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    var refused by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        refused = results.values.any { !it }
        onChanged()
    }

    fun askFor(vararg permissions: String) {
        val missing = permissions.filter { !PermissionState.granted(context, it) }
        if (missing.isEmpty()) return onChanged()
        val activity = context.findActivity()
        val blocked = asked && activity != null && missing.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (blocked) {
            SettingsLauncher.openAppSettings(context)
        } else {
            asked = true
            request.launch(missing.toTypedArray())
        }
    }

    val fix: (ChecklistId) -> Unit = { id ->
        when (id) {
            ChecklistId.RECEIVE_SMS, ChecklistId.READ_SMS -> askFor(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
            ChecklistId.POST_NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= 33) askFor(Manifest.permission.POST_NOTIFICATIONS) else SettingsLauncher.openAppNotificationSettings(context)
            ChecklistId.BATTERY -> SettingsLauncher.requestIgnoreBatteryOptimizations(context)
            // The "Restricted" choice lives in the app's own settings page (Battery).
            ChecklistId.BACKGROUND_RESTRICTION -> SettingsLauncher.openAppSettings(context)
            ChecklistId.UNUSED_APP_PAUSE -> SettingsLauncher.openUnusedAppPauseSettings(context)
            ChecklistId.MAKER_STEPS -> openBatteryGuide()
            ChecklistId.NOTIFICATION_ACCESS -> SettingsLauncher.openNotificationListenerSettings(context)
        }
    }
    return ChecklistActions(fix, refused)
}
