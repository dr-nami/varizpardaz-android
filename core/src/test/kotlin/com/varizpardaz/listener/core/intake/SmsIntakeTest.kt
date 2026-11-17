package com.varizpardaz.listener.core.intake

import com.varizpardaz.listener.core.Fixtures
import com.varizpardaz.listener.core.loadExampleConfig
import com.varizpardaz.listener.core.pairing.AesGcmSecretBox
import com.varizpardaz.listener.core.pairing.SecretBoxException
import com.varizpardaz.listener.core.queue.CounterName
import com.varizpardaz.listener.core.queue.Counters
import com.varizpardaz.listener.core.queue.ItemKind
import com.varizpardaz.listener.core.queue.MessageQueue
import com.varizpardaz.listener.core.queue.QueuedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryQueue : MessageQueue {
    val items = ArrayList<QueuedItem>()
    var failNext = false
    private var seq = 0L

    override fun enqueue(item: QueuedItem): Long {
        if (failNext) {
            failNext = false
            throw IllegalStateException("disk full")
        }
        items += QueuedItem(
            seq = ++seq, localId = item.localId, kind = item.kind, channel = item.channel, sender = item.sender,
            packageName = item.packageName, simSlot = item.simSlot, subscriptionId = item.subscriptionId,
            scTimestampMillis = item.scTimestampMillis, receivedAtMillis = item.receivedAtMillis, sealedBody = item.sealedBody,
            templateId = item.templateId, bank = item.bank, amountRial = item.amountRial, balanceRial = item.balanceRial,
            configVersion = item.configVersion, rescanRequestId = item.rescanRequestId, account = item.account,
        )
        return seq
    }

    override fun peek(limit: Int) = items.sortedBy { it.seq }.take(limit)
    override fun remove(localIds: Collection<String>) {
        items.removeAll { it.localId in localIds }
    }
    override fun size() = items.size
    override fun clear() = items.clear()
}

class MemoryCounters : Counters {
    private val totals = HashMap<CounterName, Long>()
    private val sinceLast = HashMap<CounterName, Long>()
    override fun increment(name: CounterName, by: Int) {
        totals[name] = (totals[name] ?: 0L) + by
        sinceLast[name] = (sinceLast[name] ?: 0L) + by
    }
    override fun snapshot() = CounterName.entries.associateWith { totals[it] ?: 0L }
    override fun takeAll() = CounterName.entries.associateWith { sinceLast[it] ?: 0L }.also { sinceLast.clear() }
    override fun putBack(values: Map<CounterName, Long>) {
        values.forEach { (name, value) -> if (value > 0) sinceLast[name] = (sinceLast[name] ?: 0L) + value }
    }
    override fun clear() {
        totals.clear()
        sinceLast.clear()
    }
}

class SmsIntakeTest {
    private val config = loadExampleConfig()
    private val queue = MemoryQueue()
    private val counters = MemoryCounters()
    private val box = AesGcmSecretBox.withRandomKey()
    private var ids = 0
    private val intake = SmsIntake(queue, counters, box, newLocalId = { "id-${++ids}" }, nowMillis = { 1_793_600_000_000 })
    private val sim = SimRef(slot = 1, subscriptionId = 3)
    private val sc = 1_793_599_990_000L

    private fun fixture(name: String) = Fixtures.smsFixtures().first { it.name.endsWith(name) }

    private fun handle(sender: String, body: String, sc: Long = this.sc) =
        intake.handle(listOf(SmsPart(sender, body, sc)), sim, config)

    private fun nothingElseKept() {
        // Dropped messages leave no trace except counters: no item is stored.
        assertEquals(0, queue.size())
    }

    @Test
    fun `a deposit is queued with a sealed body, its template and where it came from`() {
        val f = fixture("deposit-basic.json")
        val outcome = handle(f.sender, f.body)
        assertEquals(1, outcome.queued)
        val item = queue.items.single()
        assertEquals(ItemKind.DEPOSIT, item.kind)
        assertEquals("id-1", item.localId)
        assertEquals("TESTBANK", item.sender)
        assertEquals("testbank-dep-v1", item.templateId)
        assertEquals("testbank", item.bank)
        assertEquals(1, item.simSlot)
        assertEquals(3, item.subscriptionId)
        assertEquals(sc, item.scTimestampMillis)
        assertEquals(1_793_600_000_000, item.receivedAtMillis)
        assertEquals(config.version, item.configVersion)
        assertNull(item.amountRial)
        // The raw text, unchanged, is what a later upload sends...
        assertEquals(f.body, String(box.open(assertNotNull(item.sealedBody), "id-1".toByteArray()), Charsets.UTF_8))
        // ...and it is not readable in the stored form.
        assertTrue("testbank" !in String(item.sealedBody, Charsets.ISO_8859_1).lowercase())
    }

