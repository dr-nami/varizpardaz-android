package com.varizpardaz.listener.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Wire model of the signed config, contract section 5. Unknown keys are ignored (forward compatible). */
@Serializable
data class DeviceConfig(
    val version: Long,
    @SerialName("issued_at") val issuedAt: String,
    @SerialName("heartbeat_interval_sec") val heartbeatIntervalSec: Int = 30,
    val upload: UploadSpec = UploadSpec(),
    @SerialName("otp_patterns") val otpPatterns: List<PatternSpec> = emptyList(),
    @SerialName("deposit_keywords") val depositKeywords: List<String> = emptyList(),
    val banks: List<BankSpec> = emptyList(),
    val cards: List<CardInfo> = emptyList(),
    @SerialName("app_update") val appUpdate: AppUpdate? = null,
    /** For display in the app only (CCR-D2); never used for a decision. Absent on servers that do not send it. */
    @SerialName("merchant_name") val merchantName: String? = null,
)

@Serializable
data class UploadSpec(@SerialName("max_batch") val maxBatch: Int = 50)

@Serializable
data class PatternSpec(val pattern: String, val flags: String = "")

@Serializable
data class TemplateSpec(
    val id: String,
    val pattern: String,
    val flags: String = "",
    /** `"rial"` or `"toman"`; the device converts to rial before upload. */
    val unit: String = "rial",
    /** `verified` or `unverified`. Informational on the device. */
    val status: String = "unverified",
)

@Serializable
data class BankSpec(
    val bank: String,
    val senders: List<String> = emptyList(),
    @SerialName("notification_packages") val notificationPackages: List<String> = emptyList(),
    val deposit: List<TemplateSpec> = emptyList(),
    val withdrawal: List<TemplateSpec> = emptyList(),
)

/** For display in the app only; the server does the card binding. */
@Serializable
data class CardInfo(
    @SerialName("card_id") val cardId: String,
    val bank: String,
    val label: String,
    @SerialName("sim_slot") val simSlot: Int? = null,
)

@Serializable
data class AppUpdate(
    @SerialName("latest_version_code") val latestVersionCode: Int,
    @SerialName("min_version_code") val minVersionCode: Int,
    val url: String,
    val sha256: String,
)

/** A template whose pattern compiled, with its unit decoded. */
class CompiledTemplate internal constructor(val id: String, val pattern: CompiledPattern, val unit: AmountUnit)

enum class AmountUnit(val multiplier: Long) { RIAL(1), TOMAN(10) }

class CompiledBank internal constructor(
    val bank: String,
    internal val senders: Set<String>,
    internal val notificationPackages: Set<String>,
    val deposit: List<CompiledTemplate>,
    val withdrawal: List<CompiledTemplate>,
)

/**
 * A config ready for classification. [skippedTemplates] lists the ids of bank templates that did not
 * compile (they are ignored; the rest of the config still works).
 */
class CompiledConfig internal constructor(
    val raw: DeviceConfig,
    internal val otpPatterns: List<CompiledPattern>,
    internal val depositKeywords: List<String>,
    internal val banks: List<CompiledBank>,
    val skippedTemplates: List<String>,
) {
    val version: Long get() = raw.version

    /**
     * Whether a notification from [packageName] may be read at all (`banks[].notification_packages`). Check this before
     * touching a notification's text, so other apps' notifications are never looked at.
     */
    fun isNotificationPackage(packageName: String): Boolean = banks.any { packageName in it.notificationPackages }
}

sealed interface ConfigResult {
    /** [rawBody] is the exact signed byte sequence, to be persisted next to its signature. */
    class Accepted(val config: CompiledConfig, val rawBody: ByteArray) : ConfigResult

    class Rejected(val reason: Reason) : ConfigResult

    enum class Reason { BAD_SIGNATURE, MALFORMED, INVALID_OTP_PATTERN }
}

object ConfigLoader {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verifies the Ed25519 signature over the raw body with the pinned key, then parses and compiles.
     * Anything wrong returns [ConfigResult.Rejected] and the caller keeps its last good config.
     * An OTP pattern that does not compile rejects the whole config: a gate that silently shrinks would
     * let one-time passwords through.
     */
    fun verifyAndLoad(rawBody: ByteArray, signatureB64Url: String, pinnedPublicKeyB64Url: String): ConfigResult {
        if (!Ed25519Verifier.verify(rawBody, signatureB64Url, pinnedPublicKeyB64Url)) {
            return ConfigResult.Rejected(ConfigResult.Reason.BAD_SIGNATURE)
        }
        return load(rawBody)
    }

    /** Parse and compile without a signature check. Only for bytes that were verified earlier. */
    fun load(rawBody: ByteArray): ConfigResult {
        val raw = try {
            json.decodeFromString(DeviceConfig.serializer(), rawBody.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return ConfigResult.Rejected(ConfigResult.Reason.MALFORMED)
        } catch (_: IllegalArgumentException) {
            return ConfigResult.Rejected(ConfigResult.Reason.MALFORMED)
        }
        val compiled = try {
            compile(raw)
        } catch (_: InvalidPatternException) {
            return ConfigResult.Rejected(ConfigResult.Reason.INVALID_OTP_PATTERN)
        }
        return ConfigResult.Accepted(compiled, rawBody)
    }

    internal fun compile(raw: DeviceConfig): CompiledConfig {
        val otp = raw.otpPatterns.map { RegexDialect.compile(it.pattern, it.flags) }
        val skipped = ArrayList<String>()
        val banks = raw.banks.map { bank ->
            fun templates(specs: List<TemplateSpec>): List<CompiledTemplate> = specs.mapNotNull { spec ->
                val unit = when (spec.unit) {
                    "rial" -> AmountUnit.RIAL
                    "toman" -> AmountUnit.TOMAN
                    else -> null
                }
                val pattern = try {
                    RegexDialect.compile(spec.pattern, spec.flags)
                } catch (_: InvalidPatternException) {
                    null
                }
                if (unit == null || pattern == null) {
                    skipped += spec.id
                    null
                } else {
                    CompiledTemplate(spec.id, pattern, unit)
                }
            }
            CompiledBank(
                bank = bank.bank,
                senders = bank.senders.map(Normalizer::sender).toSet(),
                notificationPackages = bank.notificationPackages.toSet(),
                deposit = templates(bank.deposit),
                withdrawal = templates(bank.withdrawal),
            )
        }
        return CompiledConfig(
            raw = raw,
            otpPatterns = otp,
            depositKeywords = raw.depositKeywords.map(Normalizer::normalize).filter { it.isNotEmpty() },
            banks = banks,
            skippedTemplates = skipped,
        )
    }
}

/**
 * Ed25519 (RFC 8032) verification with BouncyCastle's lightweight API. Does not go through the
 * platform JCA, which only offers Ed25519 from Android 13.
 */
object Ed25519Verifier {
    fun verify(message: ByteArray, signatureB64Url: String, publicKeyB64Url: String): Boolean {
        val signature = Base64Url.decodeOrNull(signatureB64Url) ?: return false
        val publicKey = Base64Url.decodeOrNull(publicKeyB64Url) ?: return false
        if (signature.size != 64 || publicKey.size != 32) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: RuntimeException) {
            false
        }
    }
}
