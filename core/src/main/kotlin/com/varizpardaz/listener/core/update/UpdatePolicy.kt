package com.varizpardaz.listener.core.update

import com.varizpardaz.listener.core.AppUpdate
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** An update the phone can fetch: an https link and the exact SHA-256 of the file, both from the signed config. */
class UpdateOffer(
    val versionCode: Int,
    val url: String,
    /** Lower-case hex, 64 characters. */
    val sha256: String,
)

/** What `app_update` of the signed config means for the installed version. */
sealed interface UpdateState {
    data object None : UpdateState

    /** A newer version exists. The phone keeps working as before. */
    class Available(val offer: UpdateOffer) : UpdateState

    /**
     * The installed version is below `min_version_code`: the phone keeps queueing but does not upload until it is updated.
     * [offer] is null when the config's link or checksum is unusable, so the update has to be installed by hand.
     */
    class Required(val offer: UpdateOffer?) : UpdateState
}

object UpdatePolicy {
    fun evaluate(update: AppUpdate?, currentVersionCode: Int, allowInsecureHttp: Boolean = false): UpdateState {
        update ?: return UpdateState.None
        // A minimum above the newest version cannot be satisfied by any update: a server mistake. Obeying it would stop a phone
        // for good, so it is ignored (the phone is then only offered the newest version, if that is newer).
        val required = currentVersionCode < update.minVersionCode && update.latestVersionCode >= update.minVersionCode
        val offer = offerOf(update, currentVersionCode, allowInsecureHttp)
        return when {
            required -> UpdateState.Required(offer)
            offer != null -> UpdateState.Available(offer)
            else -> UpdateState.None
        }
    }

    fun blocksUploads(state: UpdateState): Boolean = state is UpdateState.Required

    private fun offerOf(update: AppUpdate, currentVersionCode: Int, allowInsecureHttp: Boolean): UpdateOffer? {
        if (update.latestVersionCode <= currentVersionCode) return null
        val sha = Sha256Hex.normalise(update.sha256) ?: return null
        if (!isAcceptableUrl(update.url, allowInsecureHttp)) return null
        return UpdateOffer(update.latestVersionCode, update.url, sha)
    }

    /** https only (plain http only for the development mock), a host, and no credentials in the link. */
    internal fun isAcceptableUrl(url: String, allowInsecureHttp: Boolean): Boolean {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val schemeOk = scheme == "https" || (allowInsecureHttp && scheme == "http")
        return schemeOk && !uri.host.isNullOrBlank() && uri.userInfo == null
    }
}

object Sha256Hex {
    private val HEX = Regex("[0-9a-fA-F]{64}")

    /** The checksum as lower-case hex, or null if it is not 64 hex digits (surrounding spaces are ignored). */
    fun normalise(value: String): String? = value.trim().takeIf { HEX.matches(it) }?.lowercase(Locale.ROOT)

    fun toBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun fromBytes(bytes: ByteArray): String = buildString(bytes.size * 2) { bytes.forEach { append("%02x".format(it)) } }
}

/** What an APK file says about itself, checked against what the config promised, before the system installer ever sees it. */
object ArchiveCheck {
    enum class Problem { WRONG_PACKAGE, WRONG_VERSION }

    /** `null` means the file is the update that was promised. */
    fun check(expectedPackage: String, offer: UpdateOffer, actualPackage: String?, actualVersionCode: Long?): Problem? = when {
        actualPackage != expectedPackage -> Problem.WRONG_PACKAGE
        actualVersionCode != offer.versionCode.toLong() -> Problem.WRONG_VERSION
        else -> null
    }
}
