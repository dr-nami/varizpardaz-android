package com.varizpardaz.listener.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyPairGenerator
import java.security.Signature

/** Access to `contracts/fixtures`, whose location is passed in by Gradle (see core/build.gradle.kts). */
object Fixtures {
    val dir: Path = Paths.get(
        System.getProperty("paygate.fixturesDir") ?: error("system property paygate.fixturesDir is not set"),
    )

    fun bytes(relative: String): ByteArray = Files.readAllBytes(dir.resolve(relative))

    fun json(relative: String): JsonElement = Json.parseToJsonElement(String(bytes(relative), Charsets.UTF_8))

    /** `sms/<bank>/<case>.json` -> parsed fixture, in a stable order. */
    fun smsFixtures(): List<SmsFixture> =
        Files.walk(dir.resolve("sms")).use { paths ->
            paths.filter { it.toString().endsWith(".json") }.sorted().map { path ->
                val obj = Json.parseToJsonElement(String(Files.readAllBytes(path), Charsets.UTF_8)).jsonObject
                SmsFixture(
                    name = dir.relativize(path).toString().replace('\\', '/'),
                    bank = obj.str("bank")!!,
                    sender = obj.str("sender")!!,
                    body = obj.str("body")!!,
                    expected = obj["expected"]!!.jsonObject,
                    source = obj.str("source")!!,
                )
            }.toList()
        }

    fun exampleConfigBytes(): ByteArray = bytes("config.example.json")
}

class SmsFixture(
    val name: String,
    val bank: String,
    val sender: String,
    val body: String,
    val expected: JsonObject,
    val source: String,
)

fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

/** Throw-away Ed25519 key pair from the JDK, independent of the BouncyCastle code under test. */
class TestEd25519Key {
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    /** Raw 32-byte public key, base64url. The X.509 encoding ends with the raw key. */
    val publicKeyB64Url: String = Base64Url.encode(pair.public.encoded.takeLast(32).toByteArray())

    fun sign(message: ByteArray): String =
        Base64Url.encode(
            Signature.getInstance("Ed25519").apply {
                initSign(pair.private)
                update(message)
            }.sign(),
        )
}

fun loadExampleConfig(): CompiledConfig {
    val result = ConfigLoader.load(Fixtures.exampleConfigBytes())
    check(result is ConfigResult.Accepted) { "example config rejected: ${(result as ConfigResult.Rejected).reason}" }
    return result.config
}
