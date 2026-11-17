package com.varizpardaz.listener.app.queue

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteDatabase

/** One queued upload. The deposit text is only ever stored in [sealedBody] (AES-GCM, Keystore key). */
@Entity(tableName = "queue", indices = [Index(value = ["localId"], unique = true)])
class QueueRow(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val localId: String,
    val kind: String,
    val channel: String,
    val sender: String?,
    val packageName: String?,
    val simSlot: Int?,
    val subscriptionId: Int?,
    val scTimestampMillis: Long,
    val receivedAtMillis: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val sealedBody: ByteArray?,
    val templateId: String?,
    val bank: String?,
    val amountRial: Long?,
    val balanceRial: Long?,
    val configVersion: Long,
    val rescanRequestId: String?,
    /** Balance markers only (CCR-1). Added in schema version 2. */
    val account: String? = null,
)

@Entity(tableName = "counters")
class CounterRow(@PrimaryKey val name: String, val value: Long)

@Dao
interface QueueDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(row: QueueRow): Long

    @Query("SELECT * FROM queue ORDER BY seq ASC LIMIT :limit")
    fun oldest(limit: Int): List<QueueRow>

    @Query("DELETE FROM queue WHERE localId IN (:localIds)")
    fun delete(localIds: List<String>)

    @Query("SELECT COUNT(*) FROM queue")
    fun count(): Int

    @Query("DELETE FROM queue")
    fun deleteAll()
}

@Dao
abstract class CounterDao {
    // No UPSERT: the phones we support start at Android 8, whose SQLite predates it.
    @Query("UPDATE counters SET value = value + :by WHERE name = :name")
    abstract fun bump(name: String, by: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun create(row: CounterRow): Long

    @Query("SELECT * FROM counters")
    abstract fun all(): List<CounterRow>

    @Query("DELETE FROM counters")
    abstract fun deleteAll()

    @Transaction
    open fun add(name: String, by: Int) {
        if (bump(name, by) == 0 && create(CounterRow(name, by.toLong())) == -1L) bump(name, by)
    }

    // Rows named "total_*" are the lifetime totals; everything else is "since the last heartbeat".
    @Query("SELECT * FROM counters WHERE substr(name, 1, 6) != 'total_'")
    abstract fun sinceLast(): List<CounterRow>

    @Query("DELETE FROM counters WHERE substr(name, 1, 6) != 'total_'")
    abstract fun deleteSinceLast()

    @Transaction
    open fun takeSinceLast(): List<CounterRow> = sinceLast().also { deleteSinceLast() }
}

@Database(entities = [QueueRow::class, CounterRow::class], version = 2, exportSchema = true)
abstract class QueueDatabase : RoomDatabase() {
    abstract fun queue(): QueueDao

    abstract fun counters(): CounterDao

    /**
     * Empties every table and truncates the write-ahead log. `clearAllTables()` alone leaves the old rows in the tail of
     * the WAL file, where metadata (senders, template ids, balance markers) would linger after an unpair.
     */
    fun wipeCompletely() {
        clearAllTables()
        openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
    }

    companion object {
        const val FILE_NAME = "queue.db"

        /** Version 2 adds `queue.account` (the withdrawal template's account fragment on a balance marker). Items already queued stay and get null. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE queue ADD COLUMN account TEXT")
            }
        }

        fun create(context: Context, name: String = FILE_NAME): QueueDatabase =
            Room.databaseBuilder(context, QueueDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Deleted rows are overwritten with zeros, not just unlinked (uploaded items, unpair).
                        db.query("PRAGMA secure_delete = ON").close()
                    }
                })
                .build()
    }
}
