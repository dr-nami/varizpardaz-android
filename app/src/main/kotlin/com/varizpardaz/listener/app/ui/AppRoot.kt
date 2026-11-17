package com.varizpardaz.listener.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.varizpardaz.listener.app.R
import com.varizpardaz.listener.app.data.AppContainer
import com.varizpardaz.listener.app.data.PairingState
import com.varizpardaz.listener.core.pairing.DeviceCredentials
import com.varizpardaz.listener.core.update.UpdateState

@Composable
fun AppRoot(container: AppContainer) {
    val state by container.pairing.state.collectAsStateWithLifecycle()
    when (val s = state) {
        PairingState.Loading -> LoadingScreen()
        PairingState.NotPaired -> PairingRoute(container)
        is PairingState.Paired -> PairedRoot(container, s.credentials)
        is PairingState.NeedsRepair -> RepairScreen(s.reason, onRepair = container.pairing::unpair)
    }
}

/** The paired app: the tabs, or the update-required screen while the installed version is below the server's minimum. */
@Composable
private fun PairedRoot(container: AppContainer, credentials: DeviceCredentials) {
    val update by rememberPolled(periodMillis = 3_000) { container.updates.current(credentials.serverPublicKey) }
    when (val state = update) {
        null -> LoadingScreen() // not read yet; never flash the tabs of a phone that has to update first
        is UpdateState.Required -> UpdateRequiredScreen(container.updates, state)
        else -> MainShell(container, credentials, onUnpair = container.pairing::unpair)
    }
}

@Composable
private fun PairingRoute(container: AppContainer) {
    val vm: PairingViewModel = viewModel(
        factory = viewModelFactory { initializer { PairingViewModel(container.pairing) } },
    )
    val ui by vm.ui.collectAsState()
    val prompt = stringResource(R.string.pair_scan_prompt)

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        when {
            contents != null -> vm.onLinkText(contents)
            result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true -> vm.onCameraDenied()
            else -> Unit // the user backed out
        }
    }
    val startScan = {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(prompt)
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .setCaptureActivity(ScannerActivity::class.java),
        )
    }
    // Ask for the camera ourselves, so a "no" gets our Persian explanation instead of the library's generic dialog.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startScan() else vm.onCameraDenied()
    }
    val context = LocalContext.current

    PairingScreen(
        ui = ui,
        onScanClick = {
            vm.dismissError()
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (granted) startScan() else cameraPermission.launch(Manifest.permission.CAMERA)
        },
        onLinkText = vm::onLinkText,
        onDismissError = vm::dismissError,
    )
}
