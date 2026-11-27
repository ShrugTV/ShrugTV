package com.shrug.shrugtv.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authenticated encryption for the few secrets the app keeps. Small on purpose so tests can
 * replace the Android Keystore, which Robolectric does not have, with a plain JVM key.
 * Both methods throw on any failure; [SecretStore] turns that into "no value".
 */
interface SecretCipher {
    /** Returns the IV followed by the ciphertext with its tag; [aad] binds the value to its place. */
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray

    /** Reverses [encrypt]; throws when the key is gone or the data or [aad] do not match. */
    fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore, alias [ALIAS]. The key is created on the first
 * encryption; decryption never creates one, so a lost key reads as "no value" instead of as garbage.
 */
class KeystoreCipher(private val alias: String = ALIAS) : SecretCipher {

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The keystore picks a fresh random IV (randomized encryption is required for its keys)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: newKey())
        cipher.updateAAD(aad)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV length ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray, aad: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "Ciphertext too short" }
        val key = existingKey() ?: throw IllegalStateException("Keystore key $alias missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return ks.getKey(alias, null) as? SecretKey
    }

    private fun newKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        const val ALIAS = "shrugtv_secrets_v1"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
