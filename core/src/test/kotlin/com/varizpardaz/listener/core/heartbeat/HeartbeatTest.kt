package com.varizpardaz.listener.core.heartbeat

import com.varizpardaz.listener.core.Fixtures
import com.varizpardaz.listener.core.TestEd25519Key
import com.varizpardaz.listener.core.TimestampFormat
import com.varizpardaz.listener.core.api.SignedApi
import com.varizpardaz.listener.core.configstore.ConfigPersistence
import com.varizpardaz.listener.core.configstore.ConfigRefresh
import com.varizpardaz.listener.core.configstore.ConfigRepository
import com.varizpardaz.listener.core.configstore.StoredConfig
import com.varizpardaz.listener.core.http.HttpRequest
import com.varizpardaz.listener.core.http.HttpResponse
import com.varizpardaz.listener.core.http.HttpTransport
import com.varizpardaz.listener.core.intake.MemoryCounters
import com.varizpardaz.listener.core.intake.MemoryQueue
import com.varizpardaz.listener.core.intake.SmsIntake
import com.varizpardaz.listener.core.pairing.AesGcmSecretBox
import com.varizpardaz.listener.core.pairing.DeviceCredentials
import com.varizpardaz.listener.core.queue.CounterName
import com.varizpardaz.listener.core.rescan.InboxReader
import com.varizpardaz.listener.core.rescan.InboxSms
import com.varizpardaz.listener.core.rescan.RescanLedger
import com.varizpardaz.listener.core.rescan.RescanProcessor
import com.varizpardaz.listener.core.wire.Permissions
import com.varizpardaz.listener.core.wire.SimInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryLedger : RescanLedger {
    val done = LinkedHashMap<String, Int>()
    override fun doneEntries(): Map<String, Int> = LinkedHashMap(done)
    override fun record(requestId: String, found: Int) {
        done[requestId] = found
    }
    override fun forget(requestIds: Collection<String>) {
        requestIds.forEach(done::remove)
    }
}

class HeartbeatTest {
    private val key = TestEd25519Key()
    private val creds = DeviceCredentials("https://api.example.com", "dev_1", ByteArray(32) { 3 }, key.publicKeyB64Url, 30)
    private val counters = MemoryCounters()
    private val queue = MemoryQueue()
    private val ledger = MemoryLedger()
    private val requests = ArrayList<HttpRequest>()
    private val replies = ArrayDeque<(HttpRequest) -> HttpResponse>()
    private val transport = HttpTransport { r ->
        requests += r
        (replies.removeFirstOrNull() ?: error("unexpected request ${r.method} ${r.url}"))(r)
    }
    private val api = SignedApi(transport, creds, nowSeconds = { 1_793_600_000 })
    private val sender = HeartbeatSender(api, counters, queue, ledger, nowMillis = { 1_793_600_000_000 })

    private val snapshot = DeviceSnapshot(
        batteryPct = 82, charging = false, network = "wifi",
        permissions = Permissions(receiveSms = true, readSms = true, notificationListener = false, postNotifications = true, ignoreBatteryOptimizations = true),
        sims = listOf(SimInfo(slot = 0, subscriptionId = 3, carrier = "MCI")),
    )

    private fun ok(configVersion: Long = 7, rescan: String = "[]") = { _: HttpRequest ->
        HttpResponse(200, emptyMap(), """{"server_time":"2026-11-01T10:15:30+03:30","config_version":$configVersion,"rescan":$rescan}""".toByteArray())
    }

    private fun body(i: Int = 0) = Json.parseToJsonElement(requests[i].body!!.toString(Charsets.UTF_8)).jsonObject

    @Test
    fun `the payload has exactly the fields of the contract`() {
        counters.increment(CounterName.UPLOADED, 2)
        counters.increment(CounterName.DROPPED_OTP)
        counters.increment(CounterName.REJECTED, 5)
        replies += ok()
        val result = sender.send(snapshot, appVersionCode = 1, configVersion = 7, lastMessageAtMillis = 1_793_599_990_000)
        assertIs<HeartbeatResult.Ok>(result)
        val b = body()
        assertEquals(
            listOf("sent_at", "app_version_code", "battery_pct", "charging", "network", "permissions", "queue_depth", "last_message_at", "counters_since_last", "config_version", "sims", "rescans_done"),
            b.keys.toList(),
        )
        assertEquals(listOf("receive_sms", "read_sms", "notification_listener", "post_notifications", "ignore_battery_optimizations"), b["permissions"]!!.jsonObject.keys.toList())
        assertEquals("false", b["permissions"]!!.jsonObject["notification_listener"]!!.jsonPrimitive.content)
        val c = b["counters_since_last"]!!.jsonObject
        assertEquals(listOf("uploaded", "dropped_sender", "dropped_otp", "dropped_unclassified"), c.keys.toList())
        assertEquals("2", c["uploaded"]!!.jsonPrimitive.content)
        assertEquals("1", c["dropped_otp"]!!.jsonPrimitive.content)
        assertEquals(TimestampFormat.format(1_793_600_000_000), b["sent_at"]!!.jsonPrimitive.content)
        assertEquals(TimestampFormat.format(1_793_599_990_000), b["last_message_at"]!!.jsonPrimitive.content)
        assertEquals("3", b["sims"]!!.jsonArray[0].jsonObject["subscription_id"]!!.jsonPrimitive.content)
        assertEquals("POST", requests[0].method)
        assertEquals("https://api.example.com/v1/devices/heartbeat", requests[0].url)
    }

