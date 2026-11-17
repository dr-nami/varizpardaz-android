package com.varizpardaz.listener.core.pairing

import com.varizpardaz.listener.core.Base64Url
import com.varizpardaz.listener.core.PairingLink
import com.varizpardaz.listener.core.http.HttpRequest
import com.varizpardaz.listener.core.http.HttpResponse
import com.varizpardaz.listener.core.http.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class PairingServiceTest {
    private val link = PairingLink("https://api.example.com", "AB12CD34")
    private val device = DeviceInfo("Galaxy A14", "14", 1)

    private val secret = ByteArray(32) { it.toByte() }
    private val key = ByteArray(32) { (100 + it).toByte() }

    private fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        HttpResponse(status, headers, body.toByteArray(Charsets.UTF_8))

    private fun okBody(
        id: String = "dev_1",
        secret: ByteArray = this.secret,
        key: ByteArray = this.key,
        interval: Int = 30,
    ) = """{"device_id":"$id","device_secret":"${Base64Url.encode(secret)}","server_public_key":"${Base64Url.encode(key)}","heartbeat_interval_sec":$interval}"""

    private fun pair(transport: HttpTransport) = PairingService(transport, device).pair(link)

    private fun failure(outcome: PairingOutcome) = assertIs<PairingOutcome.Failure>(outcome).failure

    @Test
    fun `the request has the contract shape and goes to the base url`() {
        var seen: HttpRequest? = null
        val outcome = pair { request ->
            seen = request
            json(201, okBody())
        }
        assertIs<PairingOutcome.Success>(outcome)
        val request = seen!!
        assertEquals("POST", request.method)
        assertEquals("https://api.example.com/v1/devices/pair", request.url)
        assertEquals("application/json", request.headers["Content-Type"])
        val body = Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals(
            listOf("code", "platform", "device_name", "os_version", "app_version_code"),
            body.keys.toList(),
        )
        assertEquals("AB12CD34", body["code"]!!.jsonPrimitive.content)
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertEquals("1", body["app_version_code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a good response yields credentials with decoded secret`() {
        val credentials = assertIs<PairingOutcome.Success>(pair { json(201, okBody(interval = 45)) }).credentials
        assertEquals("dev_1", credentials.deviceId)
        assertContentEquals(secret, credentials.deviceSecret)
        assertEquals(Base64Url.encode(key), credentials.serverPublicKey)
        assertEquals("https://api.example.com", credentials.baseUrl)
        assertEquals(45, credentials.heartbeatIntervalSec)
        assertFalse(credentials.toString().contains(Base64Url.encode(secret)))
    }

    @Test
    fun `an out of range heartbeat interval falls back to 30`() {
        for (interval in listOf(0, 1, 100000, -5)) {
            val c = assertIs<PairingOutcome.Success>(pair { json(201, okBody(interval = interval)) }).credentials
            assertEquals(30, c.heartbeatIntervalSec, "interval $interval")
        }
    }

    @Test
    fun `a wrongly sized or missing secret or key is a bad response`() {
        val bad = listOf(
            okBody(secret = ByteArray(16)),
            okBody(secret = ByteArray(33)),
            okBody(key = ByteArray(31)),
            okBody(id = " "),
            """{"device_id":"d","device_secret":"!!!","server_public_key":"${Base64Url.encode(key)}","heartbeat_interval_sec":30}""",
            """{"device_id":"d"}""",
            "not json",
            "",
        )
        for (body in bad) assertEquals(PairingFailure.BadResponse, failure(pair { json(201, body) }), body)
    }

    @Test
    fun `an invalid or expired code`() {
        val body = """{"ok":false,"error":{"code":"PAIRING_CODE_INVALID","message":"کد نامعتبر","server_time":"2026-11-01T10:00:00+03:30"}}"""
        assertEquals(PairingFailure.CodeInvalid, failure(pair { json(400, body) }))
    }

    @Test
    fun `rate limiting reads retry-after`() {
        val limited = failure(pair { json(429, """{"ok":false,"error":{"code":"RATE_LIMITED","message":"x"}}""", mapOf("retry-after" to "42")) })
        assertEquals(42, assertIs<PairingFailure.RateLimited>(limited).retryAfterSec)
        val noHeader = failure(pair { json(429, "") })
        assertNull(assertIs<PairingFailure.RateLimited>(noHeader).retryAfterSec)
        val junk = failure(pair { json(429, "", mapOf("Retry-After" to "Wed, 21 Oct 2026 07:28:00 GMT")) })
        assertNull(assertIs<PairingFailure.RateLimited>(junk).retryAfterSec)
    }

    @Test
    fun `server errors, other rejections and no response`() {
        assertEquals(503, assertIs<PairingFailure.Server>(failure(pair { json(503, "<html>down</html>") })).status)
        val rejected = failure(pair { json(422, """{"ok":false,"error":{"code":"VALIDATION_ERROR","message":"اطلاعات نامعتبر است"}}""") })
        val r = assertIs<PairingFailure.Rejected>(rejected)
        assertEquals(422, r.status)
        assertEquals("VALIDATION_ERROR", r.code)
        assertEquals("اطلاعات نامعتبر است", r.message)
        assertNull(assertIs<PairingFailure.Rejected>(failure(pair { json(404, "") })).code)

        for (e in listOf<IOException>(ConnectException("refused"), SocketTimeoutException("slow"), java.net.UnknownHostException("x"))) {
            assertEquals(PairingFailure.Network, failure(pair { throw e }), e.javaClass.simpleName)
        }
        assertEquals(PairingFailure.Tls, failure(pair { throw SSLHandshakeException("bad cert") }))
    }

    @Test
    fun `an error body that is a success status with an error envelope is not trusted`() {
        val body = """{"ok":false,"error":{"code":"PAIRING_CODE_INVALID","message":"x"}}"""
        assertEquals(PairingFailure.BadResponse, failure(pair { json(200, body) }))
    }
}
