package com.varizpardaz.listener.core

enum class Channel(val wire: String) {
    SMS("sms"),
    NOTIFICATION("notification"),
}

/**
 * One incoming SMS or bank-app notification. [toString] never prints the body: message texts must not
 * reach logs, crash reports or exception messages.
 */
class IncomingMessage(
    val channel: Channel,
    /** SMS originating address. Null for notifications. */
    val sender: String?,
    /** Posting package. Null for SMS. */
    val packageName: String?,
    val body: String,
) {
    override fun toString(): String = "IncomingMessage(channel=$channel, bodyLength=${body.length})"
}

enum class DropReason(val counter: String) {
    /** Not from a configured bank sender or package. */
    SENDER("dropped_sender"),

    /** Matched an OTP pattern. Nothing about the message is stored. */
    OTP("dropped_otp"),

    /** From a bank, but neither a deposit, a usable withdrawal, nor a deposit keyword. */
    UNCLASSIFIED("dropped_unclassified"),
}

/** Fields the phone could read out of a text. Only [BalanceMarker] values are ever uploaded as parsed data. */
class ParsedFields(
    val amountRial: Long?,
    val balanceRial: Long?,
    val account: String?,
    val originLast4: String?,
)

/** Result of contract section 6. */
sealed interface Classification {
    class Dropped(val reason: DropReason) : Classification

    /** Upload the raw body. [templateId] is null for the keyword fallback. */
    class Deposit(val bank: String, val templateId: String?, val fields: ParsedFields?) : Classification

    /** Upload only these numbers; the withdrawal text itself is never stored or sent. */
    class BalanceMarker(
        val bank: String,
        val templateId: String,
        val amountRial: Long,
        val balanceRial: Long,
        val account: String?,
    ) : Classification
}

object Classifier {
    fun classify(message: IncomingMessage, config: CompiledConfig): Classification {
        // 1. Sender gate. Nothing is stored for a message that fails it.
        val candidates = when (message.channel) {
            Channel.SMS -> {
                val sender = Normalizer.sender(message.sender.orEmpty())
                config.banks.filter { sender.isNotEmpty() && sender in it.senders }
            }
            Channel.NOTIFICATION -> {
                val pkg = message.packageName.orEmpty()
                config.banks.filter { pkg.isNotEmpty() && pkg in it.notificationPackages }
            }
        }
        if (candidates.isEmpty()) return Classification.Dropped(DropReason.SENDER)

        // 2. OTP gate, on the normalised text.
        val text = Normalizer.normalize(message.body)
        if (config.otpPatterns.any { it.matches(text) }) return Classification.Dropped(DropReason.OTP)

        // 3. Deposit.
        for (bank in candidates) {
            for (template in bank.deposit) {
                val match = template.pattern.find(text) ?: continue
                return Classification.Deposit(bank.bank, template.id, fieldsOf(match, template.unit))
            }
        }

        // 4. Withdrawal: only a balance marker may leave the phone. A withdrawal pattern that matches but
        // does not yield a usable amount and balance is dropped here, never handed to the keyword
        // fallback, so a withdrawal text cannot be uploaded as a "deposit".
        for (bank in candidates) {
            for (template in bank.withdrawal) {
                val match = template.pattern.find(text) ?: continue
                val fields = fieldsOf(match, template.unit)
                val amount = fields.amountRial
                val balance = fields.balanceRial
                return if (amount != null && balance != null) {
                    Classification.BalanceMarker(bank.bank, template.id, amount, balance, fields.account)
                } else {
                    Classification.Dropped(DropReason.UNCLASSIFIED)
                }
            }
        }

        // 5. Keyword fallback lets the server learn new formats.
        if (config.depositKeywords.any { text.contains(it) }) {
            return Classification.Deposit(candidates.first().bank, null, null)
        }

        // 6.
        return Classification.Dropped(DropReason.UNCLASSIFIED)
    }

    private fun fieldsOf(match: PatternMatch, unit: AmountUnit) = ParsedFields(
        amountRial = parseRial(match.group("amount"), unit),
        balanceRial = parseRial(match.group("balance"), unit),
        account = match.group("account"),
        originLast4 = match.group("origin_last4"),
    )

    /** Digits and thousands separators only; anything else (a decimal part, text) yields null. */
    internal fun parseRial(raw: String?, unit: AmountUnit): Long? {
        if (raw == null) return null
        val digits = raw.replace(",", "")
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        val value = digits.toLongOrNull() ?: return null
        return try {
            Math.multiplyExact(value, unit.multiplier)
        } catch (_: ArithmeticException) {
            null
        }
    }
}
