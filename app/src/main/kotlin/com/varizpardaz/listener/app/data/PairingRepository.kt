package com.varizpardaz.listener.app.data

import com.varizpardaz.listener.core.PairingLink
import com.varizpardaz.listener.core.http.HttpTransport
import com.varizpardaz.listener.core.pairing.CredentialStore
import com.varizpardaz.listener.core.pairing.DeviceCredentials
import com.varizpardaz.listener.core.pairing.DeviceInfo
import com.varizpardaz.listener.core.pairing.PairingFailure
import com.varizpardaz.listener.core.pairing.PairingOutcome
import com.varizpardaz.listener.core.pairing.PairingService
import com.varizpardaz.listener.core.pairing.StoredState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class RepairReason {
    /** The stored credentials cannot be read (lost Keystore key, corrupted file). */
    STORAGE_UNREADABLE,

    /** The server said `DEVICE_REVOKED`. */
    REVOKED,

    /** The server keeps refusing this device's signatures, so the stored secret is no longer valid there. */
    SECRET_REJECTED,
}

sealed interface PairingState {
    /** Reading the stored credentials. */
    data object Loading : PairingState

    data object NotPaired : PairingState

    class Paired(val credentials: DeviceCredentials) : PairingState

    class NeedsRepair(val reason: RepairReason) : PairingState
}

sealed interface PairResult {
    data object Paired : PairResult

    data object AlreadyPaired : PairResult

    class Failed(val failure: PairingFailure) : PairResult

    /** The server accepted the pairing but the phone could not store the secret safely. */
    data object StorageFailed : PairResult
}

/** Something that must forget everything it holds when the device is unpaired (e.g. the upload queue). */
fun interface DataWiper {
    fun wipe()
}

/**
 * Single source of truth for "is this phone paired, and with what". Everything that stops working after a
 * revocation or an unpair (heartbeat, uploads) observes [state].
 */
class PairingRepository(
    private val store: CredentialStore,
    transport: HttpTransport,
    deviceInfo: DeviceInfo,
    private val wipers: List<DataWiper> = emptyList(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val service = PairingService(transport, deviceInfo)
    private val lock = Mutex()
    private val _state = MutableStateFlow<PairingState>(PairingState.Loading)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    init {
        scope.launch { lock.withLock { _state.value = loadFromStore() } }
    }

    private fun loadFromStore(): PairingState =
        when (val stored = runCatching { store.load() }.getOrDefault(StoredState.Unreadable)) {
            StoredState.NotPaired -> PairingState.NotPaired
            StoredState.Unreadable -> PairingState.NeedsRepair(RepairReason.STORAGE_UNREADABLE)
            is StoredState.Paired -> PairingState.Paired(stored.credentials)
        }

    suspend fun pair(link: PairingLink): PairResult = withContext(io) {
        lock.withLock {
            if (_state.value is PairingState.Paired) return@withLock PairResult.AlreadyPaired
            // An unreadable leftover blob must not block a fresh pairing.
            when (val outcome = service.pair(link)) {
                is PairingOutcome.Failure -> PairResult.Failed(outcome.failure)
                is PairingOutcome.Success -> try {
                    store.save(outcome.credentials)
                    _state.value = PairingState.Paired(outcome.credentials)
                    PairResult.Paired
                } catch (_: Exception) {
                    // Keystore or disk failure: leave nothing half-stored.
                    runCatching { store.clear() }
                    PairResult.StorageFailed
                }
            }
        }
    }

    /**
     * Wipes the secret, the pinned key and the queue, then returns to [PairingState.NotPaired]. Also the way out
     * of [PairingState.NeedsRepair].
     */
    suspend fun unpair() = withContext(io) {
        lock.withLock { wipeLocked() }
    }

    /** For callers that learn the device is no longer valid (e.g. `DEVICE_REVOKED`): stop work and wipe the secret. */
    suspend fun markNeedsRepair(reason: RepairReason) = withContext(io) {
        lock.withLock {
            wipeLocked()
            _state.value = PairingState.NeedsRepair(reason)
        }
    }

    private fun wipeLocked() {
        // Each step is best effort and independent: a failure in one must not leave the secret behind.
        wipers.forEach { runCatching { it.wipe() } }
        runCatching { store.clear() }
        _state.value = PairingState.NotPaired
    }
}
