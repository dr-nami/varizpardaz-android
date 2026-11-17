package com.varizpardaz.listener.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.varizpardaz.listener.app.BuildConfig
import com.varizpardaz.listener.app.R
import com.varizpardaz.listener.app.update.UpdateFailure
import com.varizpardaz.listener.app.update.UpdateManager
import com.varizpardaz.listener.app.update.UpdateProgress
import com.varizpardaz.listener.core.update.UpdateOffer
import com.varizpardaz.listener.core.update.UpdateState

/** An optional update, shown at the top of the status tab. The phone keeps working while the user decides. */
@Composable
fun UpdateAvailableCard(manager: UpdateManager, offer: UpdateOffer) {
    SectionCard(background = StatusColors.WarnBackground) {
        Text(stringResource(R.string.update_available_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.update_available_body), style = MaterialTheme.typography.bodyMedium)
        UpdateControls(manager, offer)
    }
}

/**
 * The screen that replaces the app while the installed version is below the server's minimum. Uploads are stopped (messages wait in
 * the queue); this is where the user fixes that.
 */
@Composable
fun UpdateRequiredScreen(manager: UpdateManager, state: UpdateState.Required) {
    val numbers = rememberPersianNumbers()
    ScreenColumn {
        BrandMark()
        Text(stringResource(R.string.update_required_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.update_required_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionCard {
            StatRow(stringResource(R.string.update_installed_version), numbers.format(BuildConfig.VERSION_CODE))
            state.offer?.let { StatRow(stringResource(R.string.update_new_version), numbers.format(it.versionCode)) }
        }
        val offer = state.offer
        if (offer != null) {
            UpdateControls(manager, offer)
        } else {
            SectionCard(background = MaterialTheme.colorScheme.errorContainer) {
                Text(stringResource(R.string.update_required_manual), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/** The button, progress and error text for one update attempt. Shared by the card and the required screen. */
@Composable
fun UpdateControls(manager: UpdateManager, offer: UpdateOffer) {
    val context = LocalContext.current
    val progress by manager.progress.collectAsStateWithLifecycle()
    // The user may have just allowed installs from this app in system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { manager.resume() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val p = progress) {
            UpdateProgress.Idle -> StartButton(R.string.update_button) { manager.start(offer) }
            is UpdateProgress.Downloading -> {
                val fraction = p.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.update_downloading_percent, rememberPersianNumbers().format((fraction * 100).toInt())),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.update_downloading), style = MaterialTheme.typography.bodyMedium)
                }
            }
            UpdateProgress.NeedsInstallPermission -> {
                Text(stringResource(R.string.update_permission_needed), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null)),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.update_open_install_settings)) }
            }
            UpdateProgress.WaitingForInstaller -> {
                Text(stringResource(R.string.update_waiting_installer), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { manager.start(offer) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_retry)) }
            }
            is UpdateProgress.Failed -> {
                Text(stringResource(p.reason.message()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                StartButton(R.string.update_retry) { manager.start(offer) }
            }
        }
    }
}

@Composable
private fun StartButton(label: Int, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(label)) }
}

private fun UpdateFailure.message(): Int = when (this) {
    UpdateFailure.NETWORK -> R.string.update_err_network
    UpdateFailure.SERVER -> R.string.update_err_server
    UpdateFailure.CHECKSUM -> R.string.update_err_checksum
    UpdateFailure.TOO_LARGE -> R.string.update_err_too_large
    UpdateFailure.WRONG_FILE -> R.string.update_err_wrong_file
    UpdateFailure.STORAGE -> R.string.update_err_storage
    UpdateFailure.INSTALL_ABORTED -> R.string.update_err_aborted
    UpdateFailure.INSTALL_BLOCKED -> R.string.update_err_blocked
    UpdateFailure.INSTALL_CONFLICT -> R.string.update_err_conflict
    UpdateFailure.INSTALL_INCOMPATIBLE -> R.string.update_err_incompatible
    UpdateFailure.INSTALL_OTHER -> R.string.update_err_other
}
