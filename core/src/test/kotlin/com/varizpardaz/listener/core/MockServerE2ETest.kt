package com.varizpardaz.listener.core

import com.varizpardaz.listener.core.api.SignedApi
import com.varizpardaz.listener.core.http.HttpRequest
import com.varizpardaz.listener.core.intake.MemoryCounters
import com.varizpardaz.listener.core.intake.MemoryQueue
import com.varizpardaz.listener.core.intake.SimRef
import com.varizpardaz.listener.core.intake.SmsIntake
import com.varizpardaz.listener.core.intake.SmsPart
import com.varizpardaz.listener.core.pairing.AesGcmSecretBox
import com.varizpardaz.listener.core.upload.DrainOutcome
import com.varizpardaz.listener.core.upload.Uploader
import com.varizpardaz.listener.core.http.HttpResponse
import com.varizpardaz.listener.core.http.HttpTransport
import com.varizpardaz.listener.core.pairing.DeviceCredentials
import com.varizpardaz.listener.core.pairing.DeviceInfo
import com.varizpardaz.listener.core.pairing.PairingFailure
import com.varizpardaz.listener.core.pairing.PairingOutcome
import com.varizpardaz.listener.core.pairing.PairingService
import com.varizpardaz.listener.core.wire.DepositItem
import com.varizpardaz.listener.core.wire.ErrorEnvelope
import com.varizpardaz.listener.core.wire.IngestResponse
import com.varizpardaz.listener.core.wire.Wire
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Talks to the real Node mock server (`tools/mock-server`) over HTTP: pairing, signed requests, config
 * verification and ingest, using only code from `:core`. This is what ties the Kotlin signer, the Ed25519
 * verifier and the wire DTOs to an independent implementation of the contract.
 *
 * Needs `node` (22+) on the PATH; skipped, with a printed notice, if it is missing. CI installs Node.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MockServerE2ETest {
    private var process: Process? = null
    private lateinit var base: String
    private lateinit var pairingLink: String

    private val client: HttpClient = HttpClient.newBuilder()
        .proxy(object : ProxySelector() {
            override fun select(uri: URI?) = listOf(java.net.Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) = Unit
        })
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val transport = HttpTransport { request ->
        val builder = java.net.http.HttpRequest.newBuilder(URI.create(request.url)).timeout(Duration.ofSeconds(10))
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        val publisher = request.body?.let { java.net.http.HttpRequest.BodyPublishers.ofByteArray(it) }
            ?: java.net.http.HttpRequest.BodyPublishers.noBody()
        builder.method(request.method, publisher)
        val response = client.send(builder.build(), BodyHandlers.ofByteArray())
        HttpResponse(
            response.statusCode(),
            response.headers().map().mapValues { it.value.first() },
            response.body(),
        )
    }

    private fun nodeAvailable(): Boolean = try {
        ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
    } catch (_: java.io.IOException) {
        false
    }

    @BeforeAll
    fun startMock() {
        if (!nodeAvailable()) {
            println("SKIPPED MockServerE2ETest: node is not on the PATH")
            return
        }
        val script = File("../tools/mock-server/server.mjs").absoluteFile.normalize()
        check(script.exists()) { "mock server script not found at $script" }
        val port = ServerSocket(0).use { it.localPort }
        base = "http://127.0.0.1:$port"
        val pb = ProcessBuilder("node", script.path)
            .directory(script.parentFile)
            .redirectErrorStream(true)
        pb.environment().apply {
            put("MOCK_PORT", port.toString())
            put("MOCK_HOST", "127.0.0.1")
            put("MOCK_PUBLIC_URL", base)
        }
        val p = pb.start()
        process = p
        val reader = p.inputStream.bufferedReader()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            val line = reader.readLine() ?: break
            if (line.startsWith("pairing link: ")) {
                pairingLink = line.removePrefix("pairing link: ").trim()
                // Keep draining so the child never blocks on a full pipe.
                Thread { reader.forEachLine { } }.apply { isDaemon = true }.start()
                return
            }
        }
        error("mock server did not print a pairing link")
    }

    @AfterAll
    fun stopMock() {
        process?.destroy()
        process?.waitFor(5, TimeUnit.SECONDS)
    }

    private fun requireMock() = assumeTrue(process != null, "node not available")

    private fun newCode(): PairingLink {
        val response = transport.execute(HttpRequest("POST", "$base/mock/pairing-codes", body = "{}".toByteArray()))
        val link = Regex("\"link\":\"([^\"]+)\"").find(response.body.toString(Charsets.UTF_8))!!.groupValues[1]
        return parse(link)
    }

    private fun parse(link: String): PairingLink =
        assertIs<PairingLink.Result.Ok>(PairingLink.parse(link, "paygate", "pair", allowInsecureHttp = true)).link

    private fun pairNew(): DeviceCredentials {
        val outcome = PairingService(transport, DeviceInfo("Emulator", "16", 1)).pair(newCode())
        return assertIs<PairingOutcome.Success>(outcome).credentials
    }

    private fun signedGet(creds: DeviceCredentials, path: String, signer: RequestSigner = RequestSigner(creds.deviceId, creds.deviceSecret)) =
        transport.execute(HttpRequest("GET", creds.baseUrl + path, signer.sign("GET", path).asMap()))

    private fun signedPost(creds: DeviceCredentials, path: String, body: ByteArray, signer: RequestSigner = RequestSigner(creds.deviceId, creds.deviceSecret)) =
        transport.execute(
            HttpRequest("POST", creds.baseUrl + path, signer.sign("POST", path, body).asMap() + ("Content-Type" to "application/json"), body),
        )

    @Test
    fun `the link the server prints is accepted by the link parser`() {
        requireMock()
        val link = parse(pairingLink)
        assertEquals(base, link.baseUrl)
        assertEquals(8, link.code.length)
    }

    @Test
    fun `pairing works once per code and the second try is refused`() {
        requireMock()
        val link = newCode()
        val service = PairingService(transport, DeviceInfo("Emulator", "16", 1))
        assertIs<PairingOutcome.Success>(service.pair(link))
        val second = assertIs<PairingOutcome.Failure>(service.pair(link))
        assertEquals(PairingFailure.CodeInvalid, second.failure)
    }

    @Test
    fun `a garbage code is refused`() {
        requireMock()
        val link = PairingLink(base, "ZZZZZZZZ")
        val outcome = assertIs<PairingOutcome.Failure>(PairingService(transport, DeviceInfo("x", "1", 1)).pair(link))
        assertEquals(PairingFailure.CodeInvalid, outcome.failure)
    }

    @Test
    fun `nothing listening is a network failure`() {
        requireMock()
        val closed = ServerSocket(0).use { it.localPort }
        val outcome = assertIs<PairingOutcome.Failure>(
            PairingService(transport, DeviceInfo("x", "1", 1)).pair(PairingLink("http://127.0.0.1:$closed", "AB12CD34")),
        )
        assertEquals(PairingFailure.Network, outcome.failure)
    }

    @Test
    fun `a signed config request is accepted and the config verifies against the pinned key`() {
        requireMock()
        val creds = pairNew()
        val response = signedGet(creds, "/v1/devices/config")
        assertEquals(200, response.status)
        val signature = response.header("X-PG-Config-Signature")!!
        val result = ConfigLoader.verifyAndLoad(response.body, signature, creds.serverPublicKey)
        val accepted = assertIs<ConfigResult.Accepted>(result)
        assertTrue(accepted.config.raw.banks.any { it.bank == "testbank" })

        // The same bytes under another key must not verify.
        val other = TestEd25519Key()
        assertIs<ConfigResult.Rejected>(ConfigLoader.verifyAndLoad(response.body, signature, other.publicKeyB64Url))
    }

    @Test
    fun `a replayed nonce and a skewed clock get the contract errors, and the offset fixes it`() {
        requireMock()
        val creds = pairNew()
        val fixedNonce = ByteArray(16) { 9 }
        val signer = RequestSigner(creds.deviceId, creds.deviceSecret, newNonce = { fixedNonce })
        assertEquals(200, signedGet(creds, "/v1/devices/config", signer).status)
        val replay = signedGet(creds, "/v1/devices/config", signer)
        assertEquals(401, replay.status)
        assertEquals("NONCE_REUSED", Wire.json.decodeFromString(ErrorEnvelope.serializer(), replay.body.toString(Charsets.UTF_8)).error.code)

        val skewed = RequestSigner(creds.deviceId, creds.deviceSecret, nowSeconds = { System.currentTimeMillis() / 1000 - 3600 })
        val refused = signedGet(creds, "/v1/devices/config", skewed)
        assertEquals(401, refused.status)
        val error = Wire.json.decodeFromString(ErrorEnvelope.serializer(), refused.body.toString(Charsets.UTF_8)).error
        assertEquals("CLOCK_SKEW", error.code)
        val serverMillis = TimestampFormat.parseToEpochMillis(error.serverTime!!)!!
        var offset = 0L
        val corrected = RequestSigner(
            creds.deviceId, creds.deviceSecret,
            clockOffsetSeconds = { offset },
            nowSeconds = { System.currentTimeMillis() / 1000 - 3600 },
        )
        offset = serverMillis / 1000 - (System.currentTimeMillis() / 1000 - 3600)
        assertEquals(200, signedGet(creds, "/v1/devices/config", corrected).status)
    }

    @Test
    fun `a deposit built by the classifier is accepted, then reported as duplicate`() {
        requireMock()
        val creds = pairNew()
        val config = loadExampleConfig()
        val fixture = Fixtures.smsFixtures().first { it.name.endsWith("deposit-basic.json") }
        val classification = Classifier.classify(IncomingMessage(Channel.SMS, fixture.sender, null, fixture.body), config)
        val deposit = assertIs<Classification.Deposit>(classification)

        val millis = System.currentTimeMillis()
        fun item(localId: String) = DepositItem(
            localId = localId, channel = "sms", sender = fixture.sender, packageName = null, simSlot = 0, subscriptionId = 3,
            scTimestamp = TimestampFormat.format(millis), receivedAt = TimestampFormat.format(millis),
            body = fixture.body, templateId = deposit.templateId, configVersion = config.version, rescanRequestId = null,
        )
        val first = signedPost(creds, "/v1/ingest/android", Wire.encodeIngest(listOf(item("11111111-1111-4111-8111-111111111111"))))
        assertEquals(200, first.status)
        val firstResults = Wire.json.decodeFromString(IngestResponse.serializer(), first.body.toString(Charsets.UTF_8)).results
        assertEquals(listOf("accepted"), firstResults.map { it.status })

        val again = signedPost(creds, "/v1/ingest/android", Wire.encodeIngest(listOf(item("22222222-2222-4222-8222-222222222222"))))
        val againResults = Wire.json.decodeFromString(IngestResponse.serializer(), again.body.toString(Charsets.UTF_8)).results
        assertEquals(listOf("duplicate"), againResults.map { it.status })

        val state = transport.execute(HttpRequest("GET", "$base/mock/state")).body.toString(Charsets.UTF_8)
        assertTrue("\"otp_violations\":[]" in state, "the mock saw an OTP text")
    }

    @Test
    fun `the uploader drains a queue built by the intake through the mock server, surviving a 503`() {
        requireMock()
        val creds = pairNew()
        val config = loadExampleConfig()
        val box = AesGcmSecretBox.withRandomKey()
        val queue = MemoryQueue()
        val counters = MemoryCounters()
        val intake = SmsIntake(queue, counters, box)
        val t0 = System.currentTimeMillis()
        listOf("deposit-basic", "withdrawal-basic", "otp-dynamic-password", "other-promo", "keyword-fallback").forEachIndexed { i, name ->
            val f = Fixtures.smsFixtures().first { it.name.endsWith("$name.json") }
            intake.handle(listOf(SmsPart(f.sender, f.body, t0 + i * 1000L)), SimRef(0, 1), config)
        }
        assertEquals(3, queue.size(), "deposit, withdrawal marker and keyword-fallback deposit; the OTP and the promo never queue")

        transport.execute(HttpRequest("POST", "$base/mock/fail-next", body = """{"path":"/v1/ingest/android","status":503,"times":1}""".toByteArray()))
        val uploader = Uploader(queue, counters, SignedApi(transport, creds), box, config.raw.upload.maxBatch)
        assertIs<DrainOutcome.RetryLater>(uploader.drain())
        assertEquals(3, queue.size(), "nothing is lost on a 503")

        val done = assertIs<DrainOutcome.Done>(uploader.drain())
        assertEquals(3, done.tally.uploaded)
        assertEquals(0, queue.size())

        val state = transport.execute(HttpRequest("GET", "$base/mock/state")).body.toString(Charsets.UTF_8)
        assertTrue("\"otp_violations\":[]" in state, "the mock saw an OTP or withdrawal text")
        assertEquals(3, Regex("\"device_id\":\"${creds.deviceId}\"").findAll(state).count(), "exactly the three items reached the server")
    }
}
