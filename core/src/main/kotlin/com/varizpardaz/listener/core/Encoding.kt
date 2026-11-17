package com.varizpardaz.listener.core

import java.security.MessageDigest
import java.util.Base64

/** base64url without padding on output; padding is tolerated on input. */
object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** Returns null when [text] is not valid base64url. */
    fun decodeOrNull(text: String): ByteArray? =
        try {
            decoder.decode(text.trimEnd('='))
        } catch (_: IllegalArgumentException) {
            null
        }
}

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = digits[v ushr 4]
        out[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(out)
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
