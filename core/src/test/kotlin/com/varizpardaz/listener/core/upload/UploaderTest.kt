package com.varizpardaz.listener.core.upload

import com.varizpardaz.listener.core.Channel
import com.varizpardaz.listener.core.TimestampFormat
import com.varizpardaz.listener.core.api.SignedApi
import com.varizpardaz.listener.core.http.HttpRequest
import com.varizpardaz.listener.core.http.HttpResponse
import com.varizpardaz.listener.core.http.HttpTransport
import com.varizpardaz.listener.core.intake.MemoryCounters
import com.varizpardaz.listener.core.intake.MemoryQueue
import com.varizpardaz.listener.core.pairing.AesGcmSecretBox
import com.varizpardaz.listener.core.pairing.DeviceCredentials
import com.varizpardaz.listener.core.queue.CounterName
import com.varizpardaz.listener.core.queue.ItemKind
import com.varizpardaz.listener.core.queue.QueuedItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploaderTest {
    private val queue = MemoryQueue()
    private val counters = MemoryCounters()
    private val box = AesGcmSecretBox.withRandomKey()
    private val creds = DeviceCredentials("https://api.example.com", "dev_1", ByteArray(32) { 1 }, "key", 30)

    private val requests = ArrayList<HttpRequest>()
    private val replies = ArrayDeque<(HttpRequest) -> HttpResponse>()
    private val transport = HttpTransport { request ->
        requests += request
        (replies.removeFirstOrNull() ?: error("unexpected request #${requests.size}"))(request)
    }
    private val api = SignedApi(transport, creds, nowSeconds = { 1_793_600_000 })

    private fun uploader(maxBatch: Int = 50, maxBytes: Int = Uploader.MAX_REQUEST_BYTES, maxBatches: Int = 200) =
        Uploader(queue, counters, api, box, maxBatch, maxBytes, maxBatches)

    private fun deposit(n: Int, body: String = "واریز شماره $n"): QueuedItem {
        val id = "id-%03d".format(n)
        return QueuedItem(
            localId = id, kind = ItemKind.DEPOSIT, channel = Channel.SMS, sender = "TESTBANK", packageName = null, simSlot = 0,
            subscriptionId = 3, scTimestampMillis = 1_793_599_990_000L + n * 1000, receivedAtMillis = 1_793_599_991_000L + n * 1000,
            sealedBody = box.seal(body.toByteArray(), id.toByteArray()), templateId = "t1", bank = "testbank", amountRial = null,
            balanceRial = null, configVersion = 7,
        )
    }

    private fun marker(n: Int) = QueuedItem(
        localId = "mk-%03d".format(n), kind = ItemKind.BALANCE_MARKER, channel = Channel.SMS, sender = "TESTBANK", packageName = null,
        simSlot = 0, subscriptionId = 3, scTimestampMillis = 1_793_599_990_000L, receivedAtMillis = 1_793_599_991_000L, sealedBody = null,
        templateId = "wd1", bank = "testbank", amountRial = 1_250_000, balanceRial = 96_750_000, configVersion = 7,
    )

    private fun fill(count: Int, body: (Int) -> String = { "واریز شماره $it" }) = repeat(count) { queue.enqueue(deposit(it, body(it))) }

    private fun sentIds(request: HttpRequest): List<String> =
        Json.parseToJsonElement(request.body!!.toString(Charsets.UTF_8)).jsonObject["items"]!!.jsonArray.map { it.jsonObject["local_id"]!!.jsonPrimitive.content }

    /** The server answers every item with [status]. */
    private fun answerAll(status: String, reason: String? = null): (HttpRequest) -> HttpResponse = { request ->
        val results = sentIds(request).joinToString(",") {
            """{"local_id":"$it","status":"$status"${reason?.let { r -> ""","reason":"$r"""" }.orEmpty()}}"""
        }
        HttpResponse(200, emptyMap(), """{"results":[$results]}""".toByteArray())
    }

    private fun error(status: Int, code: String, extra: String = "", headers: Map<String, String> = emptyMap()): (HttpRequest) -> HttpResponse =
        { HttpResponse(status, headers, """{"ok":false,"error":{"code":"$code","message":"پیام"$extra}}""".toByteArray()) }

    @Test
    fun `an empty queue sends nothing`() {
        assertIs<DrainOutcome.Done>(uploader().drain())
        assertEquals(0, requests.size)
    }

    @Test
    fun `items go out oldest first in batches of max_batch and leave the queue only once answered`() {
        fill(120)
        replies += answerAll("accepted"); replies += answerAll("accepted"); replies += answerAll("accepted")
        val outcome = assertIs<DrainOutcome.Done>(uploader(maxBatch = 50).drain())
        assertEquals(listOf(50, 50, 20), requests.map { sentIds(it).size })
        assertEquals((0 until 120).map { "id-%03d".format(it) }, requests.flatMap(::sentIds))
        assertEquals(0, queue.size())
        assertEquals(120, outcome.tally.uploaded)
        assertEquals(120L, counters.snapshot()[CounterName.UPLOADED])
    }

    @Test
    fun `the request carries the raw text and the contract fields`() {
        queue.enqueue(deposit(1, "TESTBANK\nواریز: ۵,۰۰۰,۰۰۰ ریال"))
        queue.enqueue(marker(2))
        replies += answerAll("accepted")
        uploader().drain()
        val items = Json.parseToJsonElement(requests.single().body!!.toString(Charsets.UTF_8)).jsonObject["items"]!!.jsonArray
        val d = items[0].jsonObject
        assertEquals("TESTBANK\nواریز: ۵,۰۰۰,۰۰۰ ریال", d["body"]!!.jsonPrimitive.content)
        assertEquals("deposit", d["kind"]!!.jsonPrimitive.content)
        assertEquals("sms", d["channel"]!!.jsonPrimitive.content)
        assertEquals("t1", d["template_id"]!!.jsonPrimitive.content)
        assertEquals(TimestampFormat.format(1_793_599_991_000L), d["sc_timestamp"]!!.jsonPrimitive.content)
        val m = items[1].jsonObject
        assertEquals("balance_marker", m["kind"]!!.jsonPrimitive.content)
        assertEquals("1250000", m["amount_rial"]!!.jsonPrimitive.content)
        assertEquals("96750000", m["balance_rial"]!!.jsonPrimitive.content)
        assertNull(m["body"])
        assertEquals("POST", requests.single().method)
        assertEquals("https://api.example.com/v1/ingest/android", requests.single().url)
    }

    @Test
    fun `duplicate counts as uploaded and rejected is final but counted apart`() {
        fill(2)
        replies += { request ->
            val ids = sentIds(request)
            HttpResponse(200, emptyMap(), """{"results":[{"local_id":"${ids[0]}","status":"duplicate"},{"local_id":"${ids[1]}","status":"rejected","reason":"otp_detected"}]}""".toByteArray())
        }
        val outcome = assertIs<DrainOutcome.Done>(uploader().drain())
        assertEquals(0, queue.size())
        assertEquals(1, outcome.tally.uploaded)
        assertEquals(1, outcome.tally.rejected)
        assertEquals(1L, counters.snapshot()[CounterName.UPLOADED])
        assertEquals(1L, counters.snapshot()[CounterName.REJECTED])
    }

    @Test
    fun `an item the server did not answer for stays queued`() {
        fill(3)
        replies += { request ->
            val ids = sentIds(request)
            HttpResponse(200, emptyMap(), """{"results":[{"local_id":"${ids[0]}","status":"accepted"},{"local_id":"${ids[2]}","status":"accepted"}]}""".toByteArray())
        }
        replies += answerAll("accepted")
        assertIs<DrainOutcome.Done>(uploader().drain())
        assertEquals(2, requests.size)
        assertEquals(listOf("id-001"), sentIds(requests[1])) // the one that was skipped is simply sent again
        assertEquals(0, queue.size())
    }

    @Test
    fun `a response that settles nothing is a retry, not a loop`() {
        fill(2)
        replies += { HttpResponse(200, emptyMap(), """{"results":[]}""".toByteArray()) }
        assertIs<DrainOutcome.RetryLater>(uploader().drain())
        assertEquals(1, requests.size)
        assertEquals(2, queue.size())
        replies += { HttpResponse(200, emptyMap(), "not json".toByteArray()) }
        assertIs<DrainOutcome.RetryLater>(uploader().drain())
        assertEquals(2, queue.size())
    }

    @Test
    fun `a server error or no connection keeps everything, in order`() {
        fill(3)
        replies += error(503, "INTERNAL")
        assertNull(assertIs<DrainOutcome.RetryLater>(uploader().drain()).afterSec)
        replies += { throw IOException("offline") }
        assertIs<DrainOutcome.RetryLater>(uploader().drain())
        assertEquals(listOf("id-000", "id-001", "id-002"), queue.peek(10).map { it.localId })
        replies += { HttpResponse(502, emptyMap(), "<html>bad gateway</html>".toByteArray()) }
        assertIs<DrainOutcome.RetryLater>(uploader().drain())
        assertEquals(3, queue.size())
    }

    @Test
    fun `rate limiting waits for retry-after`() {
        fill(1)
        replies += error(429, "RATE_LIMITED", headers = mapOf("Retry-After" to "42"))
        assertEquals(42, assertIs<DrainOutcome.RetryLater>(uploader().drain()).afterSec)
        assertEquals(1, queue.size())
    }

    @Test
    fun `payload too large halves the batch until it fits`() {
        fill(8)
        replies += error(413, "PAYLOAD_TOO_LARGE"); replies += error(413, "PAYLOAD_TOO_LARGE")
        replies += answerAll("accepted"); replies += answerAll("accepted")
        replies += answerAll("accepted"); replies += answerAll("accepted")
        assertIs<DrainOutcome.Done>(uploader(maxBatch = 8).drain())
        assertEquals(listOf(8, 4, 2, 2, 2, 2), requests.map { sentIds(it).size })
        assertEquals(0, queue.size())
    }

    @Test
    fun `a single item the server calls too large is dropped so it cannot block the queue`() {
        fill(2)
        replies += error(413, "PAYLOAD_TOO_LARGE"); replies += error(413, "PAYLOAD_TOO_LARGE")
        replies += answerAll("accepted")
        val outcome = assertIs<DrainOutcome.Done>(uploader(maxBatch = 2).drain())
        assertEquals(1, outcome.tally.dropped)
        assertEquals(1, outcome.tally.uploaded)
        assertEquals(0, queue.size())
    }

    @Test
    fun `a request over 256 KB is split before it is sent`() {
        fill(6) { "x".repeat(100 * 1024) + it }
        repeat(6) { replies += answerAll("accepted") }
        assertIs<DrainOutcome.Done>(uploader().drain())
        assertTrue(requests.all { it.body!!.size <= Uploader.MAX_REQUEST_BYTES })
        assertEquals(6, requests.sumOf { sentIds(it).size })
        assertEquals(0, queue.size())
    }

    @Test
    fun `a 422 naming items drops those and resends the rest at once`() {
        fill(4)
        replies += error(422, "VALIDATION_ERROR", ""","fields":{"items.1.local_id":"Invalid input","items.3.body":"Invalid input"}""")
        replies += answerAll("accepted")
        val outcome = assertIs<DrainOutcome.Done>(uploader().drain())
        assertEquals(listOf("id-000", "id-002"), sentIds(requests[1]))
        assertEquals(2, outcome.tally.rejected)
        assertEquals(2, outcome.tally.uploaded)
        assertEquals(0, queue.size())
    }

    @Test
    fun `a 422 with the older list shape is understood too`() {
        fill(2)
        replies += error(422, "VALIDATION_ERROR", ""","fields":["items.0.body"]""")
        replies += answerAll("accepted")
        assertEquals(1, assertIs<DrainOutcome.Done>(uploader().drain()).tally.rejected)
        assertEquals(listOf("id-001"), sentIds(requests[1]))
    }

    @Test
    fun `a batch-level 422 splits the batch, and an unreadable one ends with the single item dropped`() {
        fill(4)
        replies += error(422, "VALIDATION_ERROR", ""","fields":{"items":"Too big: expected array to have <=50 items"}""")
        replies += answerAll("accepted"); replies += answerAll("accepted")
        assertIs<DrainOutcome.Done>(uploader().drain())
        assertEquals(listOf(4, 2, 2), requests.map { sentIds(it).size })

        requests.clear()
        fill(2)
        replies += error(422, "VALIDATION_ERROR"); replies += error(422, "VALIDATION_ERROR"); replies += answerAll("accepted")
        val outcome = assertIs<DrainOutcome.Done>(uploader().drain())
        assertEquals(1, outcome.tally.rejected)
        assertEquals(0, queue.size())
    }

    @Test
    fun `revoked and re-pair needed stop at once and keep the queue for the caller to wipe`() {
        fill(2)
        replies += error(403, "DEVICE_REVOKED")
        assertIs<DrainOutcome.Revoked>(uploader().drain())
        replies += error(401, "BAD_SIGNATURE"); replies += error(401, "BAD_SIGNATURE")
        assertIs<DrainOutcome.RepairNeeded>(uploader().drain())
        assertEquals(2, queue.size())
    }

    @Test
    fun `a clock skew is corrected inside the call and the batch still goes out`() {
        fill(1)
        replies += error(401, "CLOCK_SKEW", ""","server_time":"2026-11-01T10:15:30+03:30"""")
        replies += answerAll("accepted")
        assertIs<DrainOutcome.Done>(uploader().drain())
        assertEquals(2, requests.size)
        assertEquals(0, queue.size())
    }

    @Test
    fun `an item whose body cannot be opened is removed and the others are still sent`() {
        fill(2)
        val broken = QueuedItem(
            localId = "id-broken", kind = ItemKind.DEPOSIT, channel = Channel.SMS, sender = "TESTBANK", packageName = null, simSlot = 0,
            subscriptionId = 3, scTimestampMillis = 1, receivedAtMillis = 1, sealedBody = ByteArray(40), templateId = null, bank = "testbank",
            amountRial = null, balanceRial = null, configVersion = 7,
        )
        queue.enqueue(broken)
        replies += answerAll("accepted")
        val outcome = assertIs<DrainOutcome.Done>(uploader().drain())
        assertEquals(1, outcome.tally.dropped)
        assertEquals(2, outcome.tally.uploaded)
        assertEquals(listOf("id-000", "id-001"), sentIds(requests.single()))
        assertEquals(0, queue.size())
    }

    @Test
    fun `a long queue is sent in bounded runs`() {
        fill(10)
        repeat(3) { replies += answerAll("accepted") }
        val outcome = assertIs<DrainOutcome.MoreWaiting>(uploader(maxBatch = 2, maxBatches = 3).drain())
        assertEquals(6, outcome.tally.uploaded)
        assertEquals(4, queue.size())
    }

    @Test
    fun `the body of a message is not in any printed form of the result`() {
        fill(1, { "رمز 998877" })
        replies += answerAll("accepted")
        val outcome = uploader().drain()
        assertTrue("998877" !in outcome.toString())
        assertNotNull(requests.single().toString())
        assertTrue("998877" !in requests.single().toString())
        assertIs<JsonArray>(Json.parseToJsonElement(requests.single().body!!.toString(Charsets.UTF_8)).jsonObject["items"])
    }

    @Test
    fun `a notification item goes out with its package and no sender or SIM`() {
        val id = "id-900"
        queue.enqueue(
            QueuedItem(
                localId = id, kind = ItemKind.DEPOSIT, channel = Channel.NOTIFICATION, sender = null, packageName = "ir.example.testbank",
                simSlot = null, subscriptionId = null, scTimestampMillis = 1_793_599_990_000L, receivedAtMillis = 1_793_599_991_000L,
                sealedBody = box.seal("واریز".toByteArray(), id.toByteArray()), templateId = null, bank = "testbank", amountRial = null,
                balanceRial = null, configVersion = 7,
            ),
        )
        replies += answerAll("accepted")
        uploader().drain()
        val sent = Json.parseToJsonElement(requests.single().body!!.toString(Charsets.UTF_8)).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals("notification", sent["channel"]!!.jsonPrimitive.content)
        assertEquals("ir.example.testbank", sent["package"]!!.jsonPrimitive.content)
        assertEquals("null", sent["sender"].toString())
        assertEquals("null", sent["sim_slot"].toString())
        assertEquals("null", sent["subscription_id"].toString())
    }

    @Test
    fun `a notification balance marker names its package, not a sender (CCR-D1)`() {
        queue.enqueue(
            QueuedItem(
                localId = "mk-n1", kind = ItemKind.BALANCE_MARKER, channel = Channel.NOTIFICATION, sender = null, packageName = "ir.example.testbank",
                simSlot = null, subscriptionId = null, scTimestampMillis = 1_793_599_990_000L, receivedAtMillis = 1_793_599_991_000L,
                sealedBody = null, templateId = "wd1", bank = "testbank", amountRial = 1_250_000, balanceRial = 96_750_000, configVersion = 7,
            ),
        )
        replies += answerAll("accepted")
        uploader().drain()
        val sent = Json.parseToJsonElement(requests.single().body!!.toString(Charsets.UTF_8)).jsonObject["items"]!!.jsonArray.single().jsonObject
        assertEquals("notification", sent["channel"]!!.jsonPrimitive.content)
        assertEquals("null", sent["sender"].toString())
        assertEquals("ir.example.testbank", sent["package"]!!.jsonPrimitive.content)
        assertNull(sent["body"])
    }

    @Test
    fun `a balance marker carries the template's account when it has one, and null when it has none (CCR-1)`() {
        val withAccount = marker(1).let {
            QueuedItem(
                localId = it.localId, kind = it.kind, channel = it.channel, sender = it.sender, packageName = null, simSlot = 0, subscriptionId = 3,
                scTimestampMillis = it.scTimestampMillis, receivedAtMillis = it.receivedAtMillis, sealedBody = null, templateId = "wd1", bank = "testbank",
                amountRial = 1_250_000, balanceRial = 96_750_000, configVersion = 7, account = "1234",
            )
        }
        queue.enqueue(withAccount)
        queue.enqueue(marker(2))
        replies += answerAll("accepted")
        uploader().drain()
        val items = Json.parseToJsonElement(requests.single().body!!.toString(Charsets.UTF_8)).jsonObject["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals("1234", items[0]["account"]!!.jsonPrimitive.content)
        assertEquals("null", items[1]["account"].toString())
        assertEquals("null", items[0]["package"].toString(), "an SMS marker has no package")
    }

    @Test
    fun `backoff doubles from five seconds up to five minutes`() {
        assertEquals(listOf(0, 5, 10, 20, 40, 80, 160, 300, 300), (0..8).map(Backoff::delaySeconds))
        assertEquals(300, Backoff.delaySeconds(1000))
    }
}
