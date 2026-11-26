package com.example.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts sensitive FPTN token credentials (raw tokens, usernames, passwords) at rest in Room
 * using hardware-backed Android Keystore AES-256-GCM keys.
 *
 * Legacy plaintext entries without the [PREFIX_V1] marker are transparently readable so existing
 * profiles survive app upgrades without data loss.
 */
object TokenSecretCipher {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "fptn_flow_token_secrets_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LENGTH_BYTES = 12
    internal const val PREFIX_V1 = "enc:v1:"

    private val lock = Any()
    private val secureRandom = SecureRandom()

    @Volatile
    private var jvmFallbackKey: SecretKey? = null

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty() || plaintext.startsWith(PREFIX_V1)) {
            return plaintext
        }
        return synchronized(lock) {
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv: ByteArray
            val ciphertext: ByteArray
            if (isAndroidKeyStoreKey(key)) {
                // Android Keystore requires provider-generated IVs when randomizedEncryptionRequired is enabled
                cipher.init(Cipher.ENCRYPT_MODE, key)
                iv = cipher.iv
                ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            } else {
                iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            }
            val payload = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, payload, 0, iv.size)
            System.arraycopy(ciphertext, 0, payload, iv.size, ciphertext.size)
            PREFIX_V1 + payload.toByteString().base64()
        }
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX_V1)) {
            // Backward compatibility for legacy unencrypted rows
            return stored
        }
        return synchronized(lock) {
            runCatching {
                val payload = stored.removePrefix(PREFIX_V1).decodeBase64()?.toByteArray() ?: return@runCatching ""
                if (payload.size <= IV_LENGTH_BYTES) return@runCatching ""
                val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
                val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
                val key = getOrCreateSecretKey()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            }.getOrDefault("")
        }
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX_V1)

    private fun isAndroidKeyStoreKey(key: SecretKey): Boolean {
        return key !== jvmFallbackKey
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keystoreKey = runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        }.getOrNull()

        if (keystoreKey != null) {
            return keystoreKey
        }

        // Local JVM / Robolectric fallback when AndroidKeyStore service is absent
        return jvmFallbackKey ?: KeyGenerator.getInstance("AES").apply { init(256) }
            .generateKey()
            .also { jvmFallbackKey = it }
    }
}
