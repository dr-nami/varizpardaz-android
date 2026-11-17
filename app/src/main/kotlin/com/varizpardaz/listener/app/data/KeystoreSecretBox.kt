package com.varizpardaz.listener.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.varizpardaz.listener.core.pairing.AesGcmSecretBox
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * AES-256-GCM keys that live in the Android Keystore and are not exportable: the app can use them to encrypt and
 * decrypt, but never read their bytes (`key.encoded` is null). One key per purpose, so destroying one (on unpair)
 * leaves nothing readable behind for that purpose.
 *
 * Deliberately *not* tied to the screen lock (`setUserAuthenticationRequired` / `setUnlockedDeviceRequired`):
 * the SMS receiver must be able to seal and sign while the phone is locked.
 */
object KeystoreSecretBox {
    private const val PROVIDER = "AndroidKeyStore"

    /** Protects `credentials.bin` (device secret, pinned server key). */
    const val CREDENTIALS_ALIAS = "listener_credentials_v1"

    /** Protects the bodies of queued deposit messages. */
    const val QUEUE_ALIAS = "listener_queue_v1"

    fun create(alias: String): AesGcmSecretBox =
        AesGcmSecretBox(key = { getOrCreateKey(alias) }, onDestroy = { deleteKey(alias) })

    @Synchronized
    fun getOrCreateKey(alias: String): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    @Synchronized
    fun deleteKey(alias: String) {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
}
