package com.varizpardaz.listener.app.data

import android.content.Context
import android.os.Build
import com.varizpardaz.listener.app.BuildConfig
import com.varizpardaz.listener.app.guide.Maker
import com.varizpardaz.listener.app.guide.MakerDetector
import com.varizpardaz.listener.app.monitor.DeviceSnapshotProvider
import com.varizpardaz.listener.app.notification.NotificationSourceSetting
import com.varizpardaz.listener.app.monitor.MonitorEngine
import com.varizpardaz.listener.app.monitor.MonitorService
import com.varizpardaz.listener.app.monitor.PermissionState
import com.varizpardaz.listener.app.monitor.PrefsRescanLedger
import com.varizpardaz.listener.app.monitor.SimRegistry
import com.varizpardaz.listener.app.monitor.SmsInboxReader
import com.varizpardaz.listener.app.queue.QueueDatabase
import com.varizpardaz.listener.app.queue.RoomCounters
import com.varizpardaz.listener.app.queue.RoomMessageQueue
import com.varizpardaz.listener.app.sms.IntakeCoordinator
import com.varizpardaz.listener.app.status.SystemFacts
import com.varizpardaz.listener.app.update.OkHttpDownloadTransport
import com.varizpardaz.listener.app.update.UpdateManager
import com.varizpardaz.listener.app.upload.DeviceState
import com.varizpardaz.listener.app.upload.UploadRunner
import com.varizpardaz.listener.app.upload.UploadScheduler
import com.varizpardaz.listener.core.CardInfo
import com.varizpardaz.listener.core.DisplayName
import com.varizpardaz.listener.core.api.ClockOffset
import com.varizpardaz.listener.core.configstore.ConfigRepository
import com.varizpardaz.listener.core.intake.SmsIntake
import com.varizpardaz.listener.core.pairing.CredentialStore
import com.varizpardaz.listener.core.pairing.DeviceInfo
import com.varizpardaz.listener.core.queue.CounterName
import com.varizpardaz.listener.core.queue.NullCounters
import com.varizpardaz.listener.core.rescan.RescanProcessor
import com.varizpardaz.listener.core.update.UpdatePolicy
import com.varizpardaz.listener.core.update.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Hand-rolled dependency container; the app is small enough that a DI framework would only add weight. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val transport = OkHttpTransport()
    private val devicePrefs = appContext.getSharedPreferences("device_state", Context.MODE_PRIVATE)
    private val deviceState = DeviceState(devicePrefs)
    private val sims = SimRegistry(devicePrefs)
    val notificationSource = NotificationSourceSetting(deviceState)
    private val rescanLedger = PrefsRescanLedger(devicePrefs)
    private val clockOffset = ClockOffset(deviceState.clockOffsetSeconds)

    // ---- storage: the queue and the counters share one database; bodies are sealed with their own Keystore key ----
    private val database: QueueDatabase by lazy {
        QueueDatabase.create(appContext)
    }
    val queue by lazy { RoomMessageQueue(database) }
    val counters by lazy { RoomCounters(database) }
    private val queueBox = KeystoreSecretBox.create(KeystoreSecretBox.QUEUE_ALIAS)
    val configs = ConfigRepository(ConfigFilePersistence(File(appContext.filesDir, "config.json")))

    /** Everything that must be forgotten on unpair, each step on its own so one failure leaves nothing behind. */
    private val wipers = listOf(
        DataWiper { database.wipeCompletely() }, // queued items and counters, and the write-ahead log
        DataWiper { queueBox.destroyKey() }, // the key for bodies that may linger in free database pages
        DataWiper { configs.clear() },
        DataWiper { scheduler.cancelAll() },
        DataWiper {
            deviceState.clear()
            clockOffset.seconds = 0
        },
    )

    val pairing = PairingRepository(
        store = CredentialStore(
            storage = FileBlobStorage(File(appContext.filesDir, "credentials.bin")),
            box = KeystoreSecretBox.create(KeystoreSecretBox.CREDENTIALS_ALIAS),
        ),
        transport = transport,
        deviceInfo = DeviceInfo(
            name = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android" },
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            appVersionCode = BuildConfig.VERSION_CODE,
        ),
        wipers = wipers,
    )

    val scheduler = UploadScheduler(appContext)
    val intake = IntakeCoordinator(
        pairing = pairing,
        configs = configs,
        intake = SmsIntake(queue, counters, queueBox),
        scheduler = scheduler,
        sims = sims,
        state = deviceState,
        // A phone that lost its service (killed by the system, or restricted and then released) gets it back with the next message,
        // on phones where Android allows that (a battery-exempt app may start it from the background; others are refused harmlessly).
        keepAlive = { MonitorService.ensureRunning(appContext) },
    )

    /** The in-app update: offered when the signed config names a newer version, required (and uploads stop) below its minimum. */
    val updates = UpdateManager(appContext, configs, OkHttpDownloadTransport(), scope)

    // Below the server's minimum version nothing is uploaded; items stay in the queue and go out after the update.
    val uploadRunner = UploadRunner(
        pairing, queue, counters, queueBox, configs, transport, clockOffset, deviceState,
        uploadsBlocked = { pinnedKey -> UpdatePolicy.blocksUploads(updates.current(pinnedKey)) },
    )

    val monitor = MonitorEngine(
        context = appContext,
        pairing = pairing,
        configs = configs,
        transport = transport,
        clock = clockOffset,
        state = deviceState,
        queue = queue,
        counters = counters,
        ledger = rescanLedger,
        rescan = RescanProcessor(
            inbox = SmsInboxReader(appContext),
            intake = SmsIntake(queue, NullCounters, queueBox), // a rescan must not inflate the dropped counters
            subscriptionOfSlot = sims::subscriptionOfSlot,
            slotOfSubscription = sims::slotOfSubscription,
        ),
        snapshots = DeviceSnapshotProvider(appContext, sims) { notificationSource.enabled },
        scheduler = scheduler,
    )

    init {
        updates.cleanUp() // the file of an update that was just installed, or of an attempt that was interrupted
        ConfigSync(pairing, configs, transport, clockOffset, Dispatchers.IO) { deviceState.clockOffsetSeconds = it }.start(scope)
        // Items queued before a restart or a crash are picked up as soon as the phone is known to be paired.
        scope.launch {
            pairing.state.collect {
                if (it is PairingState.Paired) {
                    scheduler.requestUpload()
                    MonitorService.ensureRunning(appContext)
                }
            }
        }
    }

    /** When this process started, so the status screen can tell "still connecting" from "down". */
    val processStartedMillis: Long = System.currentTimeMillis()

    /** The phone maker whose background limits the battery guide leads with. */
    val detectedMaker: Maker = MakerDetector.detect(Build.MANUFACTURER, Build.BRAND)

    /** What the status screen shows: numbers, the cards the server lists, and times. Never any message content. */
    fun status(pinnedKey: String): Status {
        val config = configs.current(pinnedKey)
        return Status(
            queueDepth = queue.size(),
            counters = counters.snapshot(),
            lastHeartbeatMillis = monitor.status.value.lastSuccessMillis,
            lastMessageAtMillis = deviceState.lastMessageAtMillis.takeIf { it > 0 },
            configVersion = config?.version,
            cards = config?.raw?.cards.orEmpty(),
            merchantName = DisplayName.clean(config?.raw?.merchantName),
            update = updates.current(pinnedKey),
        )
    }

    /** What the permission checklist is built from; cheap, so the screen re-reads it whenever it comes back to the front. */
    fun systemFacts(): SystemFacts {
        val permissions = PermissionState.all(appContext)
        return SystemFacts(
            receiveSms = permissions.receiveSms,
            readSms = permissions.readSms,
            postNotifications = permissions.postNotifications,
            batteryExempt = permissions.ignoreBatteryOptimizations,
            backgroundRestricted = PermissionState.backgroundRestricted(appContext),
            exemptFromUnusedAppPause = PermissionState.exemptFromUnusedAppPause(appContext),
            makerStepsConfirmed = if (detectedMaker == Maker.OTHER) null else deviceState.makerStepsConfirmedFor == detectedMaker.name,
            notificationSourceEnabled = notificationSource.enabled,
            notificationAccess = permissions.notificationListener,
        )
    }

    fun setMakerStepsConfirmed(confirmed: Boolean) {
        deviceState.makerStepsConfirmedFor = if (confirmed) detectedMaker.name else null
    }

    class Status(
        val queueDepth: Int,
        val counters: Map<CounterName, Long>,
        val lastHeartbeatMillis: Long?,
        val lastMessageAtMillis: Long?,
        val configVersion: Long?,
        val cards: List<CardInfo>,
        /** The shop the phone belongs to, if the server says (display only), already cleaned for one line. */
        val merchantName: String?,
        val update: UpdateState,
    )
}
