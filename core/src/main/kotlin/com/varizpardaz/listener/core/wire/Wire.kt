package com.varizpardaz.listener.core.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Request and response bodies of `contracts/device-api.md` (sections 3, 7, 9, 10).
 * Property names follow the contract exactly; keep it that way.
 */
object Wire {
    /** Emits nulls and defaults explicitly, as in the contract examples. Ignores unknown response keys. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeIngest(items: List<IngestItem>): ByteArray {
        val array = JsonArray(items.map { toJson(it) })
        return json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("items" to array)))
            .toByteArray(Charsets.UTF_8)
    }

    private fun toJson(item: IngestItem): JsonElement = when (item) {
        is DepositItem -> json.encodeToJsonElement(DepositItem.serializer(), item)
        is BalanceMarkerItem -> json.encodeToJsonElement(BalanceMarkerItem.serializer(), item)
    }
}

// ---- Pairing (section 3) ----------------------------------------------------------------------

@Serializable
data class PairRequest(
    val code: String,
    val platform: String = "android",
    @SerialName("device_name") val deviceName: String,
    @SerialName("os_version") val osVersion: String,
    @SerialName("app_version_code") val appVersionCode: Int,
)

@Serializable
data class PairResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_secret") val deviceSecret: String,
    @SerialName("server_public_key") val serverPublicKey: String,
    @SerialName("heartbeat_interval_sec") val heartbeatIntervalSec: Int = 30,
) {
    // Never let the secret reach a log line.
    override fun toString(): String = "PairResponse(deviceId=$deviceId)"
}

// ---- Ingest (section 7) -----------------------------------------------------------------------

sealed interface IngestItem {
    val localId: String
}

/** A deposit text. [body] is the raw text; the server re-normalises. [templateId] is null for the keyword fallback. */
@Serializable
data class DepositItem(
    @SerialName("local_id") override val localId: String,
    val kind: String = "deposit",
    val channel: String,
    val sender: String?,
    @SerialName("package") val packageName: String?,
    @SerialName("sim_slot") val simSlot: Int?,
    @SerialName("subscription_id") val subscriptionId: Int?,
    @SerialName("sc_timestamp") val scTimestamp: String,
    @SerialName("received_at") val receivedAt: String,
    val body: String,
    @SerialName("template_id") val templateId: String?,
    @SerialName("config_version") val configVersion: Long,
    @SerialName("rescan_request_id") val rescanRequestId: String?,
) : IngestItem {
    override fun toString(): String = "DepositItem(localId=$localId)" // never the body
}

/** What replaces a withdrawal text: numbers only. */
@Serializable
data class BalanceMarkerItem(
    @SerialName("local_id") override val localId: String,
    val kind: String = "balance_marker",
    val channel: String,
    val sender: String?,
    /** Notification markers name their source here, exactly as a deposit does (CCR-D1). Null for SMS. */
    @SerialName("package") val packageName: String? = null,
    @SerialName("sim_slot") val simSlot: Int?,
    @SerialName("subscription_id") val subscriptionId: Int?,
    @SerialName("sc_timestamp") val scTimestamp: String,
    @SerialName("received_at") val receivedAt: String,
    val bank: String,
    /** The withdrawal template's `account` group (CCR-1): lets the server tell two cards of one bank on one SIM apart. Null if the template has none. */
    val account: String? = null,
    @SerialName("amount_rial") val amountRial: String,
    @SerialName("balance_rial") val balanceRial: String,
    @SerialName("template_id") val templateId: String,
    @SerialName("config_version") val configVersion: Long,
    @SerialName("rescan_request_id") val rescanRequestId: String?,
) : IngestItem

@Serializable
data class IngestResponse(val results: List<ItemResult>)

/** [status] is `accepted`, `duplicate` or `rejected`; [reason] accompanies `rejected`. */
@Serializable
data class ItemResult(
    @SerialName("local_id") val localId: String,
    val status: String,
    val reason: String? = null,
)

// ---- Heartbeat (section 9) --------------------------------------------------------------------

@Serializable
data class HeartbeatRequest(
    @SerialName("sent_at") val sentAt: String,
    @SerialName("app_version_code") val appVersionCode: Int,
    @SerialName("battery_pct") val batteryPct: Int?,
    val charging: Boolean?,
    val network: String,
    val permissions: Permissions,
    @SerialName("queue_depth") val queueDepth: Int,
    @SerialName("last_message_at") val lastMessageAt: String?,
    @SerialName("counters_since_last") val countersSinceLast: Counters,
    @SerialName("config_version") val configVersion: Long,
    val sims: List<SimInfo>,
    @SerialName("rescans_done") val rescansDone: List<RescanDone> = emptyList(),
)

@Serializable
data class Permissions(
    @SerialName("receive_sms") val receiveSms: Boolean,
    @SerialName("read_sms") val readSms: Boolean,
    @SerialName("notification_listener") val notificationListener: Boolean,
    @SerialName("post_notifications") val postNotifications: Boolean,
    @SerialName("ignore_battery_optimizations") val ignoreBatteryOptimizations: Boolean,
)

@Serializable
data class Counters(
    val uploaded: Int = 0,
    @SerialName("dropped_sender") val droppedSender: Int = 0,
    @SerialName("dropped_otp") val droppedOtp: Int = 0,
    @SerialName("dropped_unclassified") val droppedUnclassified: Int = 0,
)

@Serializable
data class SimInfo(
    val slot: Int,
    @SerialName("subscription_id") val subscriptionId: Int,
    val carrier: String? = null,
)

@Serializable
data class RescanDone(@SerialName("request_id") val requestId: String, val found: Int)

@Serializable
data class HeartbeatResponse(
    @SerialName("server_time") val serverTime: String,
    @SerialName("config_version") val configVersion: Long,
    val rescan: List<RescanRequest> = emptyList(),
)

@Serializable
data class RescanRequest(
    @SerialName("request_id") val requestId: String,
    val from: String,
    val to: String,
    @SerialName("sim_slot") val simSlot: Int? = null,
)

// ---- Errors (section 10) ----------------------------------------------------------------------

@Serializable
data class ErrorEnvelope(val ok: Boolean = false, val error: ErrorBody)

@Serializable
data class ErrorBody(
    val code: String,
    /** Persian, safe to show to the user. */
    val message: String = "",
    @SerialName("server_time") val serverTime: String? = null,
    /** Shape is not pinned down by the contract (names of invalid items or fields); kept raw. */
    val fields: JsonElement? = null,
)

/** Error codes of contract section 10. [UNKNOWN] covers anything a newer server may add. */
enum class ErrorCode(val wire: String) {
    BAD_SIGNATURE("BAD_SIGNATURE"),
    CLOCK_SKEW("CLOCK_SKEW"),
    NONCE_REUSED("NONCE_REUSED"),
    DEVICE_REVOKED("DEVICE_REVOKED"),
    PAIRING_CODE_INVALID("PAIRING_CODE_INVALID"),
    VALIDATION_ERROR("VALIDATION_ERROR"),
    PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE"),
    RATE_LIMITED("RATE_LIMITED"),
    UNKNOWN("");

    companion object {
        fun of(code: String): ErrorCode = entries.firstOrNull { it.wire == code && it != UNKNOWN } ?: UNKNOWN
    }
}