    @Test
    fun `the sealed body only opens for its own row`() {
        handle("TESTBANK", fixture("deposit-basic.json").body)
        assertFailsWith<SecretBoxException> { box.open(queue.items.single().sealedBody!!, "id-2".toByteArray()) }
    }

    @Test
    fun `keyword fallback is queued without a template`() {
        val f = fixture("keyword-fallback.json")
        handle(f.sender, f.body)
        val item = queue.items.single()
        assertEquals(ItemKind.DEPOSIT, item.kind)
        assertNull(item.templateId)
    }

    @Test
    fun `a withdrawal becomes numbers only and its text is never stored`() {
        val f = fixture("withdrawal-basic.json")
        handle(f.sender, f.body)
        val item = queue.items.single()
        assertEquals(ItemKind.BALANCE_MARKER, item.kind)
        assertEquals(1_250_000L, item.amountRial)
        assertEquals(96_750_000L, item.balanceRial)
        assertEquals("testbank-wd-v1", item.templateId)
        assertEquals("1234", item.account, "the template's account group travels with the marker (CCR-1)")
        assertNull(item.sealedBody)
    }

    @Test
    fun `an otp is dropped, counted, and leaves nothing behind`() {
        val f = fixture("otp-dynamic-password.json")
        val outcome = handle(f.sender, f.body)
        assertEquals(1, outcome.dropped)
        nothingElseKept()
        assertEquals(1L, counters.snapshot()[CounterName.DROPPED_OTP])
    }

    @Test
    fun `promotions and strangers are only counted`() {
        handle(fixture("other-promo.json").sender, fixture("other-promo.json").body)
        handle("SomeoneElse", "واریز: ۵,۰۰۰,۰۰۰ ریال حساب: ۱۲۳۴ مانده: ۹۸,۰۰۰,۰۰۰")
        handle("", "x")
        nothingElseKept()
        val c = counters.snapshot()
        assertEquals(1L, c[CounterName.DROPPED_UNCLASSIFIED])
        assertEquals(2L, c[CounterName.DROPPED_SENDER])
    }

    @Test
    fun `segments of one message are joined before classification`() {
        val body = fixture("deposit-basic.json").body
        val cut = body.length / 2
        val outcome = intake.handle(
            listOf(SmsPart("TESTBANK", body.substring(0, cut), sc), SmsPart("TESTBANK", body.substring(cut), sc)),
            sim,
            config,
        )
        assertEquals(1, outcome.queued)
        assertEquals(body, String(box.open(queue.items.single().sealedBody!!, "id-1".toByteArray()), Charsets.UTF_8))
        assertEquals("testbank-dep-v1", queue.items.single().templateId)
    }

    @Test
    fun `different senders or timestamps in one broadcast stay separate`() {
        val parts = listOf(
            SmsPart("TESTBANK", "a", 1), SmsPart("TESTBANK", "b", 2), SmsPart("OTHER", "c", 1), SmsPart("TESTBANK", "d", 1), SmsPart("TESTBANK", null, 1),
        )
        val joined = SmsAssembler.assemble(parts)
        assertEquals(listOf("ad", "b", "c"), joined.map { it.body })
        assertEquals(listOf("TESTBANK", "TESTBANK", "OTHER"), joined.map { it.sender })
    }

    @Test
    fun `a missing service centre time falls back to the arrival time`() {
        handle("TESTBANK", fixture("deposit-basic.json").body, sc = 0)
        assertEquals(1_793_600_000_000, queue.items.single().scTimestampMillis)
    }

    @Test
    fun `a storage failure is contained and the next message still works`() {
        queue.failNext = true
        val f = fixture("deposit-basic.json")
        assertEquals(1, handle(f.sender, f.body).failed)
        assertEquals(0, queue.size())
        assertEquals(1, handle(f.sender, f.body).queued)
    }

