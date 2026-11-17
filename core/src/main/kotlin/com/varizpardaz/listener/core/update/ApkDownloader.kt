package com.varizpardaz.listener.core.update

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class DownloadResponse(
    val status: Int,
    /** From the `Content-Length` header, if the server sent one. */
    val contentLength: Long?,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        body.close()
        onClose()
    }
}

fun interface DownloadTransport {
    /** Blocking. Throws [IOException] when there is no HTTP response; any HTTP status is a normal return value. */
    @Throws(IOException::class)
    fun open(url: String): DownloadResponse
}

sealed interface DownloadOutcome {
    /** [file] is complete and its SHA-256 equals the one in the signed config. */
    class Verified(val file: File, val bytes: Long) : DownloadOutcome

    /** The bytes received are not the file the config named. Nothing is kept. */
    data object ChecksumMismatch : DownloadOutcome

    data object TooLarge : DownloadOutcome

    class HttpError(val status: Int) : DownloadOutcome

    /** No response, a dropped connection, or fewer bytes than the server promised. */
    data object NetworkError : DownloadOutcome

    data object Cancelled : DownloadOutcome
}

/**
 * Fetches an update APK to a file while hashing it, and keeps the file only if its SHA-256 is the one in the signed config.
 * The bytes go to `<target>.part` and are moved to [target] only after the check, so a half-written or wrong file is never
 * where the installer could pick it up.
 */
class ApkDownloader(
    private val transport: DownloadTransport,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    fun download(
        offer: UpdateOffer,
        target: File,
        cancelled: () -> Boolean = { false },
        onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val part = File(target.path + PART_SUFFIX)
        try {
            val response = try {
                transport.open(offer.url)
            } catch (_: IOException) {
                return DownloadOutcome.NetworkError
            }
            response.use {
                if (it.status != 200) return DownloadOutcome.HttpError(it.status)
                val declared = it.contentLength?.takeIf { length -> length >= 0 }
                if (declared != null && declared > maxBytes) return DownloadOutcome.TooLarge

                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                try {
                    target.absoluteFile.parentFile?.mkdirs()
                    part.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            if (cancelled()) return DownloadOutcome.Cancelled
                            val n = it.body.read(buffer)
                            if (n < 0) break
                            received += n
                            if (received > maxBytes) return DownloadOutcome.TooLarge
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                            onProgress(received, declared)
                        }
                    }
                } catch (_: IOException) {
                    return DownloadOutcome.NetworkError
                }
                if (declared != null && declared != received) return DownloadOutcome.NetworkError // cut short

                // Constant-time comparison; the expected value comes from the signed config, so this is about integrity, not secrecy.
                if (!MessageDigest.isEqual(digest.digest(), Sha256Hex.toBytes(offer.sha256))) return DownloadOutcome.ChecksumMismatch
                try {
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (_: IOException) {
                    return DownloadOutcome.NetworkError // the disk is the problem, but the user-facing advice is the same: try again
                }
                return DownloadOutcome.Verified(target, received)
            }
        } finally {
            part.delete()
        }
    }

    companion object {
        /** An update of this app is a few MB; anything near this is wrong. */
        const val DEFAULT_MAX_BYTES = 150L * 1024 * 1024
        const val PART_SUFFIX = ".part"
        private const val BUFFER_BYTES = 64 * 1024
    }
}
