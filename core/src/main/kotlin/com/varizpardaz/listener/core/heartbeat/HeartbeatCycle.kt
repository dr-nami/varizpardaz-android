package com.varizpardaz.listener.core.heartbeat

import com.varizpardaz.listener.core.api.SignedApi
import com.varizpardaz.listener.core.configstore.ConfigRefresh
import com.varizpardaz.listener.core.configstore.ConfigRepository
import com.varizpardaz.listener.core.rescan.RescanLedger
import com.varizpardaz.listener.core.rescan.RescanProcessor

class CycleResult(
    val heartbeat: HeartbeatResult,
    /** Items queued by rescans in this cycle; the caller should start an upload if above zero. */
    val rescanQueued: Int,
    /** Set when the heartbeat announced a newer config and it was fetched. */
    val configRefresh: ConfigRefresh?,
)

/**
 * One heartbeat tick and everything the answer asks for (contract section 9): fetch the config when the server's version
 * is newer than ours, and run the rescans it lists. A rescan is processed once per `request_id` and reported in the
 * *next* heartbeat; it stays in the ledger until the server stops listing it.
 */
class HeartbeatCycle(
    private val sender: HeartbeatSender,
    private val api: SignedApi,
    private val configs: ConfigRepository,
    private val ledger: RescanLedger,
    private val rescan: RescanProcessor,
) {
    fun run(
        snapshot: DeviceSnapshot,
        appVersionCode: Int,
        pinnedKey: String,
        lastMessageAtMillis: Long?,
        /** READ_SMS is granted; without it a rescan cannot be answered and is left for the server to repeat. */
        canReadInbox: Boolean,
    ): CycleResult {
        val localVersion = configs.current(pinnedKey)?.version ?: 0L
        val result = sender.send(snapshot, appVersionCode, localVersion, lastMessageAtMillis)
        if (result !is HeartbeatResult.Ok) return CycleResult(result, 0, null)

        val response = result.response
        val listed = response.rescan.map { it.requestId }.toSet()
        // Reported and no longer listed: the server has it, forget it.
        ledger.forget(ledger.doneEntries().keys - listed)

        val refresh = if (response.configVersion != localVersion && response.configVersion > localVersion) {
            configs.refresh(api, pinnedKey)
        } else {
            null
        }

        var queued = 0
        val config = configs.current(pinnedKey)
        if (config != null && canReadInbox) {
            val done = ledger.doneEntries().keys
            for (request in response.rescan) {
                if (request.requestId in done) continue
                // A window that cannot be read can never be satisfied: answer with zero so the server stops asking.
                val found = rescan.process(request, config)
                ledger.record(request.requestId, found ?: 0)
                queued += found ?: 0
            }
        }
        return CycleResult(result, queued, refresh)
    }
}