    @Test
    fun `a sealing failure is contained too`() {
        val broken = SmsIntake(queue, counters, object : com.varizpardaz.listener.core.pairing.SecretBox {
            override fun seal(plain: ByteArray, aad: ByteArray): ByteArray = throw IllegalStateException("keystore gone")
            override fun open(sealed: ByteArray, aad: ByteArray): ByteArray = throw SecretBoxException("x")
            override fun destroyKey() = Unit
        })
        val f = fixture("deposit-basic.json")
        assertEquals(1, broken.handle(listOf(SmsPart(f.sender, f.body, sc)), sim, config).failed)
        assertEquals(0, queue.size())
    }

    @Test
    fun `items come out in arrival order`() {
        val f = fixture("deposit-basic.json")
        handle(f.sender, f.body, sc = 1)
        handle(f.sender, f.body, sc = 2)
        handle(f.sender, f.body, sc = 3)
        assertEquals(listOf(1L, 2L, 3L), queue.peek(10).map { it.scTimestampMillis })
        assertEquals(listOf("id-1", "id-2"), queue.peek(2).map { it.localId })
    }

    @Test
    fun `counters can be taken and reset for the heartbeat`() {
        handle("Stranger", "x")
        val first = counters.takeAll()
        assertEquals(1L, first[CounterName.DROPPED_SENDER])
        assertEquals(0L, counters.takeAll()[CounterName.DROPPED_SENDER], "since-last starts again from zero")
        assertEquals(1L, counters.snapshot()[CounterName.DROPPED_SENDER], "the lifetime total stays for the status screen")
    }

    @Test
    fun `no part of a message appears in any printed form`() {
        val secretText = "رمز پویا 998877 TESTBANK"
        val rendered = listOf(SmsPart("TESTBANK", secretText, 1).toString(), SmsAssembler.assemble(listOf(SmsPart("a", secretText, 1))).toString())
        rendered.forEach { assertTrue("998877" !in it) }
        handle("TESTBANK", fixture("deposit-basic.json").body)
        assertTrue("5,000,000" !in queue.items.single().toString())
        assertTrue("998877" !in intake.handle(listOf(SmsPart("TESTBANK", secretText, 1)), sim, config).toString())
    }

    // ---- bank-app notifications --------------------------------------------------------------------------------

    private fun notification(pkg: String, body: String, postTime: Long = sc) = intake.handleNotification(pkg, body, postTime, config)

    @Test
    fun `a deposit notification is queued with its package, post time and sealed text`() {
        val body = fixture("deposit-basic.json").body
        assertEquals(1, notification("ir.example.testbank", body).queued)
        val item = queue.items.single()
        assertEquals(ItemKind.DEPOSIT, item.kind)
        assertEquals(com.varizpardaz.listener.core.Channel.NOTIFICATION, item.channel)
        assertEquals("ir.example.testbank", item.packageName)
        assertNull(item.sender)
        assertNull(item.simSlot)
        assertNull(item.subscriptionId)
        assertEquals(sc, item.scTimestampMillis)
        assertEquals("testbank-dep-v1", item.templateId)
        assertEquals(body, String(box.open(assertNotNull(item.sealedBody), "id-1".toByteArray()), Charsets.UTF_8))
    }

    @Test
    fun `a withdrawal notification becomes numbers only`() {
        notification("ir.example.testbank", fixture("withdrawal-basic.json").body)
        val item = queue.items.single()
        assertEquals(ItemKind.BALANCE_MARKER, item.kind)
        assertEquals("ir.example.testbank", item.packageName)
        assertEquals(1_250_000L, item.amountRial)
        assertNull(item.sealedBody)
    }

    @Test
    fun `an otp notification and a notification from another app leave only counters`() {
        notification("ir.example.testbank", fixture("otp-dynamic-password.json").body)
        notification("com.some.other.app", fixture("deposit-basic.json").body)
        notification("", "x")
        nothingElseKept()
        val c = counters.snapshot()
        assertEquals(1L, c[CounterName.DROPPED_OTP])
        assertEquals(2L, c[CounterName.DROPPED_SENDER])
    }

    @Test
    fun `the package check says which apps may be read at all`() {
        assertTrue(config.isNotificationPackage("ir.example.testbank"))
        assertTrue(!config.isNotificationPackage("com.whatsapp"))
        assertTrue(!config.isNotificationPackage(""))
    }
}
