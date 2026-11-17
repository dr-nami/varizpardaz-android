package com.varizpardaz.listener.app.queue

import com.varizpardaz.listener.core.Channel
import com.varizpardaz.listener.core.queue.CounterName
import com.varizpardaz.listener.core.queue.Counters
import com.varizpardaz.listener.core.queue.ItemKind
import com.varizpardaz.listener.core.queue.MessageQueue
import com.varizpardaz.listener.core.queue.QueuedItem

/** [MessageQueue] on Room. All calls block on disk: never from the main thread. */
class RoomMessageQueue(private val db: QueueDatabase) : MessageQueue {
    private val dao = db.queue()

    override fun enqueue(item: QueuedItem): Long = dao.insert(item.toRow())

    override fun peek(limit: Int): List<QueuedItem> = dao.oldest(limit).map { it.toItem() }

    override fun remove(localIds: Collection<String>) {
        // SQLite caps the number of bound variables per statement.
        localIds.chunked(CHUNK).forEach(dao::delete)
    }

    override fun size(): Int = dao.count()

    override fun clear() = dao.deleteAll()

    private fun QueuedItem.toRow() = QueueRow(
        localId = localId, kind = kind.wire, channel = channel.wire, sender = sender, packageName = packageName,
        simSlot = simSlot, subscriptionId = subscriptionId, scTimestampMillis = scTimestampMillis,
        receivedAtMillis = receivedAtMillis, sealedBody = sealedBody, templateId = templateId, bank = bank,
        amountRial = amountRial, balanceRial = balanceRial, configVersion = configVersion, rescanRequestId = rescanRequestId,
        account = account,
    )

    private fun QueueRow.toItem() = QueuedItem(
        seq = seq, localId = localId, kind = ItemKind.entries.first { it.wire == kind },
        channel = Channel.entries.first { it.wire == channel }, sender = sender, packageName = packageName,
        simSlot = simSlot, subscriptionId = subscriptionId, scTimestampMillis = scTimestampMillis,
        receivedAtMillis = receivedAtMillis, sealedBody = sealedBody, templateId = templateId, bank = bank,
        amountRial = amountRial, balanceRial = balanceRial, configVersion = configVersion, rescanRequestId = rescanRequestId,
        account = account,
    )

    private companion object {
        const val CHUNK = 500
    }
}

/**
 * [Counters] on Room, so counts survive a killed process. Two rows per counter: "since last heartbeat" (reset by
 * [takeAll]) and a lifetime "total_" row for the status screen.
 */
class RoomCounters(private val db: QueueDatabase) : Counters {
    private val dao = db.counters()

    override fun increment(name: CounterName, by: Int) {
        dao.add(name.wire, by)
        dao.add(TOTAL + name.wire, by)
    }

    override fun snapshot(): Map<CounterName, Long> =
        CounterName.entries.let { names ->
            val byName = dao.all().associate { it.name to it.value }
            names.associateWith { byName[TOTAL + it.wire] ?: 0L }
        }

    override fun takeAll(): Map<CounterName, Long> {
        val taken = dao.takeSinceLast().associate { it.name to it.value }
        return CounterName.entries.associateWith { taken[it.wire] ?: 0L }
    }

    override fun putBack(values: Map<CounterName, Long>) {
        values.forEach { (name, value) -> if (value > 0) dao.add(name.wire, value.toInt()) }
    }

    override fun clear() = dao.deleteAll()

    private companion object {
        const val TOTAL = "total_"
    }
}
