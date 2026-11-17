package com.varizpardaz.listener.core.pairing

import com.varizpardaz.listener.core.Base64Url
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Thrown when sealed data cannot be opened: wrong or lost key, tampering, truncation, unknown format. */
class SecretBoxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Authenticated encryption of small secrets. The key never leaves whatever implements this. */
interface SecretBox {
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray

    @Throws(SecretBoxException::class)
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray

    /** Irrecoverably destroys the key, so anything sealed before can never be opened again. */
    fun destroyKey()
}

/**
 * AES-256-GCM over any JCA [SecretKey]. On the phone the key is a non-exportable Android Keystore key (the
 * provider generates the IV, as Keystore requires); in tests it is an ordinary software key.
 *
 * Layout: `[version=1][iv length][iv][ciphertext || 16-byte tag]`. [aad] binds the ciphertext to its purpose.
 */
class AesGcmSecretBox(
    private val key: () -> SecretKey,
    private val onDestroy: () -> Unit,
) : SecretBox {
    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv.size in 1..255) { "unexpected IV size" }
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + ciphertext
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        if (sealed.size < HEADER + MIN_IV + TAG_BYTES || sealed[0] != VERSION) throw SecretBoxException("unknown format")
        val ivLength = sealed[1].toInt() and 0xFF
        if (ivLength < MIN_IV || sealed.size < HEADER + ivLength + TAG_BYTES) throw SecretBoxException("truncated")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BYTES * 8, sealed, HEADER, ivLength),
            )
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, HEADER + ivLength, sealed.size - HEADER - ivLength)
        } catch (e: GeneralSecurityException) {
            throw SecretBoxException("cannot open", e)
        } catch (e: RuntimeException) {
            // Keystore implementations report a lost or unusable key in assorted unchecked ways.
            throw SecretBoxException("key unusable", e)
        }
    }

    override fun destroyKey() = onDestroy()

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val HEADER = 2
        private const val MIN_IV = 12
        private const val TAG_BYTES = 16

        /** Software key for tests. */
        fun withRandomKey(): AesGcmSecretBox {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            val key = javax.crypto.spec.SecretKeySpec(bytes, "AES")
            return AesGcmSecretBox({ key }, {})
        }
    }
}

/** One opaque blob on disk (the app uses an atomic private file). */
interface BlobStorage {
    fun read(): ByteArray?

    fun write(bytes: ByteArray)

    fun clear()
}

sealed interface StoredState {
    data object NotPaired : StoredState

    class Paired(val credentials: DeviceCredentials) : StoredState

    /** Something is stored but cannot be used (lost Keystore key, corruption, unknown version). Needs re-pairing. */
    data object Unreadable : StoredState
}

/**
 * Persists [DeviceCredentials] as a single encrypted blob: the secret, the pinned server key, the base URL and
 * the device id are sealed together, so changing any of them on disk fails authentication.
 */
class CredentialStore(
    private val storage: BlobStorage,
    private val box: SecretBox,
) {
    fun save(credentials: DeviceCredentials) {
        val record = Record(
            baseUrl = credentials.baseUrl,
            deviceId = credentials.deviceId,
            deviceSecret = Base64Url.encode(credentials.deviceSecret),
            serverPublicKey = credentials.serverPublicKey,
            heartbeatIntervalSec = credentials.heartbeatIntervalSec,
        )
        val plain = json.encodeToString(Record.serializer(), record).toByteArray(Charsets.UTF_8)
        try {
            storage.write(box.seal(plain, AAD))
        } finally {
            plain.fill(0)
        }
    }

    fun load(): StoredState {
        val sealed = storage.read() ?: return StoredState.NotPaired
        val plain = try {
            box.open(sealed, AAD)
        } catch (_: SecretBoxException) {
            return StoredState.Unreadable
        }
        return try {
            val record = json.decodeFromString(Record.serializer(), plain.toString(Charsets.UTF_8))
            val secret = Base64Url.decodeOrNull(record.deviceSecret)
            if (record.version != 1 || secret == null || secret.size != 32 || record.deviceId.isBlank()) {
                StoredState.Unreadable
            } else {
                StoredState.Paired(
                    DeviceCredentials(record.baseUrl, record.deviceId, secret, record.serverPublicKey, record.heartbeatIntervalSec),
                )
            }
        } catch (_: SerializationException) {
            StoredState.Unreadable
        } catch (_: IllegalArgumentException) {
            StoredState.Unreadable
        } finally {
            plain.fill(0)
        }
    }

    /** Forget the pairing: delete the blob and destroy the key. */
    fun clear() {
        storage.clear()
        box.destroyKey()
    }

    @Serializable
    private class Record(
        val version: Int = 1,
        @SerialName("base_url") val baseUrl: String,
        @SerialName("device_id") val deviceId: String,
        @SerialName("device_secret") val deviceSecret: String,
        @SerialName("server_public_key") val serverPublicKey: String,
        @SerialName("heartbeat_interval_sec") val heartbeatIntervalSec: Int,
    )

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val AAD = "listener/credentials/v1".toByteArray(Charsets.UTF_8)
    }
}
