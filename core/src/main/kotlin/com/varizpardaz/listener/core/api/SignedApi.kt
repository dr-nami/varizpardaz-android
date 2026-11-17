package com.varizpardaz.listener.core.api

import com.varizpardaz.listener.core.RequestSigner
import com.varizpardaz.listener.core.TimestampFormat
import com.varizpardaz.listener.core.http.HttpRequest
import com.varizpardaz.listener.core.http.HttpResponse
import com.varizpardaz.listener.core.http.HttpTransport
import com.varizpardaz.listener.core.pairing.DeviceCredentials
import com.varizpardaz.listener.core.wire.ErrorBody
import com.varizpardaz.listener.core.wire.ErrorCode
import com.varizpardaz.listener.core.wire.ErrorEnvelope
import com.varizpardaz.listener.core.wire.Wire
import kotlinx.serialization.SerializationException
import java.io.IOException

/** What a signed call came to. The contract's section 10 retry rules are already applied when you see these. */
sealed interface ApiResult {
    /** A 2xx answer. */
    class Success(val response: HttpResponse) : ApiResult

    /** `DEVICE_REVOKED`: stop all work, wipe the secret, show the re-pair screen. */
    data object Revoked : ApiResult

    /** `BAD_SIGNATURE` persisted after one retry: the stored secret no longer matches the server's. */
    data object RepairNeeded : ApiResult

    /** Any other non-2xx answer, for the caller to interpret (`VALIDATION_ERROR`, `PAYLOAD_TOO_LARGE`, `RATE_LIMITED`, 5xx...). */
    class Failure(
        val status: Int,
        val code: ErrorCode,
        val error: ErrorBody?,
        /** Seconds from a `Retry-After` header, if the server sent a number. */
        val retryAfterSec: Int?,
    ) : ApiResult

    /** No HTTP answer at all. */
    class Unreachable(val cause: IOException) : ApiResult
}

/** The difference between the server's clock and this phone's, in seconds, remembered across calls. */
class ClockOffset(initialSeconds: Long = 0L) {
    @Volatile
    var seconds: Long = initialSeconds
}

/**
 * Signs and sends requests for one paired device (contract section 4) and applies the error table of section 10:
 * `CLOCK_SKEW` stores the offset and retries at once, `NONCE_REUSED` retries with a new nonce, `BAD_SIGNATURE`
 * retries once and then asks for re-pairing, `DEVICE_REVOKED` stops everything. Retries are bounded.
 */
class SignedApi(
    private val transport: HttpTransport,
    private val credentials: DeviceCredentials,
    private val clock: ClockOffset = ClockOffset(),
    private val onClockOffsetChanged: (Long) -> Unit = {},
    nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val localNow = nowSeconds
    private val signer = RequestSigner(
        deviceId = credentials.deviceId,
        secret = credentials.deviceSecret,
        clockOffsetSeconds = { clock.seconds },
        nowSeconds = nowSeconds,
    )

    fun get(path: String): ApiResult = call("GET", path, ByteArray(0))

    fun post(path: String, body: ByteArray): ApiResult = call("POST", path, body)

    private fun call(method: String, path: String, body: ByteArray): ApiResult {
        var badSignatures = 0
        var attempts = 0
        while (true) {
            val response = try {
                send(method, path, body)
            } catch (e: IOException) {
                return ApiResult.Unreachable(e)
            }
            if (response.status in 200..299) return ApiResult.Success(response)

            val error = parseError(response)
            val code = ErrorCode.of(error?.code.orEmpty())
            attempts++
            when {
                code == ErrorCode.DEVICE_REVOKED -> return ApiResult.Revoked
                code == ErrorCode.CLOCK_SKEW && attempts <= MAX_RETRIES -> {
                    val serverSeconds = error?.serverTime?.let(TimestampFormat::parseToEpochMillis)?.div(1000)
                        ?: return failure(response, code, error)
                    clock.seconds = serverSeconds - localNow()
                    onClockOffsetChanged(clock.seconds)
                }
                code == ErrorCode.NONCE_REUSED && attempts <= MAX_RETRIES -> Unit // a fresh nonce is drawn on the next loop
                code == ErrorCode.BAD_SIGNATURE -> {
                    if (++badSignatures > 1) return ApiResult.RepairNeeded
                }
                else -> return failure(response, code, error)
            }
        }
    }

    private fun send(method: String, path: String, body: ByteArray): HttpResponse {
        val headers = signer.sign(method, path, body).asMap().toMutableMap()
        headers["Accept"] = "application/json"
        if (method != "GET") headers["Content-Type"] = "application/json"
        return transport.execute(
            HttpRequest(method, credentials.baseUrl + path, headers, if (method == "GET") null else body),
        )
    }

    private fun failure(response: HttpResponse, code: ErrorCode, error: ErrorBody?) = ApiResult.Failure(
        status = response.status,
        code = code,
        error = error,
        retryAfterSec = response.header("Retry-After")?.trim()?.toIntOrNull()?.takeIf { it > 0 },
    )

    private fun parseError(response: HttpResponse): ErrorBody? =
        try {
            Wire.json.decodeFromString(ErrorEnvelope.serializer(), response.body.toString(Charsets.UTF_8)).error
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val MAX_RETRIES = 2
    }
}
