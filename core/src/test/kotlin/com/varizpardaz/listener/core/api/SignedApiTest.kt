package com.varizpardaz.listener.core.api

import com.varizpardaz.listener.core.RequestSigner
import com.varizpardaz.listener.core.SignedHeaders
import com.varizpardaz.listener.core.http.HttpRequest
import com.varizpardaz.listener.core.http.HttpResponse
import com.varizpardaz.listener.core.http.HttpTransport
import com.varizpardaz.listener.core.pairing.DeviceCredentials
import com.varizpardaz.listener.core.wire.ErrorCode
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignedApiTest {
    private val secret = ByteArray(32) { (it + 1).toByte() }
    private val creds = DeviceCredentials("https://api.example.com", "dev_1", secret, "key", 30)
    private val now = 1_793_600_000L

    private val seen = ArrayList<HttpRequest>()
    private val replies = ArrayDeque<() -> HttpResponse>()
    private val transport = HttpTransport { request ->
        seen += request
        replies.removeFirst()()
    }
    private var offsetSaved: Long? = null
    private val api = SignedApi(transport, creds, ClockOffset(), { offsetSaved = it }, nowSeconds = { now })

    private fun ok(body: String = "{}") = { HttpResponse(200, emptyMap(), body.toByteArray()) }

    private fun error(status: Int, code: String, serverTime: String? = null, headers: Map<String, String> = emptyMap(), extra: String = "") = {
        val time = serverTime?.let { ""","server_time":"$it"""" }.orEmpty()
        HttpResponse(status, headers, """{"ok":false,"error":{"code":"$code","message":"پیام"$time$extra}}""".toByteArray())
    }

    private fun header(request: HttpRequest, name: String) = request.headers.getValue(name)

    @Test
    fun `a successful call is signed and goes to base plus path`() {
        replies += ok("""{"x":1}""")
        val result = assertIs<ApiResult.Success>(api.get("/v1/devices/config"))
        assertEquals("""{"x":1}""", result.response.body.toString(Charsets.UTF_8))
        val request = seen.single()
        assertEquals("GET", request.method)
        assertEquals("https://api.example.com/v1/devices/config", request.url)
        assertNull(request.body)
        assertEquals("dev_1", header(request, SignedHeaders.HEADER_DEVICE))
        assertEquals(now.toString(), header(request, SignedHeaders.HEADER_TIMESTAMP))
    }

    @Test
    fun `a post is signed over the exact body bytes`() {
        replies += ok()
        val body = """{"items":[{"body":"واریز"}]}""".toByteArray()
        assertIs<ApiResult.Success>(api.post("/v1/ingest/android", body))
        val request = seen.single()
        assertContentEquals(body, request.body)
        assertEquals("application/json", header(request, "Content-Type"))

        val canonical = RequestSigner.canonicalString(
            "POST", "/v1/ingest/android", header(request, SignedHeaders.HEADER_TIMESTAMP), header(request, SignedHeaders.HEADER_NONCE), body,
        )
        val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(canonical.toByteArray()),
        )
        assertEquals(expected, header(request, SignedHeaders.HEADER_SIGNATURE))
        assertTrue(MessageDigest.isEqual(expected.toByteArray(), header(request, SignedHeaders.HEADER_SIGNATURE).toByteArray()))
    }

    @Test
    fun `clock skew stores the offset and retries at once with a corrected timestamp`() {
        // Server says it is 2026-11-01T10:15:30+03:30 = 06:45:30Z = epoch 1793515530 (offset from `now` is negative).
        replies += error(401, "CLOCK_SKEW", "2026-11-01T10:15:30+03:30")
        replies += ok()
        assertIs<ApiResult.Success>(api.get("/v1/devices/config"))
        assertEquals(2, seen.size)
        val serverSeconds = 1_793_515_530L
        assertEquals(serverSeconds - now, offsetSaved)
        assertEquals(serverSeconds.toString(), header(seen[1], SignedHeaders.HEADER_TIMESTAMP))

        // The offset sticks for later calls.
        replies += ok()
        api.get("/x")
        assertEquals(serverSeconds.toString(), header(seen[2], SignedHeaders.HEADER_TIMESTAMP))
    }

    @Test
    fun `endless clock skew gives up instead of looping`() {
        repeat(10) { replies += error(401, "CLOCK_SKEW", "2026-11-01T10:15:30+03:30") }
        val result = assertIs<ApiResult.Failure>(api.get("/x"))
        assertEquals(ErrorCode.CLOCK_SKEW, result.code)
        assertTrue(seen.size <= 4)
    }

    @Test
    fun `clock skew without a usable server time is returned, not retried blindly`() {
        replies += error(401, "CLOCK_SKEW")
        assertEquals(ErrorCode.CLOCK_SKEW, assertIs<ApiResult.Failure>(api.get("/x")).code)
        assertEquals(1, seen.size)
    }

    @Test
    fun `a reused nonce is retried with a new one`() {
        replies += error(401, "NONCE_REUSED")
        replies += ok()
        assertIs<ApiResult.Success>(api.get("/x"))
        assertNotEquals(header(seen[0], SignedHeaders.HEADER_NONCE), header(seen[1], SignedHeaders.HEADER_NONCE))
    }

    @Test
    fun `a bad signature is retried once and then means re-pair`() {
        replies += error(401, "BAD_SIGNATURE")
        replies += ok()
        assertIs<ApiResult.Success>(api.get("/x"))

        replies += error(401, "BAD_SIGNATURE")
        replies += error(401, "BAD_SIGNATURE")
        assertIs<ApiResult.RepairNeeded>(api.get("/x"))
        assertEquals(2 + 2, seen.size)
    }

    @Test
    fun `a revoked device stops at once`() {
        replies += error(403, "DEVICE_REVOKED")
        assertIs<ApiResult.Revoked>(api.get("/x"))
        assertEquals(1, seen.size)
    }

    @Test
    fun `other errors are handed back with their code and retry-after`() {
        replies += error(429, "RATE_LIMITED", headers = mapOf("retry-after" to "7"))
        val limited = assertIs<ApiResult.Failure>(api.post("/v1/ingest/android", ByteArray(1)))
        assertEquals(ErrorCode.RATE_LIMITED, limited.code)
        assertEquals(7, limited.retryAfterSec)

        replies += error(413, "PAYLOAD_TOO_LARGE")
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, assertIs<ApiResult.Failure>(api.post("/x", ByteArray(1))).code)

        replies += error(422, "VALIDATION_ERROR", extra = ""","fields":["items.1.body"]""")
        val invalid = assertIs<ApiResult.Failure>(api.post("/x", ByteArray(1)))
        assertEquals(ErrorCode.VALIDATION_ERROR, invalid.code)
        assertTrue(invalid.error!!.fields.toString().contains("items.1.body"))

        replies += { HttpResponse(503, emptyMap(), "<html>down</html>".toByteArray()) }
        val down = assertIs<ApiResult.Failure>(api.get("/x"))
        assertEquals(503, down.status)
        assertEquals(ErrorCode.UNKNOWN, down.code)
        assertNull(down.error)
    }

    @Test
    fun `no connection is reported as unreachable`() {
        replies += { throw IOException("no route") }
        assertIs<ApiResult.Unreachable>(api.get("/x"))
    }
}
