package com.varizpardaz.listener.core.intake

import com.varizpardaz.listener.core.Channel
import com.varizpardaz.listener.core.Classification
import com.varizpardaz.listener.core.Classifier
import com.varizpardaz.listener.core.CompiledConfig
import com.varizpardaz.listener.core.IncomingMessage
import com.varizpardaz.listener.core.pairing.SecretBox
import com.varizpardaz.listener.core.queue.CounterName
import com.varizpardaz.listener.core.queue.Counters
import com.varizpardaz.listener.core.queue.ItemKind
import com.varizpardaz.listener.core.queue.MessageQueue
import com.varizpardaz.listener.core.queue.QueuedItem
import java.util.UUID

/** One SMS segment as the platform delivers it. */
class SmsPart(val address: String?, val body: String?, val timestampMillis: Long) {
    override fun toString(): String = "SmsPart(bodyLength=${body?.length ?: 0})"
}

/** Segments joined into the message the sender wrote. */
class AssembledSms(val sender: String, val body: String, val scTimestampMillis: Long) {
    override fun toString(): String = "AssembledSms(bodyLength=${body.length})"
}

/** Where the message arrived. Both are null when the platform did not say. */
class SimRef(val slot: Int?, val subscriptionId: Int?)

object SmsAssembler {
    /**
     * Multipart SMS arrive as several segments in one broadcast; contract section 6 joins segments with the same
     * sender and the same service-centre timestamp, in arrival order, before anything else happens.
     */
    fun assemble(parts: List<SmsPart>): List<AssembledSms> {
        data class Key(val sender: String, val timestamp: Long)

        val groups = LinkedHashMap<Key, StringBuilder>()
        for (part in parts) {
            val body = part.body ?: continue
            groups.getOrPut(Key(part.address.orEmpty(), part.timestampMillis)) { StringBuilder() }.append(body)
        }
        return groups.map { (key, body) -> AssembledSms(key.sender, body.toString(), key.timestamp) }
    }
}

class IntakeOutcome(val queued: Int, val dropped: Int, val failed: Int) {
    override fun toString(): String = "IntakeOutcome(queued=$queued, dropped=$dropped, failed=$failed)"
}

/**
 * The SMS path, contract section 6, from segments to the durable queue. Everything that is not a deposit text or a
 * balance marker ends here as a counter increment and nothing else: no text, no hash, no timestamp.
 */
class SmsIntake(
    private val queue: MessageQueue,
    private val counters: Counters,
    /** Seals deposit bodies for storage (a Keystore key on the phone). */
    private val bodyBox: SecretBox,
    private val newLocalId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** [rescanRequestId] marks items found by an inbox rescan, so the server can tell them from live messages. */
    fun handle(parts: List<SmsPart>, sim: SimRef, config: CompiledConfig, rescanRequestId: String? = null): IntakeOutcome {
        val total = Tally3()
        for (sms in SmsAssembler.assemble(parts)) {
            total.add(process(IncomingMessage(Channel.SMS, sms.sender, null, sms.body), sms.scTimestampMillis, sim, config, rescanRequestId))
        }
        return total.toOutcome()
    }

    /**
     * A bank-app notification, contract section 6 with the package gate instead of the sender gate. [postTimeMillis] is
     * `StatusBarNotification.postTime`. The caller must already have checked the package (so that no other app's
     * notification text is ever read); this still fails closed through the same classifier.
     */
    fun handleNotification(packageName: String, body: String, postTimeMillis: Long, config: CompiledConfig): IntakeOutcome =
        process(IncomingMessage(Channel.NOTIFICATION, null, packageName, body), postTimeMillis, SimRef(null, null), config, null).let {
            Tally3().apply { add(it) }.toOutcome()
        }

    private class Tally3(var queued: Int = 0, var dropped: Int = 0, var failed: Int = 0) {
        fun add(other: Tally3) {
            queued += other.queued
            dropped += other.dropped
            failed += other.failed
        }

        fun toOutcome() = IntakeOutcome(queued, dropped, failed)
    }

    private fun process(message: IncomingMessage, scTimestampMillis: Long, sim: SimRef, config: CompiledConfig, rescanRequestId: String?): Tally3 {
        val result = Tally3()
        val sc = scTimestampMillis.takeIf { it > 0 } ?: nowMillis()
        when (val classification = Classifier.classify(message, config)) {
            is Classification.Dropped -> {
                counters.increment(CounterName.of(classification.reason))
                result.dropped++
            }
            is Classification.Deposit -> {
                val localId = newLocalId()
                val ok = enqueue {
                    QueuedItem(
                        localId = localId,
                        kind = ItemKind.DEPOSIT,
                        channel = message.channel,
                        sender = message.sender,
                        packageName = message.packageName,
                        simSlot = sim.slot,
                        subscriptionId = sim.subscriptionId,
                        scTimestampMillis = sc,
                        receivedAtMillis = nowMillis(),
                        // Bound to the row: a body cannot be moved to another item without failing to open.
                        sealedBody = bodyBox.seal(message.body.toByteArray(Charsets.UTF_8), localId.toByteArray(Charsets.UTF_8)),
                        templateId = classification.templateId,
                        bank = classification.bank,
                        amountRial = null,
                        balanceRial = null,
                        configVersion = config.version,
                        rescanRequestId = rescanRequestId,
                    )
                }
                if (ok) result.queued++ else result.failed++
            }
            is Classification.BalanceMarker -> {
                val ok = enqueue {
                    QueuedItem(
                        localId = newLocalId(),
                        kind = ItemKind.BALANCE_MARKER,
                        channel = message.channel,
                        sender = message.sender,
                        packageName = message.packageName,
                        simSlot = sim.slot,
                        subscriptionId = sim.subscriptionId,
                        scTimestampMillis = sc,
                        receivedAtMillis = nowMillis(),
                        sealedBody = null, // the withdrawal text is never kept
                        templateId = classification.templateId,
                        bank = classification.bank,
                        amountRial = classification.amountRial,
                        balanceRial = classification.balanceRial,
                        configVersion = config.version,
                        rescanRequestId = rescanRequestId,
                        account = classification.account,
                    )
                }
                if (ok) result.queued++ else result.failed++
            }
        }
        return result
    }

    // A storage failure must not take the broadcast thread down, and the exception (which could mention
    // a value) is not kept. A missed deposit is caught later by the server's balance-chain gap check and a rescan.
    private fun enqueue(build: () -> QueuedItem): Boolean =
        try {
            queue.enqueue(build())
            true
        } catch (_: Exception) {
            false
        }
}
