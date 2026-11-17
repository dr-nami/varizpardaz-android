package com.varizpardaz.listener.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import java.io.IOException

/**
 * Hands a verified APK to the system installer through a [PackageInstaller] session. The user confirms in the system's own
 * dialog; the outcome comes back to [InstallResultReceiver]. Going through a session (rather than a file link) needs no
 * file provider and reports why an install failed.
 */
class ApkInstaller(private val context: Context) {
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    @Throws(IOException::class)
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(SESSION_FILE, 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // Mutable on purpose: the system adds the status and the confirmation intent. The intent is explicit, as Android 14 requires.
                val result = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(result.intentSender)
            }
        } catch (e: IOException) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        } catch (e: RuntimeException) {
            runCatching { installer.abandonSession(sessionId) }
            throw IOException("installer session failed", e)
        }
    }

    private companion object {
        const val SESSION_FILE = "base.apk"
    }
}
