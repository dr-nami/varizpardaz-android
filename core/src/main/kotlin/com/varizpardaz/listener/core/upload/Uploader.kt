package com.varizpardaz.listener.core.upload

import com.varizpardaz.listener.core.TimestampFormat
import com.varizpardaz.listener.core.api.ApiResult
import com.varizpardaz.listener.core.api.SignedApi
import com.varizpardaz.listener.core.pairing.SecretBox
import com.varizpardaz.listener.core.pairing.SecretBoxException
import com.varizpardaz.listener.core.queue.CounterName
import com.varizpardaz.listener.core.queue.Counters
import com.varizpardaz.listener.core.queue.ItemKind
import com.varizpardaz.listener.core.queue.MessageQueue
import com.varizpardaz.listener.core.queue.QueuedItem
import com.varizpardaz.listener.core.wire.BalanceMarkerItem
import com.varizpardaz.listener.core.wire.DepositItem
import com.varizpardaz.listener.core.wire.ErrorCode
import com.varizpardaz.listener.core.wire.IngestItem
import com.varizpardaz.listener.core.wire.IngestResponse
import com.varizpardaz.listener.core.wire.Wire
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What one drain run did. Counts only. */
class Tally(
    /** Accepted or duplicate: the server has them. */
    var uploaded: Int = 0,
    /** Answered `rejected`, or named by a 422: final. */
    var rejected: Int = 0,
    /** Removed locally because they could not be sent at all (unreadable body, bigger than a request may be). */
    var dropped: Int = 0,
) {
    override fun toString(): String = "Tally(uploaded=$uploaded, rejected=$rejected, dropped=$dropped)"
}

sealed interface DrainOutcome {
    /** The queue is empty. */
    class Done(val tally: Tally) : DrainOutcome

    /** This run hit its batch limit; more is waiting, run again soon. */
    class MoreWaiting(val tally: Tally) : DrainOutcome

    /** Something transient went wrong; nothing was lost and order is intact. [afterSec] is the server's `Retry-After`. */
    class RetryLater(val afterSec: Int?, val tally: Tally) : DrainOutcome

    /** `DEVICE_REVOKED`. */
    data object Revoked : DrainOutcome

    /** The server keeps rejecting this device's signature. */
    data object RepairNeeded : DrainOutcome
}

/** Retry delays: 5 s, doubling, never more than 5 minutes (contract section 7). */
object Backoff {
    const val MAX_SECONDS = 300

    fun delaySeconds(consecutiveFailures: Int): Int {
        if (consecutiveFailures <= 0) return 0
        val shift = (consecutiveFailures - 1).coerceAtMost(6)
        return (5 shl shift).coerceAtMost(MAX_SECONDS)
    }
}

/**
 * Sends the queue to `POST /v1/ingest/android` (contract sections 7 and 10): oldest first, at most [maxBatch] items and
 * 256 KB per request, and an item leaves the queue only after the server has answered for it (`accepted`, `duplicate`
 * or `rejected`). Anything else leaves the queue exactly as it was, so a crash or a bad network never loses or reorders a deposit.
 */