    @Test
    fun `counters are since the last heartbeat and survive a failed one`() {
        counters.increment(CounterName.DROPPED_SENDER, 4)
        replies += { throw IOException("offline") }
        assertIs<HeartbeatResult.RetryLater>(sender.send(snapshot, 1, 7, null))

        replies += { HttpResponse(503, emptyMap(), "down".toByteArray()) }
        assertIs<HeartbeatResult.RetryLater>(sender.send(snapshot, 1, 7, null))

        replies += ok()
        sender.send(snapshot, 1, 7, null)
        assertEquals("4", body(2)["counters_since_last"]!!.jsonObject["dropped_sender"]!!.jsonPrimitive.content)

        replies += ok()
        sender.send(snapshot, 1, 7, null)
        assertEquals("0", body(3)["counters_since_last"]!!.jsonObject["dropped_sender"]!!.jsonPrimitive.content, "reported once, then reset")
        assertEquals(4L, counters.snapshot()[CounterName.DROPPED_SENDER], "the lifetime total is untouched")
    }

    @Test
    fun `queue depth, missing battery and no last message are reported honestly`() {
        replies += ok()
        sender.send(DeviceSnapshot(null, null, "none", snapshot.permissions, emptyList()), 1, 0, null)
        val b = body()
        assertEquals("0", b["queue_depth"]!!.jsonPrimitive.content)
        assertTrue(b["battery_pct"]!!.toString() == "null")
        assertTrue(b["last_message_at"]!!.toString() == "null")
        assertEquals("none", b["network"]!!.jsonPrimitive.content)
    }

    @Test
    fun `revoked and unusable signatures are passed on, a garbled answer is a retry`() {
        replies += { HttpResponse(403, emptyMap(), """{"ok":false,"error":{"code":"DEVICE_REVOKED","message":"x"}}""".toByteArray()) }
        assertIs<HeartbeatResult.Revoked>(sender.send(snapshot, 1, 7, null))
        replies += { HttpResponse(200, emptyMap(), "not json".toByteArray()) }
        counters.increment(CounterName.UPLOADED)
        assertIs<HeartbeatResult.RetryLater>(sender.send(snapshot, 1, 7, null))
        assertEquals(1L, counters.takeAll()[CounterName.UPLOADED], "counts put back after a garbled answer")
        replies += { HttpResponse(429, mapOf("Retry-After" to "9"), """{"ok":false,"error":{"code":"RATE_LIMITED","message":"x"}}""".toByteArray()) }
        assertEquals(9, assertIs<HeartbeatResult.RetryLater>(sender.send(snapshot, 1, 7, null)).afterSec)
    }

    // ---- the cycle: config refresh and rescans ------------------------------------------------------------------

    private class MemoryConfigPersistence : ConfigPersistence {
        var stored: StoredConfig? = null
        override fun read() = stored
        override fun write(config: StoredConfig) {
            stored = config
        }
        override fun clear() {
            stored = null
        }
    }

    private val configs = ConfigRepository(MemoryConfigPersistence())
    private val inboxRows = ArrayList<InboxSms>()
    private val inbox = InboxReader { from, to -> inboxRows.filter { it.dateSentMillis in from..to } }
    private val rescanQueue = MemoryQueue()
    private val rescan = RescanProcessor(
        inbox = inbox,
        intake = SmsIntake(rescanQueue, MemoryCounters(), AesGcmSecretBox.withRandomKey(), newLocalId = { "r-${rescanQueue.size() + 1}" }),
        subscriptionOfSlot = { if (it == 0) 3 else null },
        slotOfSubscription = { if (it == 3) 0 else null },
    )
    private val cycle = HeartbeatCycle(sender, api, configs, ledger, rescan)

    private fun signedConfig(version: Long): Pair<ByteArray, String> {
        val example = Fixtures.exampleConfigBytes().toString(Charsets.UTF_8).replaceFirst("\"version\": 1", "\"version\": $version")
        val bytes = example.toByteArray()
        return bytes to key.sign(bytes)
    }

    private fun runCycle(canRead: Boolean = true) = cycle.run(snapshot, 1, key.publicKeyB64Url, null, canRead)

    private fun window(from: Long, to: Long) = """[{"request_id":"rs_1","from":"${TimestampFormat.format(from)}","to":"${TimestampFormat.format(to)}","sim_slot":0}]"""

