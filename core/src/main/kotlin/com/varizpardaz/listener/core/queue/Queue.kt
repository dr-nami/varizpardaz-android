package com.varizpardaz.listener.core.queue

import com.varizpardaz.listener.core.Channel
import com.varizpardaz.listener.core.DropReason

enum class ItemKind(val wire: String) {
    DEPOSIT("deposit"),
    BALANCE_MARKER("balance_marker"),
}

/**
 * One thing waiting to be uploaded. A deposit carries its text only as [sealedBody] (AES-GCM under a Keystore key,
 * bound to [localId]); a balance marker carries numbers and no text at all. [toString] prints neither.
 */
class QueuedItem(
    /** Position in the queue, assigned by the store. Upload order follows it. */
    val seq: Long = 0,
    /** UUID v4, the `local_id` of the upload. */
    val localId: String,
    val kind: ItemKind,
    val channel: Channel,
    /** SMS originating address as received; null for notifications. */
    val sender: String?,
    /** Posting package for notifications; null for SMS. */
    val packageName: String?,
    val simSlot: Int?,
    val subscriptionId: Int?,
    /** Service-centre time of the SMS (or `postTime` of a notification), epoch millis. */
    val scTimestampMillis: Long,
    val receivedAtMillis: Long,
    /** Deposit only. */
    val sealedBody: ByteArray?,
    /** Null for the keyword fallback. */
    val templateId: String?,
    val bank: String?,
    /** Balance marker only. */
    val amountRial: Long?,
    val balanceRial: Long?,
    val configVersion: Long,
    /** Balance marker only: the template's `account` fragment as captured (e.g. the last digits), or null. */
    val account: String? = null,
    /** Set for items found by an inbox rescan (A5). */
    val rescanRequestId: String? = null,
) {
    override fun toString(): String = "QueuedItem($kind, localId=$localId)"
}

/** Durable FIFO of items waiting for upload. Implementations must survive process death and reboots. */
interface MessageQueue {
    /** Appends and returns the stored item's `seq`. */
    fun enqueue(item: QueuedItem): Long

    /** Oldest first, at most [limit]. */
    fun peek(limit: Int): List<QueuedItem>

    /** Removes items the server has answered (accepted, duplicate or rejected). */
    fun remove(localIds: Collection<String>)

    fun size(): Int

    fun clear()
}

/** What the heartbeat reports as `counters_since_last`. Only counts; never anything about a message. */
enum class CounterName(val wire: String) {
    UPLOADED("uploaded"),
    DROPPED_SENDER("dropped_sender"),
    DROPPED_OTP("dropped_otp"),
    DROPPED_UNCLASSIFIED("dropped_unclassified"),

    /** Items the server answered `rejected`. Local only: not part of the heartbeat counters. */
    REJECTED("rejected"),
    ;

    companion object {
        fun of(reason: DropReason): CounterName = when (reason) {
            DropReason.SENDER -> DROPPED_SENDER
            DropReason.OTP -> DROPPED_OTP
            DropReason.UNCLASSIFIED -> DROPPED_UNCLASSIFIED
        }
    }
}

interface Counters {
    fun increment(name: CounterName, by: Int = 1)

    /** Lifetime totals since pairing, for the status screen. Never reset by [takeAll]. */
    fun snapshot(): Map<CounterName, Long>

    /** What happened since the previous call, atomically reset: what a heartbeat reports as `counters_since_last`. */
    fun takeAll(): Map<CounterName, Long>

    /** Puts counts taken by [takeAll] back into "since last" after a heartbeat failed. Lifetime totals are not touched. */
    fun putBack(values: Map<CounterName, Long>)

    /** Forget everything, totals included (unpair). */
    fun clear()
}

/** For work that must not count: a rescan re-reads old messages and must not inflate the dropped counters. */
object NullCounters : Counters {
    override fun increment(name: CounterName, by: Int) = Unit

    override fun snapshot(): Map<CounterName, Long> = CounterName.entries.associateWith { 0L }

    override fun takeAll(): Map<CounterName, Long> = snapshot()

    override fun putBack(values: Map<CounterName, Long>) = Unit

    override fun clear() = Unit
}
