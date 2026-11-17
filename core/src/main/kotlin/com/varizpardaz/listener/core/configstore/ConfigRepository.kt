package com.varizpardaz.listener.core.configstore

import com.varizpardaz.listener.core.CompiledConfig
import com.varizpardaz.listener.core.ConfigLoader
import com.varizpardaz.listener.core.ConfigResult
import com.varizpardaz.listener.core.TimestampFormat
import com.varizpardaz.listener.core.api.ApiResult
import com.varizpardaz.listener.core.api.SignedApi

/** A config exactly as the server signed it: the raw bytes and the `X-PG-Config-Signature` value. */
class StoredConfig(val body: ByteArray, val signature: String)

interface ConfigPersistence {
    fun read(): StoredConfig?

    fun write(config: StoredConfig)

    fun clear()
}

sealed interface ConfigRefresh {
    class Installed(val version: Long) : ConfigRefresh

    /** The server's config is the one already in use. */
    class Unchanged(val version: Long) : ConfigRefresh

    /** The server sent an older config than the one in use; ignored (a replay must not weaken the gates). */
    class Stale(val version: Long) : ConfigRefresh

    /** Bad signature, malformed, or an unusable OTP pattern: the last good config stays in use. */
    class Rejected(val reason: ConfigResult.Reason) : ConfigRefresh

    /** The request itself did not produce a config (offline, revoked, ...). */
    class NotFetched(val result: ApiResult) : ConfigRefresh
}

/**
 * The signed config in use. It is persisted *with its signature* and verified again against the pinned key every time
 * it is loaded, so a file edited on disk is never trusted. On any failure the last good config stays in use.
 */
class ConfigRepository(private val persistence: ConfigPersistence) {
    private val lock = Any()
    private var cached: CompiledConfig? = null
    private var cachedKey: String? = null

    /** The config to classify with, or null if none has been fetched yet (nothing can be classified then). */
    fun current(pinnedKey: String): CompiledConfig? = synchronized(lock) {
        if (cached != null && cachedKey == pinnedKey) cached else loadStored(pinnedKey)
    }

    private fun loadStored(pinnedKey: String): CompiledConfig? {
        cached = null
        cachedKey = null
        val stored = persistence.read() ?: return null
        return when (val result = ConfigLoader.verifyAndLoad(stored.body, stored.signature, pinnedKey)) {
            is ConfigResult.Accepted -> result.config.also {
                cached = it
                cachedKey = pinnedKey
            }
            is ConfigResult.Rejected -> {
                persistence.clear() // signed by another key (re-paired elsewhere) or tampered with
                null
            }
        }
    }

    fun install(body: ByteArray, signature: String, pinnedKey: String): ConfigRefresh = synchronized(lock) {
        val have = current(pinnedKey)
        when (val result = ConfigLoader.verifyAndLoad(body, signature, pinnedKey)) {
            is ConfigResult.Rejected -> ConfigRefresh.Rejected(result.reason)
            is ConfigResult.Accepted -> {
                val incoming = result.config
                when {
                    have != null && incoming.version == have.version -> ConfigRefresh.Unchanged(incoming.version)
                    have != null && incoming.version < have.version && !issuedLater(incoming, have) -> ConfigRefresh.Stale(incoming.version)
                    else -> {
                        persistence.write(StoredConfig(body, signature))
                        cached = incoming
                        cachedKey = pinnedKey
                        ConfigRefresh.Installed(incoming.version)
                    }
                }
            }
        }
    }

    /** `GET /v1/devices/config`, verify, install. */
    fun refresh(api: SignedApi, pinnedKey: String): ConfigRefresh =
        when (val result = api.get(CONFIG_PATH)) {
            is ApiResult.Success -> install(result.response.body, result.response.header(SIGNATURE_HEADER).orEmpty(), pinnedKey)
            else -> ConfigRefresh.NotFetched(result)
        }

    fun clear() = synchronized(lock) {
        cached = null
        cachedKey = null
        persistence.clear()
    }

    // A server whose version counter restarted (a reset database) still moves forward in time.
    private fun issuedLater(incoming: CompiledConfig, have: CompiledConfig): Boolean {
        val a = TimestampFormat.parseToEpochMillis(incoming.raw.issuedAt) ?: return false
        val b = TimestampFormat.parseToEpochMillis(have.raw.issuedAt) ?: return false
        return a > b
    }

    companion object {
        const val CONFIG_PATH = "/v1/devices/config"
        const val SIGNATURE_HEADER = "X-PG-Config-Signature"
    }
}
