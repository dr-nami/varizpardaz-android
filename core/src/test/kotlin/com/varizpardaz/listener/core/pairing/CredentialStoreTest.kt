package com.varizpardaz.listener.core.pairing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStorage : BlobStorage {
    var bytes: ByteArray? = null
    override fun read() = bytes?.copyOf()
    override fun write(bytes: ByteArray) {
        this.bytes = bytes.copyOf()
    }
    override fun clear() {
        bytes = null
    }
}

class SecretBoxTest {
    private val aad = "purpose".toByteArray()

    @Test
    fun `seal then open returns the plaintext`() {
        val box = AesGcmSecretBox.withRandomKey()
        val plain = "device-secret".toByteArray()
        assertContentEquals(plain, box.open(box.seal(plain, aad), aad))
    }

    @Test
    fun `every seal uses a fresh iv`() {
        val box = AesGcmSecretBox.withRandomKey()
        assertNotEquals(box.seal(byteArrayOf(1), aad).toList(), box.seal(byteArrayOf(1), aad).toList())
    }

    @Test
    fun `flipping any byte is detected`() {
        val box = AesGcmSecretBox.withRandomKey()
        val sealed = box.seal("0123456789".toByteArray(), aad)
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<SecretBoxException>("byte $i") { box.open(tampered, aad) }
        }
    }

    @Test
    fun `wrong aad, wrong key, truncation and junk are refused`() {
        val box = AesGcmSecretBox.withRandomKey()
        val sealed = box.seal("x".toByteArray(), aad)
        assertFailsWith<SecretBoxException> { box.open(sealed, "other".toByteArray()) }
        assertFailsWith<SecretBoxException> { AesGcmSecretBox.withRandomKey().open(sealed, aad) }
        assertFailsWith<SecretBoxException> { box.open(sealed.copyOf(sealed.size - 1), aad) }
        assertFailsWith<SecretBoxException> { box.open(sealed.copyOf(10), aad) }
        assertFailsWith<SecretBoxException> { box.open(ByteArray(0), aad) }
        assertFailsWith<SecretBoxException> { box.open(ByteArray(64), aad) }
    }
}

class CredentialStoreTest {
    private val storage = MemoryStorage()
    private val box = AesGcmSecretBox.withRandomKey()
    private val store = CredentialStore(storage, box)
    private val credentials = DeviceCredentials(
        baseUrl = "https://api.example.com",
        deviceId = "dev_abc",
        deviceSecret = ByteArray(32) { (it * 3).toByte() },
        serverPublicKey = "pubkey-base64url",
        heartbeatIntervalSec = 30,
    )

    @Test
    fun `nothing stored means not paired`() {
        assertIs<StoredState.NotPaired>(store.load())
    }

    @Test
    fun `credentials survive a save and load`() {
        store.save(credentials)
        val loaded = assertIs<StoredState.Paired>(store.load()).credentials
        assertEquals("dev_abc", loaded.deviceId)
        assertEquals("https://api.example.com", loaded.baseUrl)
        assertEquals("pubkey-base64url", loaded.serverPublicKey)
        assertEquals(30, loaded.heartbeatIntervalSec)
        assertContentEquals(credentials.deviceSecret, loaded.deviceSecret)
    }

    @Test
    fun `the stored bytes contain none of the values in clear`() {
        store.save(credentials)
        val text = String(storage.bytes!!, Charsets.ISO_8859_1)
        for (needle in listOf("dev_abc", "api.example.com", "pubkey-base64url", "device_secret")) {
            assertTrue(needle !in text, "'$needle' is stored in clear")
        }
    }

    @Test
    fun `tampering, a lost key and junk all read as unreadable, never as paired`() {
        store.save(credentials)
        val good = storage.bytes!!

        storage.bytes = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertIs<StoredState.Unreadable>(store.load())

        storage.bytes = good
        assertIs<StoredState.Unreadable>(CredentialStore(storage, AesGcmSecretBox.withRandomKey()).load())

        storage.bytes = ByteArray(50) { 7 }
        assertIs<StoredState.Unreadable>(store.load())
    }

    @Test
    fun `a valid blob holding an invalid record is unreadable`() {
        val aad = "listener/credentials/v1".toByteArray()
        for (json in listOf(
            """{"version":2,"base_url":"u","device_id":"d","device_secret":"AAAA","server_public_key":"k","heartbeat_interval_sec":30}""",
            """{"version":1,"base_url":"u","device_id":"d","device_secret":"AAAA","server_public_key":"k","heartbeat_interval_sec":30}""",
            """not json""",
        )) {
            storage.bytes = box.seal(json.toByteArray(), aad)
            assertIs<StoredState.Unreadable>(store.load(), json)
        }
    }

    @Test
    fun `a blob sealed for another purpose is refused`() {
        storage.bytes = box.seal("{}".toByteArray(), "something-else".toByteArray())
        assertIs<StoredState.Unreadable>(store.load())
    }

    @Test
    fun `clear removes the blob and destroys the key`() {
        var destroyed = 0
        val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 5 }, "AES")
        val local = CredentialStore(storage, AesGcmSecretBox({ key }, { destroyed++ }))
        local.save(credentials)
        local.clear()
        assertNull(storage.bytes)
        assertEquals(1, destroyed)
        assertIs<StoredState.NotPaired>(local.load())
    }
}
