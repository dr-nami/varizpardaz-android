package com.varizpardaz.listener.app.update

import android.content.pm.PackageInstaller

/** Why an update did not go through. The screen turns each into its own Persian message. */
enum class UpdateFailure {
    NETWORK,
    SERVER,
    CHECKSUM,
    TOO_LARGE,
    WRONG_FILE,
    STORAGE,

    /** The user closed or refused the system's install dialog. */
    INSTALL_ABORTED,

    /** The phone's policy or its security scan blocked the install. */
    INSTALL_BLOCKED,

    /** The installed app and the new file do not fit together (typically a different signing key). */
    INSTALL_CONFLICT,
    INSTALL_INCOMPATIBLE,
    INSTALL_OTHER,
}

sealed interface UpdateProgress {
    data object Idle : UpdateProgress

    class Downloading(val received: Long, val total: Long?) : UpdateProgress {
        /** 0..1, or null when the server did not say how big the file is. */
        val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (received.toFloat() / it).coerceIn(0f, 1f) }
    }

    /** Downloaded and verified, but Android will not install from this app until the user allows it in settings. */
    data object NeedsInstallPermission : UpdateProgress

    /** Handed to the system installer; the user confirms in its own dialog. */
    data object WaitingForInstaller : UpdateProgress

    class Failed(val reason: UpdateFailure) : UpdateProgress
}

object InstallStatus {
    fun failureOf(status: Int): UpdateFailure = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateFailure.INSTALL_ABORTED
        PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateFailure.INSTALL_BLOCKED
        PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateFailure.INSTALL_CONFLICT
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateFailure.INSTALL_INCOMPATIBLE
        PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateFailure.STORAGE
        else -> UpdateFailure.INSTALL_OTHER
    }
}
