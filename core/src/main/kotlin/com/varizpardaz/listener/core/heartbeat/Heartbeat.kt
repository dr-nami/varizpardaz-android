package com.varizpardaz.listener.core.heartbeat

import com.varizpardaz.listener.core.TimestampFormat
import com.varizpardaz.listener.core.api.ApiResult
import com.varizpardaz.listener.core.api.SignedApi
import com.varizpardaz.listener.core.queue.CounterName
import com.varizpardaz.listener.core.queue.Counters
import com.varizpardaz.listener.core.queue.MessageQueue
import com.varizpardaz.listener.core.rescan.RescanLedger
import com.varizpardaz.listener.core.wire.Counters as WireCounters
import com.varizpardaz.listener.core.wire.HeartbeatRequest
import com.varizpardaz.listener.core.wire.HeartbeatResponse
import com.varizpardaz.listener.core.wire.Permissions
import com.varizpardaz.listener.core.wire.RescanDone
import com.varizpardaz.listener.core.wire.SimInfo
import com.varizpardaz.listener.core.wire.Wire
import kotlinx.serialization.SerializationException

/** Facts about the phone right now, read by the app. */
class DeviceSnapshot(
    val batteryPct: Int?,
    val charging: Boolean?,
    /** `wifi`, `cellular`, `ethernet`, `other` or `none`. */
    val network: String,
    val permissions: Permissions,
    val sims: List<SimInfo>,
)

sealed interface HeartbeatResult {
    class Ok(val response: HeartbeatResponse) : HeartbeatResult

    /** Nothing was lost (the counters are back where they were); try again on the next tick, or after [afterSec]. */
    class RetryLater(val afterSec: Int?) : HeartbeatResult

    data object Revoked : HeartbeatResult

    data object RepairNeeded : HeartbeatResult
}

/** `POST /v1/devices/heartbeat` (contract section 9). */
class HeartbeatSender(
    private val api: SignedApi,
    private val counters: Counters,
    private val queue: MessageQueue,
    private val ledger: RescanLedger,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun send(snapshot: DeviceSnapshot, appVersionCode: Int, configVersion: Long, lastMessageAtMillis: Long?): HeartbeatResult {
        // "since last" must not lose counts when the request fails, so the numbers are put back on any failure.
        val taken = counters.takeAll()
        fun restore() = counters.putBack(taken)

        val request = HeartbeatRequest(
            sentAt = TimestampFormat.format(nowMillis()),
            appVersionCode = appVersionCode,
            batteryPct = snapshot.batteryPct,
            charging = snapshot.charging,
            network = snapshot.network,
            permissions = snapshot.permissions,
            queueDepth = queue.size(),
            lastMessageAt = lastMessageAtMillis?.let(TimestampFormat::format),
            countersSinceLast = WireCounters(
                uploaded = (taken[CounterName.UPLOADED] ?: 0L).toInt(),
                droppedSender = (taken[CounterName.DROPPED_SENDER] ?: 0L).toInt(),
                droppedOtp = (taken[CounterName.DROPPED_OTP] ?: 0L).toInt(),
                droppedUnclassified = (taken[CounterName.DROPPED_UNCLASSIFIED] ?: 0L).toInt(),
            ),
            configVersion = configVersion,
            sims = snapshot.sims,
            rescansDone = ledger.doneEntries().map { (id, found) -> RescanDone(id, found) },
        )
        val body = Wire.json.encodeToString(HeartbeatRequest.serializer(), request).toByteArray(Charsets.UTF_8)

        return when (val result = api.post(PATH, body)) {
            is ApiResult.Success -> {
                val response = try {
                    Wire.json.decodeFromString(HeartbeatResponse.serializer(), result.response.body.toString(Charsets.UTF_8))
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (response == null) {
                    restore()
                    HeartbeatResult.RetryLater(null)
                } else {
                    HeartbeatResult.Ok(response)
                }
            }
            ApiResult.Revoked -> HeartbeatResult.Revoked
            ApiResult.RepairNeeded -> HeartbeatResult.RepairNeeded
            is ApiResult.Unreachable -> {
                restore()
                HeartbeatResult.RetryLater(null)
            }
            is ApiResult.Failure -> {
                restore()
                HeartbeatResult.RetryLater(result.retryAfterSec)
            }
        }
    }

    companion object {
        const val PATH = "/v1/devices/heartbeat"
    }
}
