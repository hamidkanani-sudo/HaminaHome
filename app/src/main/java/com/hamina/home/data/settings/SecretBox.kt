package com.hamina.home.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (SMB passwords) with an AES-GCM key kept in the Android Keystore.
 * Values are stored as "enc1:<base64(iv + ciphertext)>". Values without the prefix are treated
 * as legacy plain text so old installs keep working until they are migrated.
 */
object SecretBox {
    private const val TAG = "HaminaSecret"
    private const val ALIAS = "hamina_server_secret"
    private const val PREFIX = "enc1:"
    private const val IV_SIZE = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Some cheap TV boxes ship a broken Keystore. Keep the app usable instead of losing the password.
            Log.e(TAG, "Encryption unavailable, storing value unencrypted", e)
            plain
        }
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val data = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = data.copyOfRange(0, IV_SIZE)
            val cipherText = data.copyOfRange(IV_SIZE, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Could not decrypt stored value", e)
            ""
        }
    }
}
