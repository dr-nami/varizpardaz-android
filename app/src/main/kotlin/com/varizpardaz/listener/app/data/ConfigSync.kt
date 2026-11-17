package com.varizpardaz.listener.app.data

import com.varizpardaz.listener.core.api.ApiResult
import com.varizpardaz.listener.core.api.ClockOffset
import com.varizpardaz.listener.core.api.SignedApi
import com.varizpardaz.listener.core.configstore.ConfigRefresh
import com.varizpardaz.listener.core.configstore.ConfigRepository
import com.varizpardaz.listener.core.http.HttpTransport
import com.varizpardaz.listener.core.pairing.DeviceCredentials
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fetches the signed config whenever the phone is paired and keeps trying (with backoff, up to 5 minutes) until it has
 * one. Refreshing when the heartbeat reports a newer version is A5's job; this makes sure the phone is never paired
 * without a whitelist, because without a verified config every SMS is ignored.
 */
class ConfigSync(
    private val pairing: PairingRepository,
    private val configs: ConfigRepository,
    private val transport: HttpTransport,
    private val clock: ClockOffset,
    private val io: CoroutineDispatcher,
    private val onClockOffsetChanged: (Long) -> Unit,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            pairing.state.collectLatest { state ->
                if (state is PairingState.Paired) syncUntilDone(state.credentials)
            }
        }
    }

    private suspend fun syncUntilDone(credentials: DeviceCredentials) {
        val api = SignedApi(transport, credentials, clock, onClockOffsetChanged)
        var waitMs = FIRST_WAIT_MS
        while (true) {
            when (val result = withContext(io) { configs.refresh(api, credentials.serverPublicKey) }) {
                is ConfigRefresh.Installed, is ConfigRefresh.Unchanged, is ConfigRefresh.Stale -> return
                is ConfigRefresh.Rejected -> Unit // the server may fix itself; the last good config (if any) stays in use
                is ConfigRefresh.NotFetched -> when (result.result) {
                    ApiResult.Revoked -> return pairing.markNeedsRepair(RepairReason.REVOKED)
                    ApiResult.RepairNeeded -> return pairing.markNeedsRepair(RepairReason.SECRET_REJECTED)
                    else -> Unit // offline, rate limited, server error: try again later
                }
            }
            delay(waitMs)
            waitMs = minOf(waitMs * 2, MAX_WAIT_MS)
        }
    }

    private companion object {
        const val FIRST_WAIT_MS = 15_000L
        const val MAX_WAIT_MS = 5 * 60_000L
    }
}
