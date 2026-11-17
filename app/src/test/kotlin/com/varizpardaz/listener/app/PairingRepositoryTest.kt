package com.varizpardaz.listener.app

import com.varizpardaz.listener.app.data.DataWiper
import com.varizpardaz.listener.app.data.PairResult
import com.varizpardaz.listener.app.data.PairingRepository
import com.varizpardaz.listener.app.data.PairingState
import com.varizpardaz.listener.app.data.RepairReason
import com.varizpardaz.listener.core.Base64Url
import com.varizpardaz.listener.core.PairingLink
import com.varizpardaz.listener.core.http.HttpResponse
import com.varizpardaz.listener.core.http.HttpTransport
import com.varizpardaz.listener.core.pairing.AesGcmSecretBox
import com.varizpardaz.listener.core.pairing.BlobStorage
import com.varizpardaz.listener.core.pairing.CredentialStore
import com.varizpardaz.listener.core.pairing.DeviceInfo
import com.varizpardaz.listener.core.pairing.PairingFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingRepositoryTest {
    private class MemoryStorage : BlobStorage {
        var bytes: ByteArray? = null
        var failWrites = false
        override fun read() = bytes?.copyOf()
        override fun write(bytes: ByteArray) {
            if (failWrites) throw IOException("disk full")
            this.bytes = bytes.copyOf()
        }
        override fun clear() {
            bytes = null
        }
    }

    private val storage = MemoryStorage()
    private val box = AesGcmSecretBox.withRandomKey()
    private val link = PairingLink("https://api.example.com", "AB12CD34")
    private val okBody = """{"device_id":"dev_1","device_secret":"${Base64Url.encode(ByteArray(32) { 1 })}","server_public_key":"${Base64Url.encode(ByteArray(32) { 2 })}","heartbeat_interval_sec":30}"""

    private var response: () -> HttpResponse = { HttpResponse(201, emptyMap(), okBody.toByteArray()) }
    private val transport = HttpTransport { response() }
    private val wiped = mutableListOf<String>()

    private fun repository(): PairingRepository = PairingRepository(
        store = CredentialStore(storage, box),
        transport = transport,
        deviceInfo = DeviceInfo("Test phone", "14", 1),
        wipers = listOf(DataWiper { wiped += "queue" }, DataWiper { throw IllegalStateException("one wiper fails") }, DataWiper { wiped += "other" }),
        io = Dispatchers.Unconfined,
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `a fresh install is not paired`() {
        assertIs<PairingState.NotPaired>(repository().state.value)
    }

    @Test
    fun `pairing stores the credentials and survives a restart`() = runBlocking<Unit> {
        val repo = repository()
        assertEquals(PairResult.Paired, repo.pair(link))
        val paired = assertIs<PairingState.Paired>(repo.state.value)
        assertEquals("dev_1", paired.credentials.deviceId)

        val afterRestart = assertIs<PairingState.Paired>(repository().state.value)
        assertEquals("dev_1", afterRestart.credentials.deviceId)
    }

    @Test
    fun `a failed pairing changes nothing`() = runBlocking<Unit> {
        response = { HttpResponse(400, emptyMap(), """{"ok":false,"error":{"code":"PAIRING_CODE_INVALID","message":"x"}}""".toByteArray()) }
        val repo = repository()
        val result = assertIs<PairResult.Failed>(repo.pair(link))
        assertEquals(PairingFailure.CodeInvalid, result.failure)
        assertIs<PairingState.NotPaired>(repo.state.value)
        assertNull(storage.bytes)
    }

    @Test
    fun `no connection is reported, not thrown`() = runBlocking<Unit> {
        response = { throw IOException("no route") }
        assertEquals(PairingFailure.Network, assertIs<PairResult.Failed>(repository().pair(link)).failure)
    }

    @Test
    fun `a second pairing while paired is refused without calling the server`() = runBlocking<Unit> {
        val repo = repository()
        repo.pair(link)
        var called = false
        response = {
            called = true
            HttpResponse(201, emptyMap(), okBody.toByteArray())
        }
        assertEquals(PairResult.AlreadyPaired, repo.pair(link))
        assertFalse(called)
    }

    @Test
    fun `if the secret cannot be stored nothing is left behind`() = runBlocking<Unit> {
        storage.failWrites = true
        val repo = repository()
        assertEquals(PairResult.StorageFailed, repo.pair(link))
        assertIs<PairingState.NotPaired>(repo.state.value)
        assertNull(storage.bytes)
    }

    @Test
    fun `unpair wipes the secret and the queue even if one wiper fails`() = runBlocking<Unit> {
        val repo = repository()
        repo.pair(link)
        repo.unpair()
        assertIs<PairingState.NotPaired>(repo.state.value)
        assertNull(storage.bytes)
        assertEquals(listOf("queue", "other"), wiped)
        assertIs<PairingState.NotPaired>(repository().state.value)
    }

    @Test
    fun `unreadable stored data asks for re-pairing, and unpair is the way out`() = runBlocking<Unit> {
        storage.bytes = ByteArray(80) { 3 }
        val repo = repository()
        assertEquals(RepairReason.STORAGE_UNREADABLE, assertIs<PairingState.NeedsRepair>(repo.state.value).reason)
        repo.unpair()
        assertIs<PairingState.NotPaired>(repo.state.value)
        assertEquals(PairResult.Paired, repo.pair(link))
    }

    @Test
    fun `a revoked device is wiped and shown the re-pair screen`() = runBlocking<Unit> {
        val repo = repository()
        repo.pair(link)
        repo.markNeedsRepair(RepairReason.REVOKED)
        assertEquals(RepairReason.REVOKED, assertIs<PairingState.NeedsRepair>(repo.state.value).reason)
        assertNull(storage.bytes)
        assertTrue("queue" in wiped)
    }
}
