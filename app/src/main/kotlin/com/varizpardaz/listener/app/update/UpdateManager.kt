package com.varizpardaz.listener.app.update

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.core.content.pm.PackageInfoCompat
import com.varizpardaz.listener.app.BuildConfig
import com.varizpardaz.listener.core.configstore.ConfigRepository
import com.varizpardaz.listener.core.update.ApkDownloader
import com.varizpardaz.listener.core.update.ArchiveCheck
import com.varizpardaz.listener.core.update.DownloadOutcome
import com.varizpardaz.listener.core.update.DownloadTransport
import com.varizpardaz.listener.core.update.UpdateOffer
import com.varizpardaz.listener.core.update.UpdatePolicy
import com.varizpardaz.listener.core.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * The in-app update (contract section 5, `app_update`): works out from the signed config whether a newer version exists or the
 * installed one is too old, downloads the file, checks its SHA-256 against the config and that it really is this app at the promised
 * version, and hands it to the system installer. Nothing is installed without the user's confirmation in the system dialog.
 */
class UpdateManager(
    private val context: Context,
    private val configs: ConfigRepository,
    transport: DownloadTransport,
    private val scope: CoroutineScope,
    private val installer: ApkInstaller = ApkInstaller(context),
) {
    private val downloader = ApkDownloader(transport)
    private val directory = File(context.cacheDir, DIRECTORY)
    private val lock = Any()
    private var job: Job? = null

    /** A verified file waiting for the user to allow installs from this app. */
    private var waitingFile: File? = null

    private val _progress = MutableStateFlow<UpdateProgress>(UpdateProgress.Idle)
    val progress: StateFlow<UpdateProgress> = _progress.asStateFlow()

    /** What the signed config says about this installed version. Cheap enough to ask often. */
    fun current(pinnedKey: String): UpdateState =
        UpdatePolicy.evaluate(configs.current(pinnedKey)?.raw?.appUpdate, BuildConfig.VERSION_CODE, BuildConfig.ALLOW_INSECURE_HTTP)

    /** Leftovers from an earlier attempt, or from the update that has just been installed. */
    fun cleanUp() {
        directory.listFiles()?.forEach { it.delete() }
    }

    fun start(offer: UpdateOffer) {
        synchronized(lock) {
            if (job?.isActive == true) return
            _progress.value = UpdateProgress.Downloading(0, null)
            job = scope.launch(Dispatchers.IO) { download(offer) }
        }
    }

    /** Called when the app comes back to the front: the user may have just allowed installs from this app. */
    fun resume() {
        val file = synchronized(lock) {
            if (_progress.value !is UpdateProgress.NeedsInstallPermission || !installer.canInstall()) return
            waitingFile.also { waitingFile = null }
        } ?: return
        scope.launch(Dispatchers.IO) { handOver(file) }
    }

    /** The system installer's verdict ([PackageInstaller] status). On success the app is replaced, so this is rarely seen. */
    fun onInstallResult(status: Int) {
        if (status == PackageInstaller.STATUS_SUCCESS) {
            _progress.value = UpdateProgress.Idle
        } else {
            cleanUp()
            _progress.value = UpdateProgress.Failed(InstallStatus.failureOf(status))
        }
    }

    private fun download(offer: UpdateOffer) {
        cleanUp()
        val target = File(directory, "update-${offer.versionCode}.apk")
        val outcome = downloader.download(
            offer = offer,
            target = target,
            cancelled = { !scope.isActive },
            onProgress = { received, total -> _progress.value = UpdateProgress.Downloading(received, total) },
        )
        when (outcome) {
            is DownloadOutcome.Verified -> afterDownload(offer, outcome.file)
            DownloadOutcome.ChecksumMismatch -> fail(UpdateFailure.CHECKSUM)
            DownloadOutcome.TooLarge -> fail(UpdateFailure.TOO_LARGE)
            is DownloadOutcome.HttpError -> fail(UpdateFailure.SERVER)
            DownloadOutcome.NetworkError -> fail(UpdateFailure.NETWORK)
            DownloadOutcome.Cancelled -> _progress.value = UpdateProgress.Idle
        }
    }

    private fun afterDownload(offer: UpdateOffer, file: File) {
        // The hash says "this is the file the server named". This says that file is this app at the promised version.
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
        val problem = ArchiveCheck.check(context.packageName, offer, info?.packageName, info?.let { PackageInfoCompat.getLongVersionCode(it) })
        if (problem != null) {
            file.delete()
            return fail(UpdateFailure.WRONG_FILE)
        }
        handOver(file)
    }

    private fun handOver(file: File) {
        if (!installer.canInstall()) {
            synchronized(lock) { waitingFile = file }
            _progress.value = UpdateProgress.NeedsInstallPermission
            return
        }
        try {
            installer.install(file)
            _progress.value = UpdateProgress.WaitingForInstaller
        } catch (_: IOException) {
            file.delete()
            fail(UpdateFailure.INSTALL_OTHER)
        }
    }

    private fun fail(reason: UpdateFailure) {
        _progress.value = UpdateProgress.Failed(reason)
    }

    private companion object {
        const val DIRECTORY = "updates"
    }
}
