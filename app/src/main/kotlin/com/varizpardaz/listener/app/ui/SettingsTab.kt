package com.varizpardaz.listener.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.varizpardaz.listener.app.BuildConfig
import com.varizpardaz.listener.app.R
import com.varizpardaz.listener.app.data.AppContainer
import com.varizpardaz.listener.app.guide.SettingsLauncher
import com.varizpardaz.listener.app.monitor.PermissionState
import com.varizpardaz.listener.app.notification.NotificationSourceSetting
import com.varizpardaz.listener.core.pairing.DeviceCredentials
import kotlinx.coroutines.launch
import java.net.URI

@Composable
fun SettingsTab(container: AppContainer, credentials: DeviceCredentials, onUnpair: suspend () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val configVersion by rememberPolled { container.configs.current(credentials.serverPublicKey)?.version ?: NO_CONFIG }
    val numbers = rememberPersianNumbers()

    TabColumn {
        SectionCard {
            LabeledValue(stringResource(R.string.paired_server), serverLabel(credentials.baseUrl))
            LabeledValue(stringResource(R.string.paired_device_id), credentials.deviceId)
            LabeledValue(stringResource(R.string.settings_app_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            StatRow(
                stringResource(R.string.settings_config_version),
                configVersion?.takeIf { it != NO_CONFIG }?.let { numbers.format(it) } ?: stringResource(R.string.settings_config_none),
            )
        }

        NotificationSourceCard(container.notificationSource)

        OutlinedButton(
            onClick = { confirming = true },
            enabled = !busy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.unpair_button)) }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.unpair_confirm_title)) },
            text = { Text(stringResource(R.string.unpair_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        busy = true
                        scope.launch { onUnpair() }
                    },
                ) { Text(stringResource(R.string.unpair_confirm_action), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private const val NO_CONFIG = -1L

/** The optional second source: bank-app notifications. Off until the user switches it on and grants notification access. */
@Composable
private fun NotificationSourceCard(setting: NotificationSourceSetting) {
    val context = LocalContext.current
    var enabled by rememberSaveable { mutableStateOf(setting.enabled) }
    var access by remember { mutableStateOf(PermissionState.notificationListener(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = PermissionState.notificationListener(context) }

    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.notif_source_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    setting.enabled = it
                },
            )
        }
        Text(stringResource(R.string.notif_source_body), style = MaterialTheme.typography.bodyMedium)
        if (enabled && access) {
            Text(stringResource(R.string.notif_source_active), color = StatusColors.Good)
        } else if (enabled) {
            Text(stringResource(R.string.notif_source_need_access), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { SettingsLauncher.openNotificationListenerSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.notif_source_open_settings)) }
            Text(stringResource(R.string.notif_source_hint_restricted), style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun serverLabel(baseUrl: String): String =
    runCatching { URI(baseUrl).let { if (it.port > 0) "${it.host}:${it.port}" else it.host } }.getOrNull() ?: baseUrl