    @Test
    fun `a newer config version in the answer is fetched and installed`() {
        val (body, signature) = signedConfig(1)
        configs.install(body, signature, key.publicKeyB64Url)
        val (newBody, newSignature) = signedConfig(9)
        replies += ok(configVersion = 9)
        replies += { HttpResponse(200, mapOf("X-PG-Config-Signature" to newSignature), newBody) }
        val result = runCycle()
        assertEquals(9L, assertIs<ConfigRefresh.Installed>(result.configRefresh).version)
        assertEquals(9L, configs.current(key.publicKeyB64Url)?.version)
        assertEquals("/v1/devices/config", requests[1].url.substringAfter("api.example.com"))
    }

    @Test
    fun `an equal or older version in the answer fetches nothing`() {
        val (body, signature) = signedConfig(5)
        configs.install(body, signature, key.publicKeyB64Url)
        replies += ok(configVersion = 5)
        assertNull(runCycle().configRefresh)
        replies += ok(configVersion = 3)
        assertNull(runCycle().configRefresh)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a rescan is read from the inbox, tagged, queued once, reported next time and forgotten when the server stops listing it`() {
        val (body, signature) = signedConfig(7)
        configs.install(body, signature, key.publicKeyB64Url)
        val t = 1_793_599_000_000L
        val deposit = Fixtures.smsFixtures().first { it.name.endsWith("deposit-basic.json") }
        val otp = Fixtures.smsFixtures().first { it.name.endsWith("otp-dynamic-password.json") }
        inboxRows += InboxSms("TESTBANK", deposit.body, t + 1_000, 3)
        inboxRows += InboxSms("TESTBANK", otp.body, t + 2_000, 3)
        inboxRows += InboxSms("Stranger", "hi", t + 3_000, 3)
        inboxRows += InboxSms("TESTBANK", deposit.body, t + 4_000, 9) // another SIM: not asked for
        inboxRows += InboxSms("TESTBANK", deposit.body, t - 3_600_000, 3) // outside the window

        replies += ok(rescan = window(t, t + 10_000))
        val first = runCycle()
        assertEquals(1, first.rescanQueued)
        val item = rescanQueue.items.single()
        assertEquals("rs_1", item.rescanRequestId)
        assertEquals(t + 1_000, item.scTimestampMillis, "inbox date_sent is used as the service-centre time")
        assertEquals(0, item.simSlot)
        assertEquals(3, item.subscriptionId)
        assertEquals(mapOf("rs_1" to 1), ledger.done)

        // The server repeats the request; it is not read twice, and the earlier answer is reported.
        replies += ok(rescan = window(t, t + 10_000))
        assertEquals(0, runCycle().rescanQueued)
        assertEquals(1, rescanQueue.size())
        assertEquals("rs_1", body(requests.size - 1)["rescans_done"]!!.jsonArray[0].jsonObject["request_id"]!!.jsonPrimitive.content)
        assertEquals("1", body(requests.size - 1)["rescans_done"]!!.jsonArray[0].jsonObject["found"]!!.jsonPrimitive.content)

        // Once the server no longer lists it, the phone forgets it.
        replies += ok(rescan = "[]")
        runCycle()
        assertTrue(ledger.done.isEmpty())
    }

    @Test
    fun `without permission to read the inbox a rescan is left for the server to repeat`() {
        val (body, signature) = signedConfig(7)
        configs.install(body, signature, key.publicKeyB64Url)
        replies += ok(rescan = window(1_793_599_000_000L, 1_793_599_010_000L))
        assertEquals(0, runCycle(canRead = false).rescanQueued)
        assertTrue(ledger.done.isEmpty(), "not answered, so the server keeps asking")
    }

    @Test
    fun `a window that cannot be read is answered with zero so it stops being repeated`() {
        val (body, signature) = signedConfig(7)
        configs.install(body, signature, key.publicKeyB64Url)
        replies += ok(rescan = """[{"request_id":"rs_bad","from":"yesterday","to":"today","sim_slot":0}]""")
        runCycle()
        assertEquals(mapOf("rs_bad" to 0), ledger.done)
    }

    @Test
    fun `with no config yet the newer version is fetched, and until it arrives no rescan can be classified`() {
        replies += ok(rescan = window(0, 10))
        replies += { HttpResponse(503, emptyMap(), "down".toByteArray()) }
        val result = runCycle()
        assertIs<ConfigRefresh.NotFetched>(result.configRefresh)
        assertEquals(0, result.rescanQueued)
        assertTrue(ledger.done.isEmpty())
    }

    @Test
    fun `a failed heartbeat does nothing else`() {
        replies += { throw IOException("offline") }
        val result = runCycle()
        assertIs<HeartbeatResult.RetryLater>(result.heartbeat)
        assertEquals(0, result.rescanQueued)
        assertNull(result.configRefresh)
        assertEquals(1, requests.size)
    }
}
