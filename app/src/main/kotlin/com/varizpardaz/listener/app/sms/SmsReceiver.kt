package com.varizpardaz.listener.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.varizpardaz.listener.app.ListenerApp
import com.varizpardaz.listener.core.intake.SimRef
import com.varizpardaz.listener.core.intake.SmsPart

/**
 * Receives incoming SMS. The receiver itself only unpacks the broadcast; classification and storage run on the
 * intake thread while the broadcast is held open with `goAsync()`, because the database cannot be touched on the main
 * thread and the process must not be killed in between. Nothing here does network I/O and nothing logs a message.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parts = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
                ?.mapNotNull { sms -> sms?.let { SmsPart(it.originatingAddress, it.messageBody, it.timestampMillis) } }
        } catch (_: RuntimeException) {
            null // a malformed broadcast; there is nothing we can classify
        }
        if (parts.isNullOrEmpty()) return

        val pending = goAsync()
        val container = (context.applicationContext as ListenerApp).container
        container.intake.submit(parts, simOf(intent)) { pending.finish() }
    }

    /**
     * The platform puts the SIM into the broadcast under several names depending on the Android version and vendor;
     * values below zero mean "not set".
     */
    private fun simOf(intent: Intent) = SimRef(
        slot = intent.firstNonNegative("android.telephony.extra.SLOT_INDEX", "slot", "phone"),
        subscriptionId = intent.firstNonNegative("android.telephony.extra.SUBSCRIPTION_INDEX", "subscription"),
    )

    private fun Intent.firstNonNegative(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> getIntExtra(key, -1).takeIf { it >= 0 } }
}
