package com.varizpardaz.listener.app.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.varizpardaz.listener.app.ListenerApp
import com.varizpardaz.listener.app.R
import com.varizpardaz.listener.app.data.PairingRepository
import com.varizpardaz.listener.app.data.PairingState
import com.varizpardaz.listener.app.data.RepairReason
import com.varizpardaz.listener.core.api.ClockOffset
import com.varizpardaz.listener.core.api.SignedApi
import com.varizpardaz.listener.core.configstore.ConfigRepository
import com.varizpardaz.listener.core.http.HttpTransport
import com.varizpardaz.listener.core.pairing.SecretBox
import com.varizpardaz.listener.core.queue.Counters
import com.varizpardaz.listener.core.queue.MessageQueue
import com.varizpardaz.listener.core.upload.Backoff
import com.varizpardaz.listener.core.upload.DrainOutcome
import com.varizpardaz.listener.core.upload.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Small facts that must outlive the process but are not secrets: the clock offset and the retry streak. */
class DeviceState(private val prefs: SharedPreferences) {
    var clockOffsetSeconds: Long
        get() = prefs.getLong(KEY_OFFSET, 0L)
        set(value) = prefs.edit { putLong(KEY_OFFSET, value) }

    /** Consecutive failed upload runs; drives the backoff, reset by any success. */
    var uploadFailures: Int
        get() = prefs.getInt(KEY_FAILURES, 0)
        set(value) = prefs.edit { putInt(KEY_FAILURES, value) }

    /** The user turned on reading bank-app notifications. Off by default; forgotten on unpair. */
    var notificationSourceEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATIONS, value) }

    /** When the last deposit or balance marker was queued (heartbeat `last_message_at`); 0 = never. */
    var lastMessageAtMillis: Long
        get() = prefs.getLong(KEY_LAST_MESSAGE, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_MESSAGE, value) }

    /**
     * The maker whose battery-guide steps the user said they completed (`Maker.name`). Android cannot report those settings, so this is
     * the user's word, shown as such. Forgotten on unpair.
     */
    var makerStepsConfirmedFor: String?
        get() = prefs.getString(KEY_MAKER_STEPS, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_MAKER_STEPS) else putString(KEY_MAKER_STEPS, value) }

    fun clear() = prefs.edit(commit = true) { clear() }

    private companion object {
        const val KEY_MAKER_STEPS = "maker_steps_confirmed_for"
        const val KEY_OFFSET = "clock_offset_seconds"
        const val KEY_FAILURES = "upload_failures"
        const val KEY_LAST_MESSAGE = "last_message_at"
        const val KEY_NOTIFICATIONS = "notification_source_enabled"
    }
}

/**
 * Two kinds of upload work: "now" (expedited, started when something was queued) and a delayed "retry" after a failure.
 * They have separate names so a new SMS is never stuck behind a retry that is waiting out its backoff.
 */
class UploadScheduler(private val context: Context) {
    private val needsNetwork = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun requestUpload() {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(needsNetwork)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // Appending, not keeping: a run that already read the queue must not swallow a message queued just after.
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun scheduleRetry(afterSeconds: Int) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(needsNetwork)
            .setInitialDelay(afterSeconds.toLong(), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RETRY, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
        WorkManager.getInstance(context).cancelUniqueWork(RETRY)
    }

    private companion object {
        const val NOW = "upload-now"
        const val RETRY = "upload-retry"
    }
}

sealed interface RunResult {
    data object Idle : RunResult

    data object RunAgainNow : RunResult

    class RetryIn(val seconds: Int) : RunResult
}

/** One upload run for the paired device, serialised so two workers never send the same items at once. */
class UploadRunner(
    private val pairing: PairingRepository,
    private val queue: MessageQueue,
    private val counters: Counters,
    private val bodyBox: SecretBox,
    private val configs: ConfigRepository,
    private val transport: HttpTransport,
    private val clock: ClockOffset,
    private val state: DeviceState,
    /** True while the installed version is below the server's minimum: nothing is uploaded, everything stays queued. */
    private val uploadsBlocked: (pinnedKey: String) -> Boolean = { false },
) {
    private val lock = Mutex()

    suspend fun run(): RunResult = lock.withLock {
        val credentials = (pairing.state.first { it !is PairingState.Loading } as? PairingState.Paired)?.credentials
            ?: return RunResult.Idle
        if (uploadsBlocked(credentials.serverPublicKey)) return RunResult.Idle
        val maxBatch = configs.current(credentials.serverPublicKey)?.raw?.upload?.maxBatch ?: DEFAULT_MAX_BATCH
        val api = SignedApi(transport, credentials, clock, onClockOffsetChanged = { state.clockOffsetSeconds = it })
        val uploader = Uploader(queue, counters, api, bodyBox, maxBatch)

        when (val outcome = withContext(Dispatchers.IO) { uploader.drain() }) {
            is DrainOutcome.Done -> {
                state.uploadFailures = 0
                RunResult.Idle
            }
            is DrainOutcome.MoreWaiting -> {
                state.uploadFailures = 0
                RunResult.RunAgainNow
            }
            is DrainOutcome.RetryLater -> {
                val failures = state.uploadFailures + 1
                state.uploadFailures = failures
                RunResult.RetryIn((outcome.afterSec ?: Backoff.delaySeconds(failures)).coerceIn(1, MAX_WAIT_SECONDS))
            }
            DrainOutcome.Revoked -> {
                pairing.markNeedsRepair(RepairReason.REVOKED)
                RunResult.Idle
            }
            DrainOutcome.RepairNeeded -> {
                pairing.markNeedsRepair(RepairReason.SECRET_REJECTED)
                RunResult.Idle
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_BATCH = 50
        const val MAX_WAIT_SECONDS = 3600 // honour a server's Retry-After, but not an absurd one
    }
}

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ListenerApp).container
        when (val next = container.uploadRunner.run()) {
            RunResult.Idle -> Unit
            RunResult.RunAgainNow -> container.scheduler.requestUpload()
            is RunResult.RetryIn -> container.scheduler.scheduleRetry(next.seconds)
        }
        // Failures are handled by our own backoff above (WorkManager's own would wait up to hours).
        return Result.success()
    }

    /** Needed by expedited work on Android 11 and older, where it runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.upload_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(applicationContext.getString(R.string.upload_notification_title))
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "upload"
        const val NOTIFICATION_ID = 2001
    }
}
