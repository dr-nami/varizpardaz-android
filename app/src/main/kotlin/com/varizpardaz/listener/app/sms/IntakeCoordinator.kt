package com.varizpardaz.listener.app.sms

import com.varizpardaz.listener.app.data.PairingRepository
import com.varizpardaz.listener.app.data.PairingState
import com.varizpardaz.listener.app.monitor.SimRegistry
import com.varizpardaz.listener.app.upload.DeviceState
import com.varizpardaz.listener.app.upload.UploadScheduler
import com.varizpardaz.listener.core.CompiledConfig
import com.varizpardaz.listener.core.configstore.ConfigRepository
import com.varizpardaz.listener.core.intake.IntakeOutcome
import com.varizpardaz.listener.core.intake.SimRef
import com.varizpardaz.listener.core.intake.SmsIntake
import com.varizpardaz.listener.core.intake.SmsPart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * Gates the message paths (SMS and bank-app notifications) on "paired and holding a verified config", runs [SmsIntake], and
 * asks for an upload when something was queued. A single thread keeps messages in arrival order.
 */
class IntakeCoordinator(
    private val pairing: PairingRepository,
    private val configs: ConfigRepository,
    private val intake: SmsIntake,
    private val scheduler: UploadScheduler,
    private val sims: SimRegistry,
    private val state: DeviceState,
    /** Called once a message arrives for a paired phone: the chance to restart the monitor service if the system stopped it. */
    private val keepAlive: () -> Unit = {},
) {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sms-intake") }

    /** Last text seen per notification slot, to ignore a bank app re-posting an unchanged notification. */
    private val recentNotifications = LinkedHashMap<String, Pair<Int, Long>>()

    fun submit(parts: List<SmsPart>, sim: SimRef, done: () -> Unit) {
        executor.execute {
            try {
                handle(parts, sim)
            } catch (_: Exception) {
                // Nothing useful can be done here, and the exception must not carry message text into a log.
            } finally {
                done()
            }
        }
    }

    /**
     * A notification from [packageName]. [readText] is only called after the package passed the config's
     * `notification_packages` gate, so no other app's notification text is ever read.
     */
    fun submitNotification(packageName: String, key: String, postTimeMillis: Long, readText: () -> String?) {
        executor.execute {
            try {
                val config = readyConfig() ?: return@execute
                if (!config.isNotificationPackage(packageName)) return@execute
                keepAlive()
                val body = readText() ?: return@execute
                if (isRepeat(key, body)) return@execute
                afterIntake(intake.handleNotification(packageName, body, postTimeMillis, config))
            } catch (_: Exception) {
                // see submit()
            }
        }
    }

    internal fun handle(parts: List<SmsPart>, sim: SimRef) {
        val config = readyConfig() ?: return
        keepAlive()
        sims.remember(sim.slot, sim.subscriptionId)
        afterIntake(intake.handle(parts, sim, config))
    }

    /** The config to classify with, or null when the phone is not paired or has no verified config yet (fail closed). */
    private fun readyConfig(): CompiledConfig? {
        // A cold process may still be reading its credentials.
        val pairingState = runBlocking { pairing.state.first { it !is PairingState.Loading } }
        val credentials = (pairingState as? PairingState.Paired)?.credentials ?: return null // unpaired: keep nothing at all
        return configs.current(credentials.serverPublicKey)
    }

    private fun afterIntake(outcome: IntakeOutcome) {
        if (outcome.queued > 0) {
            state.lastMessageAtMillis = System.currentTimeMillis()
            scheduler.requestUpload()
        }
    }

    private fun isRepeat(key: String, body: String): Boolean {
        val now = System.currentTimeMillis()
        val hash = body.hashCode()
        val previous = recentNotifications[key]
        recentNotifications[key] = hash to now
        if (recentNotifications.size > MAX_REMEMBERED) recentNotifications.remove(recentNotifications.keys.first())
        return previous != null && previous.first == hash && now - previous.second < REPEAT_WINDOW_MS
    }

    private companion object {
        const val MAX_REMEMBERED = 200
        const val REPEAT_WINDOW_MS = 2 * 60 * 1000L
    }
}
