package com.varizpardaz.listener.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConfigTest {
    private val body = Fixtures.exampleConfigBytes()

    @Test
    fun `the example config parses and compiles`() {
        val config = loadExampleConfig()
        assertEquals(1L, config.version)
        assertEquals(3, config.raw.otpPatterns.size)
        assertEquals(50, config.raw.upload.maxBatch)
        assertEquals(30, config.raw.heartbeatIntervalSec)
        assertEquals(listOf("testbank"), config.raw.banks.map { it.bank })
        assertEquals("card_test_1", config.raw.cards.single().cardId)
        assertEquals(1, config.raw.appUpdate?.minVersionCode)
        assertTrue(config.skippedTemplates.isEmpty())
    }

    @Test
    fun `merchant_name is optional, absent on the example config and read when a newer server sends it (CCR-D2)`() {
        assertEquals(null, loadExampleConfig().raw.merchantName)

        val withName = String(body, Charsets.UTF_8).replaceFirst("{", "{ \"merchant_name\": \"فروشگاه نمونه\",").toByteArray(Charsets.UTF_8)
        val key = TestEd25519Key()
        val accepted = assertIs<ConfigResult.Accepted>(ConfigLoader.verifyAndLoad(withName, key.sign(withName), key.publicKeyB64Url))
        assertEquals("فروشگاه نمونه", accepted.config.raw.merchantName)
        assertEquals("فروشگاه نمونه", DisplayName.clean(accepted.config.raw.merchantName))
    }

    @Test
    fun `a validly signed config is accepted and keeps the exact bytes`() {
        val key = TestEd25519Key()
        val result = ConfigLoader.verifyAndLoad(body, key.sign(body), key.publicKeyB64Url)
        val accepted = assertIs<ConfigResult.Accepted>(result)
        assertContentEquals(body, accepted.rawBody)
        assertEquals(1L, accepted.config.version)
    }

    @Test
    fun `a config changed by one byte is rejected`() {
        val key = TestEd25519Key()
        val signature = key.sign(body)
        val tampered = body.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val result = ConfigLoader.verifyAndLoad(tampered, signature, key.publicKeyB64Url)
        assertEquals(ConfigResult.Reason.BAD_SIGNATURE, assertIs<ConfigResult.Rejected>(result).reason)
    }

    @Test
    fun `a signature from another key is rejected`() {
        val pinned = TestEd25519Key()
        val attacker = TestEd25519Key()
        val result = ConfigLoader.verifyAndLoad(body, attacker.sign(body), pinned.publicKeyB64Url)
        assertEquals(ConfigResult.Reason.BAD_SIGNATURE, assertIs<ConfigResult.Rejected>(result).reason)
    }

    @Test
    fun `garbage signatures and keys never throw`() {
        val key = TestEd25519Key()
        val good = key.sign(body)
        for (signature in listOf("", "!!!", "AAAA", good.dropLast(2), good + "AA")) {
            assertFalse(Ed25519Verifier.verify(body, signature, key.publicKeyB64Url), "signature '$signature'")
        }
        for (publicKey in listOf("", "!!!", "AAAA", key.publicKeyB64Url.dropLast(2))) {
            assertFalse(Ed25519Verifier.verify(body, good, publicKey), "key '$publicKey'")
        }
    }

    @Test
    fun `padded base64url is tolerated`() {
        val key = TestEd25519Key()
        val padded = key.sign(body).let { it + "=".repeat((4 - it.length % 4) % 4) }
        assertTrue(Ed25519Verifier.verify(body, padded, key.publicKeyB64Url))
    }

    @Test
    fun `malformed json that is correctly signed is rejected as malformed`() {
        val key = TestEd25519Key()
        val junk = "not json".toByteArray()
        val result = ConfigLoader.verifyAndLoad(junk, key.sign(junk), key.publicKeyB64Url)
        assertEquals(ConfigResult.Reason.MALFORMED, assertIs<ConfigResult.Rejected>(result).reason)

        val missingVersion = """{"issued_at":"x"}""".toByteArray()
        val r2 = ConfigLoader.verifyAndLoad(missingVersion, key.sign(missingVersion), key.publicKeyB64Url)
        assertEquals(ConfigResult.Reason.MALFORMED, assertIs<ConfigResult.Rejected>(r2).reason)
    }

    @Test
    fun `unknown keys from a newer server are ignored`() {
        val json = """{"version":2,"issued_at":"x","brand_new_field":{"a":1}}""".toByteArray()
        assertIs<ConfigResult.Accepted>(ConfigLoader.load(json))
    }

    @Test
    fun `the cross-language vector from the mock server verifies`() {
        // Produced by tools/mock-server/gen-vectors.mjs with Node's crypto, so Ed25519 is checked
        // against an implementation that shares no code with BouncyCastle.
        val vectors = assertNotNull(javaClass.getResource("/vectors/cross-language.json"), "run tools/mock-server/gen-vectors.mjs")
        val json = kotlinx.serialization.json.Json.parseToJsonElement(vectors.readText())
        val ed = (json as kotlinx.serialization.json.JsonObject)["ed25519"] as kotlinx.serialization.json.JsonObject
        val message = ed.str("message")!!.toByteArray(Charsets.UTF_8)
        assertTrue(Ed25519Verifier.verify(message, ed.str("signature")!!, ed.str("public_key")!!))
        assertFalse(Ed25519Verifier.verify(message + 0, ed.str("signature")!!, ed.str("public_key")!!))
    }
}
