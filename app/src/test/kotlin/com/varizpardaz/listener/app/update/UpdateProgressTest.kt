package com.varizpardaz.listener.app.update

import android.content.pm.PackageInstaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateProgressTest {
    @Test
    fun `every installer failure status has its own advice, anything unknown falls back to a generic one`() {
        assertEquals(UpdateFailure.INSTALL_ABORTED, InstallStatus.failureOf(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(UpdateFailure.INSTALL_BLOCKED, InstallStatus.failureOf(PackageInstaller.STATUS_FAILURE_BLOCKED))
        assertEquals(UpdateFailure.INSTALL_CONFLICT, InstallStatus.failureOf(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(UpdateFailure.INSTALL_INCOMPATIBLE, InstallStatus.failureOf(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE))
        assertEquals(UpdateFailure.STORAGE, InstallStatus.failureOf(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertEquals(UpdateFailure.INSTALL_OTHER, InstallStatus.failureOf(PackageInstaller.STATUS_FAILURE))
        assertEquals(UpdateFailure.INSTALL_OTHER, InstallStatus.failureOf(PackageInstaller.STATUS_FAILURE_INVALID))
        assertEquals(UpdateFailure.INSTALL_OTHER, InstallStatus.failureOf(12345))
    }

    @Test
    fun `download progress is a fraction between 0 and 1, or unknown without a length`() {
        assertEquals(0.5f, UpdateProgress.Downloading(50, 100).fraction)
        assertEquals(1f, UpdateProgress.Downloading(150, 100).fraction, "more than announced must not exceed 100 percent")
        assertEquals(0f, UpdateProgress.Downloading(0, 100).fraction)
        assertNull(UpdateProgress.Downloading(50, null).fraction)
        assertNull(UpdateProgress.Downloading(50, 0).fraction)
    }
}
