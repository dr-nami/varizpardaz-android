package com.varizpardaz.listener.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.varizpardaz.listener.app.data.KeystoreSecretBox
import com.varizpardaz.listener.core.pairing.CredentialStore
import com.varizpardaz.listener.core.pairing.BlobStorage
import com.varizpardaz.listener.core.pairing.DeviceCredentials
import com.varizpardaz.listener.core.pairing.SecretBoxException
import com.varizpardaz.listener.core.pairing.StoredState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device or emulator: the Android Keystore does not exist on the JVM. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretBoxTest {
    private val aad = "test".toByteArray()

    @Before
    @After
    fun cleanKey() = KeystoreSecretBox.deleteKey(KeystoreSecretBox.CREDENTIALS_ALIAS)

    @Test
    fun keyIsNotExportable() {
        val key = KeystoreSecretBox.getOrCreateKey(KeystoreSecretBox.CREDENTIALS_ALIAS)
        assertNull("the Keystore must not hand out the key bytes", key.encoded)
        assertEquals("AES", key.algorithm)
    }

    @Test
    fun sealAndOpenRoundTrip() {
        val box = KeystoreSecretBox.create(KeystoreSecretBox.CREDENTIALS_ALIAS)
        val plain = "device secret".toByteArray()
        val sealed = box.seal(plain, aad)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("device secret"))
        assertArrayEquals(plain, box.open(sealed, aad))
    }

    @Test
    fun theKeyIsStableAcrossLookups() {
        val box = KeystoreSecretBox.create(KeystoreSecretBox.CREDENTIALS_ALIAS)
        val sealed = box.seal("x".toByteArray(), aad)
        // A second box (as after a process restart) finds the same Keystore key.
        assertArrayEquals("x".toByteArray(), KeystoreSecretBox.create(KeystoreSecretBox.CREDENTIALS_ALIAS).open(sealed, aad))
    }

    @Test
    fun destroyedKeyCannotOpenOldData() {
        val box = KeystoreSecretBox.create(KeystoreSecretBox.CREDENTIALS_ALIAS)
        val sealed = box.seal("x".toByteArray(), aad)
        box.destroyKey()
        try {
            box.open(sealed, aad)
            fail("data sealed with a destroyed key must not open")
        } catch (_: SecretBoxException) {
            // expected
        }
    }

    @Test
    fun tamperedDataIsRefused() {
        val box = KeystoreSecretBox.create(KeystoreSecretBox.CREDENTIALS_ALIAS)
        val sealed = box.seal("x".toByteArray(), aad)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        try {
            box.open(sealed, aad)
            fail("tampered data must not open")
        } catch (_: SecretBoxException) {
            // expected
        }
    }

    @Test
    fun credentialStoreWorksOnTopOfTheKeystore() {
        var blob: ByteArray? = null
        val storage = object : BlobStorage {
            override fun read() = blob
            override fun write(bytes: ByteArray) {
                blob = bytes
            }
            override fun clear() {
                blob = null
            }
        }
        val store = CredentialStore(storage, KeystoreSecretBox.create(KeystoreSecretBox.CREDENTIALS_ALIAS))
        store.save(DeviceCredentials("https://x.ir", "dev_1", ByteArray(32) { it.toByte() }, "key", 30))
        val loaded = store.load()
        assertTrue(loaded is StoredState.Paired)
        assertNotNull((loaded as StoredState.Paired).credentials)
        store.clear()
        assertTrue(store.load() is StoredState.NotPaired)
    }

}
