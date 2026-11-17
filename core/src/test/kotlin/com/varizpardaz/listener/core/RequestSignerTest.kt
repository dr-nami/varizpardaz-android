package com.varizpardaz.listener.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RequestSignerTest {
    private val secret = ByteArray(32) { it.toByte() }

    private fun signer(nowSeconds: Long = 1_793_600_000, offset: Long = 0, nonce: ByteArray = ByteArray(16) { 7 }) =
        RequestSigner("dev_test", secret, { offset }, { nowSeconds }, { nonce })

    @Test
    fun `canonical string follows section 4`() {
        val body = """{"items":[]}""".toByteArray()
        val canonical = RequestSigner.canonicalString("post", "/v1/ingest/android", "1793600000", "bm9uY2U", body)
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        assertEquals("v1\nPOST\n/v1/ingest/android\n1793600000\nbm9uY2U\n$expectedHash", canonical)
    }

    @Test
    fun `a get request hashes the empty string`() {
        val canonical = RequestSigner.canonicalString("GET", "/v1/devices/config", "1", "n", ByteArray(0))
        assertTrue(canonical.endsWith("\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
    }

    /** What the server does with the headers, written independently of the signer under test. */
    private fun serverVerifies(method: String, path: String, body: ByteArray, headers: SignedHeaders, key: ByteArray): Boolean {
        val canonical = listOf(
            "v1", method, path, headers.timestamp, headers.nonce,
            MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) },
        ).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.toByteArray()))
        return expected == headers.signature
    }

    @Test
    fun `signatures verify under the server rules`() {
        val body = """{"items":[{"local_id":"a"}]}""".toByteArray()
        val headers = signer().sign("POST", "/v1/ingest/android", body)
        assertTrue(serverVerifies("POST", "/v1/ingest/android", body, headers, secret))
        assertEquals("dev_test", headers.deviceId)
        assertEquals("1793600000", headers.timestamp)
        assertEquals(22, headers.nonce.length, "16 bytes in unpadded base64url")
    }

    @Test
    fun `changing method, path, query, body or key breaks the signature`() {
        val body = "x".toByteArray()
        val h = signer().sign("POST", "/v1/a?b=1", body)
        assertTrue(serverVerifies("POST", "/v1/a?b=1", body, h, secret))
        assertTrue(!serverVerifies("GET", "/v1/a?b=1", body, h, secret))
        assertTrue(!serverVerifies("POST", "/v1/a?b=2", body, h, secret))
        assertTrue(!serverVerifies("POST", "/v1/a?b=1", "y".toByteArray(), h, secret))
        assertTrue(!serverVerifies("POST", "/v1/a?b=1", body, h, ByteArray(32) { 1 }))
    }

    @Test
    fun `clock offset shifts the timestamp`() {
        assertEquals("1793600120", signer(offset = 120).sign("GET", "/v1/devices/config").timestamp)
        assertEquals("1793599900", signer(offset = -100).sign("GET", "/v1/devices/config").timestamp)
    }

    @Test
    fun `default nonces are unique and the right size`() {
        val s = RequestSigner("dev_test", secret)
        val nonces = (1..200).map { s.sign("GET", "/x").nonce }.toSet()
        assertEquals(200, nonces.size)
        assertEquals(16, Base64Url.decodeOrNull(nonces.first())?.size)
    }

    @Test
    fun `the secret array is copied`() {
        val key = secret.copyOf()
        val s = RequestSigner("dev_test", key, { 0 }, { 1 }, { ByteArray(16) })
        val before = s.sign("GET", "/x").signature
        key.fill(0)
        assertEquals(before, s.sign("GET", "/x").signature)
        assertNotEquals(0, before.length)
    }

    @Test
    fun `header names match the contract`() {
        val map = signer().sign("GET", "/x").asMap()
        assertEquals(listOf("X-PG-Device", "X-PG-Timestamp", "X-PG-Nonce", "X-PG-Signature"), map.keys.toList())
    }

    @Test
    fun `the cross-language vectors from the mock server reproduce`() {
        // gen-vectors.mjs signs with Node's crypto using the same rules as the mock server's verifier.
        val resource = assertNotNull(javaClass.getResource("/vectors/cross-language.json"), "run tools/mock-server/gen-vectors.mjs")
        val root = Json.parseToJsonElement(resource.readText()) as JsonObject
        val cases = root["hmac"] as JsonArray
        assertTrue(cases.isNotEmpty())
        for (element in cases) {
            val case = element as JsonObject
            val key = Base64Url.decodeOrNull(case.str("device_secret")!!)!!
            val body = case.str("body")!!.toByteArray(Charsets.UTF_8)
            val signer = RequestSigner(
                deviceId = case.str("device_id")!!,
                secret = key,
                nowSeconds = { case.str("timestamp")!!.toLong() },
                newNonce = { Base64Url.decodeOrNull(case.str("nonce")!!)!! },
            )
            val headers = signer.sign(case.str("method")!!, case.str("path")!!, body)
            assertEquals(case.str("canonical"), RequestSigner.canonicalString(case.str("method")!!, case.str("path")!!, headers.timestamp, headers.nonce, body))
            assertEquals(case.str("signature"), headers.signature, case.str("name"))
        }
    }
}
