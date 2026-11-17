package com.varizpardaz.listener.core.rescan

import com.varizpardaz.listener.core.CompiledConfig
import com.varizpardaz.listener.core.TimestampFormat
import com.varizpardaz.listener.core.intake.SimRef
import com.varizpardaz.listener.core.intake.SmsIntake
import com.varizpardaz.listener.core.intake.SmsPart
import com.varizpardaz.listener.core.wire.RescanRequest

/** One row of the SMS inbox. [dateSentMillis] is the service-centre time (`date_sent`), the same clock a live message reports. */
class InboxSms(val address: String?, val body: String?, val dateSentMillis: Long, val subscriptionId: Int?) {
    override fun toString(): String = "InboxSms(bodyLength=${body?.length ?: 0})"
}

fun interface InboxReader {
    /** Messages with a service-centre time in `[fromMillis, toMillis]`, oldest first. Needs READ_SMS. */
    fun read(fromMillis: Long, toMillis: Long): List<InboxSms>
}

/**
 * Rescans already handed in and answered by the heartbeat. The server repeats a request every 5 minutes until the phone
 * reports it in `rescans_done`, so a request is processed once (by `request_id`), reported until the server stops listing it,
 * and only then forgotten.
 */
interface RescanLedger {
    fun doneEntries(): Map<String, Int>

    fun record(requestId: String, found: Int)

    fun forget(requestIds: Collection<String>)
}

/** Reads the inbox for a requested window and runs the normal intake on every message, tagged with the request id. */
class RescanProcessor(
    private val inbox: InboxReader,
    /** An intake whose counters are discarded: re-reading old messages must not inflate the dropped counts. */
    private val intake: SmsIntake,
    /** Subscription id of a SIM slot as far as the phone knows, to narrow the scan to that SIM. */
    private val subscriptionOfSlot: (Int) -> Int?,
    private val slotOfSubscription: (Int) -> Int?,
) {
    /** Returns how many items were queued, or null if the request cannot be answered (bad window). */
    fun process(request: RescanRequest, config: CompiledConfig): Int? {
        val from = TimestampFormat.parseToEpochMillis(request.from) ?: return null
        val to = TimestampFormat.parseToEpochMillis(request.to) ?: return null
        if (to < from) return null
        val wantedSubscription = request.simSlot?.let(subscriptionOfSlot)

        var queued = 0
        for (sms in inbox.read(from, to)) {
            if (wantedSubscription != null && sms.subscriptionId != null && sms.subscriptionId != wantedSubscription) continue
            val slot = sms.subscriptionId?.let(slotOfSubscription) ?: request.simSlot
            // One row at a time: the inbox already joined multipart messages, and two different messages that share a
            // sender and a second must not be merged.
            queued += intake.handle(
                listOf(SmsPart(sms.address, sms.body, sms.dateSentMillis)),
                SimRef(slot, sms.subscriptionId),
                config,
                rescanRequestId = request.requestId,
            ).queued
        }
        return queued
    }
}
