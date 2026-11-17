package com.varizpardaz.listener.core.pairing

import com.varizpardaz.listener.core.Base64Url
import com.varizpardaz.listener.core.PairingLink
import com.varizpardaz.listener.core.http.HttpRequest
import com.varizpardaz.listener.core.http.HttpTransport
import com.varizpardaz.listener.core.wire.ErrorCode
import com.varizpardaz.listener.core.wire.ErrorEnvelope
import com.varizpardaz.listener.core.wire.PairRequest
import com.varizpardaz.listener.core.wire.PairResponse
import com.varizpardaz.listener.core.wire.Wire
import kotlinx.serialization.SerializationException
import java.io.IOException
import javax.net.ssl.SSLException

/** What a paired phone holds. [toString] never prints the secret. */
class DeviceCredentials(
    val baseUrl: String,
    val deviceId: String,
    /** Decoded 32-byte HMAC key. */
    val deviceSecret: ByteArray,
    /** Pinned Ed25519 key (base64url) that must have signed every config. */
    val serverPublicKey: String,
    val heartbeatIntervalSec: Int,
) {
    override fun toString(): String = "DeviceCredentials(deviceId=$deviceId, baseUrl=$baseUrl)"
}

/** What the phone says about itself when pairing. */
class DeviceInfo(val name: String, val osVersion: String, val appVersionCode: Int)

/** Why pairing failed. The UI maps each to a Persian message; nothing here is user-facing text. */
sealed interface PairingFailure {
    /** `PAIRING_CODE_INVALID`: wrong, used or expired (codes live 10 minutes, single use). */
    data object CodeInvalid : PairingFailure

    /** 429 / `RATE_LIMITED`. */
    class RateLimited(val retryAfterSec: Int?) : PairingFailure

    /** No HTTP response: no route, DNS, refused, timeout. */
    data object Network : PairingFailure

    /** TLS handshake or certificate problem. */
    data object Tls : PairingFailure

    /** 5xx. */
    class Server(val status: Int) : PairingFailure

    /** A success status, but a body the phone cannot trust (missing or wrongly sized secret or key). */
    data object BadResponse : PairingFailure

    /** Any other 4xx. [message] is the server's Persian, show-safe text, if it sent one. */
    class Rejected(val status: Int, val code: String?, val message: String?) : PairingFailure
}

sealed interface PairingOutcome {
    class Success(val credentials: DeviceCredentials) : PairingOutcome

    class Failure(val failure: PairingFailure) : PairingOutcome
}

/** `POST /v1/devices/pair` (contract section 3). Unauthenticated: the one-time code is the credential. */
class PairingService(
    private val transport: HttpTransport,
    private val device: DeviceInfo,
) {
    fun pair(link: PairingLink): PairingOutcome {
        val body = Wire.json.encodeToString(
            PairRequest.serializer(),
            PairRequest(
                code = link.code,
                deviceName = device.name,
                osVersion = device.osVersion,
                appVersionCode = device.appVersionCode,
            ),
        ).toByteArray(Charsets.UTF_8)

        val response = try {
            transport.execute(
                HttpRequest(
                    method = "POST",
                    url = link.baseUrl + PAIR_PATH,
                    headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                    body = body,
                ),
            )
        } catch (_: SSLException) {
            return PairingOutcome.Failure(PairingFailure.Tls)
        } catch (_: IOException) {
            return PairingOutcome.Failure(PairingFailure.Network)
        }

        return when {
            response.status in 200..299 -> parseSuccess(link, response.body)
            else -> PairingOutcome.Failure(parseFailure(response.status, response.header("Retry-After"), response.body))
        }
    }

    private fun parseSuccess(link: PairingLink, body: ByteArray): PairingOutcome {
        val parsed = try {
            Wire.json.decodeFromString(PairResponse.serializer(), body.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return bad()
        } catch (_: IllegalArgumentException) {
            return bad()
        }
        val secret = Base64Url.decodeOrNull(parsed.deviceSecret)
        val publicKey = Base64Url.decodeOrNull(parsed.serverPublicKey)
        if (parsed.deviceId.isBlank() || secret == null || secret.size != SECRET_BYTES ||
            publicKey == null || publicKey.size != PUBLIC_KEY_BYTES
        ) {
            return bad()
        }
        val interval = parsed.heartbeatIntervalSec.takeIf { it in MIN_INTERVAL_SEC..MAX_INTERVAL_SEC } ?: DEFAULT_INTERVAL_SEC
        return PairingOutcome.Success(
            DeviceCredentials(
                baseUrl = link.baseUrl,
                deviceId = parsed.deviceId,
                deviceSecret = secret,
                serverPublicKey = Base64Url.encode(publicKey), // canonical form, no padding
                heartbeatIntervalSec = interval,
            ),
        )
    }

    private fun bad() = PairingOutcome.Failure(PairingFailure.BadResponse)

    private fun parseFailure(status: Int, retryAfter: String?, body: ByteArray): PairingFailure {
        val envelope = try {
            Wire.json.decodeFromString(ErrorEnvelope.serializer(), body.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        val code = envelope?.error?.code
        return when {
            ErrorCode.of(code.orEmpty()) == ErrorCode.PAIRING_CODE_INVALID -> PairingFailure.CodeInvalid
            status == 429 || ErrorCode.of(code.orEmpty()) == ErrorCode.RATE_LIMITED ->
                PairingFailure.RateLimited(retryAfter?.trim()?.toIntOrNull()?.takeIf { it > 0 })
            status >= 500 -> PairingFailure.Server(status)
            else -> PairingFailure.Rejected(status, code, envelope?.error?.message?.takeIf { it.isNotBlank() })
        }
    }

    companion object {
        const val PAIR_PATH = "/v1/devices/pair"
        private const val SECRET_BYTES = 32
        private const val PUBLIC_KEY_BYTES = 32
        private const val MIN_INTERVAL_SEC = 5
        private const val MAX_INTERVAL_SEC = 3600
        private const val DEFAULT_INTERVAL_SEC = 30
    }
}
