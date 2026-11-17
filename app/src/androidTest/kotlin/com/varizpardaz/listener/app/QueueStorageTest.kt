package com.varizpardaz.listener.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.varizpardaz.listener.app.data.KeystoreSecretBox
import com.varizpardaz.listener.app.queue.QueueDatabase
import com.varizpardaz.listener.app.queue.RoomCounters
import com.varizpardaz.listener.app.queue.RoomMessageQueue
import com.varizpardaz.listener.core.ConfigLoader
import com.varizpardaz.listener.core.ConfigResult
import com.varizpardaz.listener.core.intake.SimRef
import com.varizpardaz.listener.core.intake.SmsIntake
import com.varizpardaz.listener.core.intake.SmsPart
import com.varizpardaz.listener.core.queue.CounterName
import com.varizpardaz.listener.core.queue.ItemKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The SMS path against the real Room database and the real Android Keystore. */
@RunWith(AndroidJUnit4::class)
class QueueStorageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val dbName = "queue-test.db"
    private lateinit var db: QueueDatabase

    @Before
    fun open() {
        context.deleteDatabase(dbName)
        KeystoreSecretBox.deleteKey(KeystoreSecretBox.QUEUE_ALIAS)
        db = QueueDatabase.create(context, dbName)
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase(dbName)
        KeystoreSecretBox.deleteKey(KeystoreSecretBox.QUEUE_ALIAS)
    }

    private fun fixtureBody(name: String): String =
        Json.parseToJsonElement(assets.open("sms/testbank/$name.json").use { it.readBytes().toString(Charsets.UTF_8) })
            .jsonObject["body"]!!.jsonPrimitive.content

    private fun bytesContain(haystack: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..haystack.size - n.size) {
            for (j in n.indices) if (haystack[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }

    private val config by lazy {
        (ConfigLoader.load(assets.open("config.example.json").use { it.readBytes() }) as ConfigResult.Accepted).config
    }

    private fun intake(): Triple<SmsIntake, RoomMessageQueue, RoomCounters> {
        val queue = RoomMessageQueue(db)
        val counters = RoomCounters(db)
        val box = KeystoreSecretBox.create(KeystoreSecretBox.QUEUE_ALIAS)
        return Triple(SmsIntake(queue, counters, box), queue, counters)
    }

    private fun feed(intake: SmsIntake, name: String) =
        intake.handle(listOf(SmsPart("TESTBANK", fixtureBody(name), System.currentTimeMillis())), SimRef(0, 1), config)

    @Test
    fun deposits_withdrawals_and_otps_end_up_where_they_should_and_nowhere_else() {
        val (intake, queue, counters) = intake()
        feed(intake, "deposit-basic")
        feed(intake, "withdrawal-basic")
        feed(intake, "otp-dynamic-password")
        feed(intake, "other-promo")

        val items = queue.peek(10)
        assertEquals(listOf(ItemKind.DEPOSIT, ItemKind.BALANCE_MARKER), items.map { it.kind })
        assertTrue(items[0].sealedBody != null)
        assertNull(items[1].sealedBody)
        assertEquals(1_250_000L, items[1].amountRial)
        assertEquals("1234", items[1].account)
        val c = counters.snapshot()
        assertEquals(1L, c[CounterName.DROPPED_OTP])
        assertEquals(1L, c[CounterName.DROPPED_UNCLASSIFIED])

        // What is on disk (main file, write-ahead log, shared memory) holds no readable message text.
        db.close()
        val base = context.getDatabasePath(dbName).path
        val onDisk = listOf("", "-wal", "-shm").map { File(base + it) }.filter { it.exists() }.map { it.readBytes() }
        assertTrue(onDisk.isNotEmpty())
        for (bytes in onDisk) {
            assertFalse("deposit text in clear", bytesContain(bytes, "5,000,000"))
            assertFalse("deposit text in clear", bytesContain(bytes, "واریز"))
            assertFalse("withdrawal text on disk", bytesContain(bytes, "برداشت"))
            assertFalse("otp text on disk", bytesContain(bytes, "482913"))
            assertFalse("otp text on disk", bytesContain(bytes, "رمز پویا"))
            assertFalse("promo text on disk", bytesContain(bytes, "جشنواره"))
        }
    }

    @Test
    fun queued_items_keep_their_order_and_removal_works() {
        val (intake, queue, _) = intake()
        repeat(3) { feed(intake, "deposit-basic") }
        val all = queue.peek(10)
        assertEquals(3, all.size)
        assertEquals(all.map { it.seq }.sorted(), all.map { it.seq })
        queue.remove(listOf(all[1].localId))
        assertEquals(listOf(all[0].localId, all[2].localId), queue.peek(10).map { it.localId })
        queue.clear()
        assertEquals(0, queue.size())
    }

    @Test
    fun a_complete_wipe_leaves_no_trace_of_the_rows_in_any_database_file() {
        val (intake, queue, counters) = intake()
        feed(intake, "deposit-basic")
        feed(intake, "withdrawal-basic")
        feed(intake, "other-promo")
        assertEquals(2, queue.size())

        db.wipeCompletely()
        assertEquals(0, queue.size())
        assertEquals(0L, counters.snapshot()[CounterName.DROPPED_UNCLASSIFIED])

        db.close()
        val base = context.getDatabasePath(dbName).path
        for (suffix in listOf("", "-wal", "-shm")) {
            val file = File(base + suffix)
            if (!file.exists()) continue
            val bytes = file.readBytes()
            for (needle in listOf("testbank-dep-v1", "testbank-wd-v1", "testbank", "TESTBANK")) {
                assertFalse("'$needle' still in ${file.name}", bytesContain(bytes, needle))
            }
        }
    }

    @Test
    fun counters_keep_lifetime_totals_and_report_since_last_once() {
        val (_, _, counters) = intake()
        counters.increment(CounterName.DROPPED_SENDER, 2)
        counters.increment(CounterName.DROPPED_SENDER)
        assertEquals(3L, counters.snapshot()[CounterName.DROPPED_SENDER])
        assertEquals(3L, counters.takeAll()[CounterName.DROPPED_SENDER])
        assertEquals(0L, counters.takeAll()[CounterName.DROPPED_SENDER])
        assertEquals(3L, counters.snapshot()[CounterName.DROPPED_SENDER])

        // A failed heartbeat puts its numbers back without touching the totals.
        counters.putBack(mapOf(CounterName.DROPPED_SENDER to 2L))
        assertEquals(2L, counters.takeAll()[CounterName.DROPPED_SENDER])
        assertEquals(3L, counters.snapshot()[CounterName.DROPPED_SENDER])
    }

    @Test
    fun a_destroyed_key_makes_queued_bodies_unreadable() {
        val (intake, queue, _) = intake()
        feed(intake, "deposit-basic")
        val item = queue.peek(1).single()
        val box = KeystoreSecretBox.create(KeystoreSecretBox.QUEUE_ALIAS)
        assertEquals(fixtureBody("deposit-basic"), String(box.open(item.sealedBody!!, item.localId.toByteArray()), Charsets.UTF_8))
        box.destroyKey()
        try {
            box.open(item.sealedBody!!, item.localId.toByteArray())
            org.junit.Assert.fail("a body must not open after its key was destroyed")
        } catch (_: com.varizpardaz.listener.core.pairing.SecretBoxException) {
            // expected
        }
    }
}
