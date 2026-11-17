package com.varizpardaz.listener.app.monitor

import android.content.Context
import com.varizpardaz.listener.app.BuildConfig
import com.varizpardaz.listener.app.data.PairingRepository
import com.varizpardaz.listener.app.data.PairingState
import com.varizpardaz.listener.app.data.RepairReason
import com.varizpardaz.listener.app.upload.DeviceState
import com.varizpardaz.listener.app.upload.UploadScheduler
import com.varizpardaz.listener.core.api.ClockOffset
import com.varizpardaz.listener.core.api.SignedApi
import com.varizpardaz.listener.core.configstore.ConfigRepository
import com.varizpardaz.listener.core.heartbeat.CycleResult
import com.varizpardaz.listener.core.heartbeat.HeartbeatCycle
import com.varizpardaz.listener.core.heartbeat.HeartbeatResult
import com.varizpardaz.listener.core.heartbeat.HeartbeatSender
import com.varizpardaz.listener.core.http.HttpTransport
import com.varizpardaz.listener.core.queue.Counters
import com.varizpardaz.listener.core.queue.MessageQueue
import com.varizpardaz.listener.core.rescan.RescanLedger
import com.varizpardaz.listener.core.rescan.RescanProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What the status screen shows about the link to the server. */
class MonitorStatus(val lastSuccessMillis: Long?)

/**
 * The heartbeat loop (contract section 9): every `heartbeat_interval_sec` report the phone's state, fetch a newer config,
 * run the rescans the server asks for, and make sure queued items are being uploaded. Runs inside [MonitorService] and ends
 * when the phone is no longer paired.
 */
class MonitorEngine(
    private val context: Context,
    private val pairing: PairingRepository,
    private val configs: ConfigRepository,
    private val transport: HttpTransport,
    private val clock: ClockOffset,
    private val state: DeviceState,
    private val queue: MessageQueue,
    private val counters: Counters,
    private val ledger: RescanLedger,
    private val rescan: RescanProcessor,
    private val snapshots: DeviceSnapshotProvider,
    private val scheduler: UploadScheduler,
) {
    private val _status = MutableStateFlow(MonitorStatus(null))
    val status: StateFlow<MonitorStatus> = _status.asStateFlow()

    suspend fun run() {
        while (true) {
            val credentials = (pairing.state.first { it !is PairingState.Loading } as? PairingState.Paired)?.credentials ?: return
            val api = SignedApi(transport, credentials, clock, onClockOffsetChanged = { state.clockOffsetSeconds = it })
            val cycle = HeartbeatCycle(HeartbeatSender(api, counters, queue, ledger), api, configs, ledger, rescan)

            val result: CycleResult? = try {
                withContext(Dispatchers.IO) {
                    cycle.run(
                        snapshot = snapshots.snapshot(),
                        appVersionCode = BuildConfig.VERSION_CODE,
                        pinnedKey = credentials.serverPublicKey,
                        lastMessageAtMillis = state.lastMessageAtMillis.takeIf { it > 0 },
                        canReadInbox = PermissionState.readSms(context),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // never let one bad tick (and never message text) end the loop
            }

            var waitSeconds = intervalSeconds(credentials.serverPublicKey, credentials.heartbeatIntervalSec)
            when (val heartbeat = result?.heartbeat) {
                is HeartbeatResult.Ok -> _status.value = MonitorStatus(System.currentTimeMillis())
                HeartbeatResult.Revoked -> return pairing.markNeedsRepair(RepairReason.REVOKED)
                HeartbeatResult.RepairNeeded -> return pairing.markNeedsRepair(RepairReason.SECRET_REJECTED)
                is HeartbeatResult.RetryLater -> waitSeconds = (heartbeat.afterSec ?: waitSeconds).coerceIn(MIN_SECONDS, MAX_SECONDS)
                null -> Unit
            }

            // A safety net for a missed trigger, but never while an upload retry is deliberately waiting out its backoff.
            val backlog = withContext(Dispatchers.IO) { queue.size() > 0 }
            if ((result?.rescanQueued ?: 0) > 0 || (backlog && state.uploadFailures == 0)) scheduler.requestUpload()

            delay(waitSeconds * 1000L)
        }
    }

    private fun intervalSeconds(pinnedKey: String, fromPairing: Int): Int =
        (configs.current(pinnedKey)?.raw?.heartbeatIntervalSec ?: fromPairing).coerceIn(MIN_SECONDS, MAX_SECONDS)

    private companion object {
        const val MIN_SECONDS = 10
        const val MAX_SECONDS = 300
    }
}
