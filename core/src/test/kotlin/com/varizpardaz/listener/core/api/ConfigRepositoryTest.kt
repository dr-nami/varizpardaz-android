package com.varizpardaz.listener.core.api

import com.varizpardaz.listener.core.ConfigResult
import com.varizpardaz.listener.core.TestEd25519Key
import com.varizpardaz.listener.core.configstore.ConfigPersistence
import com.varizpardaz.listener.core.configstore.ConfigRefresh
import com.varizpardaz.listener.core.configstore.ConfigRepository
import com.varizpardaz.listener.core.configstore.StoredConfig
import com.varizpardaz.listener.core.http.HttpResponse
import com.varizpardaz.listener.core.http.HttpTransport
import com.varizpardaz.listener.core.pairing.DeviceCredentials
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigRepositoryTest {
    private class MemoryPersistence : ConfigPersistence {
        var stored: StoredConfig? = null
        override fun read() = stored
        override fun write(config: StoredConfig) {
            stored = config
        }
        override fun clear() {
            stored = null
        }
    }

    private val key = TestEd25519Key()
    private val persistence = MemoryPersistence()
    private val repository = ConfigRepository(persistence)

    private fun config(version: Long, issuedAt: String = "2026-11-01T10:00:00+03:30") =
        """{"version":$version,"issued_at":"$issuedAt","heartbeat_interval_sec":30,"upload":{"max_batch":50},
            "otp_patterns":[{"pattern":"otp","flags":"i"}],"deposit_keywords":["واریز"],"banks":[]}""".toByteArray()

    private fun install(version: Long, issuedAt: String = "2026-11-01T10:00:00+03:30", signer: TestEd25519Key = key): ConfigRefresh {
        val body = config(version, issuedAt)
        return repository.install(body, signer.sign(body), key.publicKeyB64Url)
    }

    @Test
    fun `nothing is in use before a config has been installed`() {
        assertNull(repository.current(key.publicKeyB64Url))
    }

    @Test
    fun `a signed config is installed, persisted with its signature, and reloaded after a restart`() {
        assertIs<ConfigRefresh.Installed>(install(3))
        assertEquals(3L, repository.current(key.publicKeyB64Url)?.version)
        assertNotNull(persistence.stored)
        assertEquals(3L, ConfigRepository(persistence).current(key.publicKeyB64Url)?.version)
    }

    @Test
    fun `a config signed by another key is rejected and the good one stays`() {
        install(3)
        val result = install(4, signer = TestEd25519Key())
        assertEquals(ConfigResult.Reason.BAD_SIGNATURE, assertIs<ConfigRefresh.Rejected>(result).reason)
        assertEquals(3L, repository.current(key.publicKeyB64Url)?.version)
    }

    @Test
    fun `an older config is ignored, the same one is unchanged, a newer one is installed`() {
        install(5)
        assertEquals(4L, assertIs<ConfigRefresh.Stale>(install(4)).version)
        assertEquals(5L, assertIs<ConfigRefresh.Unchanged>(install(5)).version)
        assertEquals(6L, assertIs<ConfigRefresh.Installed>(install(6)).version)
        assertEquals(6L, repository.current(key.publicKeyB64Url)?.version)
    }

    @Test
    fun `a restarted server version counter is accepted when the config is newer in time`() {
        install(50, issuedAt = "2026-11-01T10:00:00+03:30")
        assertIs<ConfigRefresh.Stale>(install(1, issuedAt = "2026-10-01T10:00:00+03:30"))
        assertIs<ConfigRefresh.Installed>(install(1, issuedAt = "2026-11-02T10:00:00+03:30"))
        assertEquals(1L, repository.current(key.publicKeyB64Url)?.version)
    }

    @Test
    fun `a stored config that no longer verifies is dropped, not trusted`() {
        install(3)
        // Tampered file.
        val s = persistence.stored!!
        persistence.stored = StoredConfig(s.body.copyOf().also { it[5] = (it[5] + 1).toByte() }, s.signature)
        assertNull(ConfigRepository(persistence).current(key.publicKeyB64Url))
        assertNull(persistence.stored)

        // Valid file but a different pinned key (the phone was re-paired to another server).
        install(3)
        assertNull(ConfigRepository(persistence).current(TestEd25519Key().publicKeyB64Url))
        assertNull(persistence.stored)
    }

    @Test
    fun `refresh fetches, verifies and installs`() {
        val body = config(7)
        val signature = key.sign(body)
        val transport = HttpTransport { HttpResponse(200, mapOf("X-PG-Config-Signature" to signature), body) }
        val api = SignedApi(transport, DeviceCredentials("https://x.ir", "dev_1", ByteArray(32), key.publicKeyB64Url, 30))
        assertEquals(7L, assertIs<ConfigRefresh.Installed>(repository.refresh(api, key.publicKeyB64Url)).version)
    }

    @Test
    fun `refresh with a missing or forged signature header installs nothing`() {
        val body = config(7)
        val creds = DeviceCredentials("https://x.ir", "dev_1", ByteArray(32), key.publicKeyB64Url, 30)
        val noHeader = SignedApi(HttpTransport { HttpResponse(200, emptyMap(), body) }, creds)
        assertIs<ConfigRefresh.Rejected>(repository.refresh(noHeader, key.publicKeyB64Url))
        val forged = SignedApi(HttpTransport { HttpResponse(200, mapOf("X-PG-Config-Signature" to TestEd25519Key().sign(body)), body) }, creds)
        assertIs<ConfigRefresh.Rejected>(repository.refresh(forged, key.publicKeyB64Url))
        assertNull(repository.current(key.publicKeyB64Url))
    }

    @Test
    fun `refresh while offline reports not fetched and keeps what it has`() {
        install(3)
        val api = SignedApi(HttpTransport { throw IOException("offline") }, DeviceCredentials("https://x.ir", "dev_1", ByteArray(32), key.publicKeyB64Url, 30))
        assertIs<ConfigRefresh.NotFetched>(repository.refresh(api, key.publicKeyB64Url))
        assertEquals(3L, repository.current(key.publicKeyB64Url)?.version)
    }
}
