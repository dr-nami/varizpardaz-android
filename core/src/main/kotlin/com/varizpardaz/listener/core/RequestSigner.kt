package com.varizpardaz.listener.core

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Headers of a signed request, contract section 4. */
class SignedHeaders(
    val deviceId: String,
    val timestamp: String,
    val nonce: String,
    val signature: String,
) {
    fun asMap(): Map<String, String> = linkedMapOf(
        HEADER_DEVICE to deviceId,
        HEADER_TIMESTAMP to timestamp,
        HEADER_NONCE to nonce,
        HEADER_SIGNATURE to signature,
    )

    // The signature is not a secret, but keep it out of logs anyway.
    override fun toString(): String = "SignedHeaders(deviceId=$deviceId, timestamp=$timestamp)"

    companion object {
        const val HEADER_DEVICE = "X-PG-Device"
        const val HEADER_TIMESTAMP = "X-PG-Timestamp"
        const val HEADER_NONCE = "X-PG-Nonce"
        const val HEADER_SIGNATURE = "X-PG-Signature"
    }
}

/**
 * Signs requests as `base64url(HMAC-SHA256(device_secret, canonical))` where
 *
 *     canonical = "v1\nMETHOD\nPATH_WITH_QUERY\ntimestamp\nnonce\nhex(sha256(raw_body_bytes))
 *
 * [secret] is the *decoded* 32-byte `device_secret` (the pairing response carries it as base64url).
 * [clockOffsetSeconds] is `server_time - local_time`, learned from `CLOCK_SKEW`, so signatures stay valid
 * on phones with a wrong clock.
 */
class RequestSigner(
    private val deviceId: String,
    secret: ByteArray,
    private val clockOffsetSeconds: () -> Long = { 0L },
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val newNonce: () -> ByteArray = { ByteArray(NONCE_BYTES).also(random::nextBytes) },
) {
    private val key = SecretKeySpec(secret.copyOf(), HMAC)

    /** [pathWithQuery] is exactly what goes on the request line (`/v1/devices/config`, `/x?y=1`). */
    fun sign(method: String, pathWithQuery: String, body: ByteArray = ByteArray(0)): SignedHeaders {
        val timestamp = (nowSeconds() + clockOffsetSeconds()).toString()
        val nonce = Base64Url.encode(newNonce())
        val canonical = canonicalString(method, pathWithQuery, timestamp, nonce, body)
        return SignedHeaders(deviceId, timestamp, nonce, Base64Url.encode(hmac(canonical.toByteArray(Charsets.UTF_8))))
    }

    private fun hmac(data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).apply { init(key) }.doFinal(data)

    companion object {
        private const val HMAC = "HmacSHA256"
        private const val NONCE_BYTES = 16
        private val random = SecureRandom()

        fun canonicalString(
            method: String,
            pathWithQuery: String,
            timestamp: String,
            nonce: String,
            body: ByteArray,
        ): String = listOf("v1", method.uppercase(), pathWithQuery, timestamp, nonce, sha256(body).toHex())
            .joinToString("\n")
    }
}
