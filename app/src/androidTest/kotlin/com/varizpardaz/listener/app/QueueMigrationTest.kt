package com.varizpardaz.listener.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.varizpardaz.listener.app.queue.QueueDatabase
import com.varizpardaz.listener.app.queue.RoomMessageQueue
import com.varizpardaz.listener.core.Channel
import com.varizpardaz.listener.core.queue.ItemKind
import com.varizpardaz.listener.core.queue.QueuedItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A phone that queued items with the first version of the database must keep them when the app is updated to the one that has the
 * `account` column (CCR-1). The old database is built by hand exactly as Room 1 wrote it (app/schemas/.../1.json).
 */
@RunWith(AndroidJUnit4::class)
class QueueMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "queue-migration-test.db"

    @Before
    @After
    fun clean() {
        context.deleteDatabase(name)
    }

    @Test
    fun items_queued_by_version_1_survive_the_upgrade_with_no_account_and_the_new_column_works() {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                "CREATE TABLE IF NOT EXISTS `queue` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `localId` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                    "`channel` TEXT NOT NULL, `sender` TEXT, `packageName` TEXT, `simSlot` INTEGER, `subscriptionId` INTEGER, " +
                    "`scTimestampMillis` INTEGER NOT NULL, `receivedAtMillis` INTEGER NOT NULL, `sealedBody` BLOB, `templateId` TEXT, `bank` TEXT, " +
                    "`amountRial` INTEGER, `balanceRial` INTEGER, `configVersion` INTEGER NOT NULL, `rescanRequestId` TEXT)",
            )
            old.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_queue_localId` ON `queue` (`localId`)")
            old.execSQL("CREATE TABLE IF NOT EXISTS `counters` (`name` TEXT NOT NULL, `value` INTEGER NOT NULL, PRIMARY KEY(`name`))")
            old.execSQL(
                "INSERT INTO queue (localId, kind, channel, sender, packageName, simSlot, subscriptionId, scTimestampMillis, receivedAtMillis, " +
                    "sealedBody, templateId, bank, amountRial, balanceRial, configVersion, rescanRequestId) " +
                    "VALUES ('old-1', 'balance_marker', 'sms', 'TESTBANK', NULL, 0, 1, 1000, 2000, NULL, 'wd1', 'testbank', 100, 200, 7, NULL)",
            )
            old.execSQL("INSERT INTO counters (name, value) VALUES ('total_uploaded', 5)")
            old.version = 1
        }

        val db = QueueDatabase.create(context, name)
        try {
            val queue = RoomMessageQueue(db)
            val survivors = queue.peek(10)
            assertEquals(listOf("old-1"), survivors.map { it.localId })
            assertEquals(100L, survivors.single().amountRial)
            assertNull("an item from the old version has no account", survivors.single().account)
            assertEquals(5L, db.counters().all().single { it.name == "total_uploaded" }.value)

            queue.enqueue(
                QueuedItem(
                    localId = "new-1", kind = ItemKind.BALANCE_MARKER, channel = Channel.SMS, sender = "TESTBANK", packageName = null, simSlot = 0,
                    subscriptionId = 1, scTimestampMillis = 3000, receivedAtMillis = 4000, sealedBody = null, templateId = "wd1", bank = "testbank",
                    amountRial = 300, balanceRial = 400, configVersion = 8, account = "1234",
                ),
            )
            assertEquals(listOf(null, "1234"), queue.peek(10).map { it.account })
        } finally {
            db.close()
        }
    }
}