class Uploader(
    private val queue: MessageQueue,
    private val counters: Counters,
    private val api: SignedApi,
    /** Opens the sealed deposit bodies. */
    private val bodyBox: SecretBox,
    private val maxBatch: Int,
    private val maxRequestBytes: Int = MAX_REQUEST_BYTES,
    private val maxBatchesPerRun: Int = 200,
) {
    private class Prepared(val item: QueuedItem, val wire: IngestItem)

    fun drain(): DrainOutcome {
        var size = maxBatch.coerceAtLeast(1)
        val tally = Tally()
        var batches = 0
        while (batches < maxBatchesPerRun) {
            val queued = queue.peek(size)
            if (queued.isEmpty()) return finish(DrainOutcome.Done(tally), tally)

            var batch = prepare(queued, tally)
            if (batch.isEmpty()) continue // all unreadable: they were removed, so the queue moved
            var body = encode(batch)
            while (body.size > maxRequestBytes && batch.size > 1) {
                batch = batch.take((batch.size + 1) / 2)
                body = encode(batch)
            }
            if (body.size > maxRequestBytes) {
                drop(batch.single().item, tally) // one item that can never fit a request
                continue
            }

            when (val result = api.post(PATH, body)) {
                is ApiResult.Success -> {
                    batches++
                    if (!applyResults(batch, result, tally)) return finish(DrainOutcome.RetryLater(null, tally), tally)
                }
                ApiResult.Revoked -> return finish(DrainOutcome.Revoked, tally)
                ApiResult.RepairNeeded -> return finish(DrainOutcome.RepairNeeded, tally)
                is ApiResult.Unreachable -> return finish(DrainOutcome.RetryLater(null, tally), tally)
                is ApiResult.Failure -> when (result.code) {
                    ErrorCode.VALIDATION_ERROR -> {
                        val named = namedItems(result.error?.fields, batch)
                        when {
                            named.isNotEmpty() -> named.forEach { reject(it.item, tally) }
                            batch.size > 1 -> size = (batch.size / 2).coerceAtLeast(1)
                            hasKey(result.error?.fields, "items") -> return finish(DrainOutcome.RetryLater(null, tally), tally)
                            else -> reject(batch.single().item, tally) // a lone item the server cannot parse is final
                        }
                    }
                    ErrorCode.PAYLOAD_TOO_LARGE ->
                        if (batch.size > 1) size = (batch.size / 2).coerceAtLeast(1) else drop(batch.single().item, tally)
                    // RATE_LIMITED waits for Retry-After; everything else (5xx, unknown codes) retries with backoff.
                    else -> return finish(DrainOutcome.RetryLater(result.retryAfterSec, tally), tally)
                }
            }
        }
        return finish(DrainOutcome.MoreWaiting(tally), tally)
    }

    private fun finish(outcome: DrainOutcome, tally: Tally): DrainOutcome {
        if (tally.uploaded > 0) counters.increment(CounterName.UPLOADED, tally.uploaded)
        if (tally.rejected > 0) counters.increment(CounterName.REJECTED, tally.rejected)
        return outcome
    }

    /** Opens bodies and builds the wire items. An item that cannot be read can never be sent: remove it so it cannot block the rest. */
    private fun prepare(items: List<QueuedItem>, tally: Tally): List<Prepared> = items.mapNotNull { item ->
        val wire = try {
            toWire(item)
        } catch (_: SecretBoxException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        if (wire == null) {
            drop(item, tally)
            null
        } else {
            Prepared(item, wire)
        }
    }

    private fun toWire(item: QueuedItem): IngestItem {
        val sc = TimestampFormat.format(item.scTimestampMillis)
        val received = TimestampFormat.format(item.receivedAtMillis)
        return when (item.kind) {
            ItemKind.DEPOSIT -> DepositItem(
                localId = item.localId,
                channel = item.channel.wire,
                sender = item.sender,
                packageName = item.packageName,
                simSlot = item.simSlot,
                subscriptionId = item.subscriptionId,
                scTimestamp = sc,
                receivedAt = received,
                body = String(bodyBox.open(checkNotNull(item.sealedBody), item.localId.toByteArray(Charsets.UTF_8)), Charsets.UTF_8),
                templateId = item.templateId,
                configVersion = item.configVersion,
                rescanRequestId = item.rescanRequestId,
            )
            ItemKind.BALANCE_MARKER -> BalanceMarkerItem(
                localId = item.localId,
                channel = item.channel.wire,
                sender = item.sender,
                packageName = item.packageName,
                simSlot = item.simSlot,
                subscriptionId = item.subscriptionId,
                scTimestamp = sc,
                receivedAt = received,
                bank = checkNotNull(item.bank),
                account = item.account,
                amountRial = checkNotNull(item.amountRial).toString(),
                balanceRial = checkNotNull(item.balanceRial).toString(),
                templateId = checkNotNull(item.templateId),
                configVersion = item.configVersion,
                rescanRequestId = item.rescanRequestId,
            )
        }
    }

    private fun encode(batch: List<Prepared>): ByteArray = Wire.encodeIngest(batch.map { it.wire })

    /** Returns false if the server answered but settled none of our items (no progress is possible now). */
    private fun applyResults(batch: List<Prepared>, result: ApiResult.Success, tally: Tally): Boolean {
        val parsed = try {
            Wire.json.decodeFromString(IngestResponse.serializer(), result.response.body.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        val statusById = parsed.results.associate { it.localId to it.status }
        val settled = ArrayList<String>()
        for (prepared in batch) {
            when (statusById[prepared.item.localId]) {
                "accepted", "duplicate" -> {
                    tally.uploaded++
                    settled += prepared.item.localId
                }
                "rejected" -> {
                    tally.rejected++
                    settled += prepared.item.localId
                }
                else -> Unit // no answer for this item (or one we do not understand): it stays queued
            }
        }
        if (settled.isNotEmpty()) queue.remove(settled)
        return settled.isNotEmpty()
    }

    private fun reject(item: QueuedItem, tally: Tally) {
        queue.remove(listOf(item.localId))
        tally.rejected++
    }

    private fun drop(item: QueuedItem, tally: Tally) {
        queue.remove(listOf(item.localId))
        tally.dropped++
    }

    /**
     * `error.fields` of a 422 names the items to drop by zod-style paths (`items.3.local_id`, the index is the position in
     * the batch we sent). A plain list of such strings is understood too.
     */
    private fun namedItems(fields: JsonElement?, batch: List<Prepared>): List<Prepared> {
        val keys = when (fields) {
            is JsonObject -> fields.keys
            is JsonArray -> fields.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            else -> emptyList()
        }
        val indexes = keys.mapNotNull { ITEM_PATH.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
        return indexes.mapNotNull { batch.getOrNull(it) }
    }

    private fun hasKey(fields: JsonElement?, key: String): Boolean =
        (fields as? JsonObject)?.containsKey(key) == true

    companion object {
        const val PATH = "/v1/ingest/android"
        const val MAX_REQUEST_BYTES = 256 * 1024
        private val ITEM_PATH = Regex("""items\.(\d+)(\..*)?""")
    }
}
