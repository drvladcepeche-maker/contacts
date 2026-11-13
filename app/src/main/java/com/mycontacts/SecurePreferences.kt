package com.mycontacts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores long-lived app credentials using a device-bound Android Keystore AES-GCM key.
 * Plaintext values from older app versions are encrypted and removed on first read.
 */
object SecurePreferences {
    private const val KEY_ALIAS = "moi_contacts_app_secrets_v1"
    private const val IV_SIZE = 12

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        }
        return store.getKey(KEY_ALIAS, null) as SecretKey
    }

    private fun encrypt(value: String, associatedData: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteArrayOutputStream().apply {
            write(cipher.iv)
            write(encrypted)
        }.toByteArray()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(value: String, associatedData: String): String {
        val packed = Base64.decode(value, Base64.NO_WRAP)
        require(packed.size > IV_SIZE) { "Encrypted preference is malformed" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, packed.copyOfRange(0, IV_SIZE))
        )
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(packed.copyOfRange(IV_SIZE, packed.size)).toString(Charsets.UTF_8)
    }

    fun get(context: Context, preferencesName: String, name: String): String {
        val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val encryptedName = "${name}_encrypted"
        val encrypted = prefs.getString(encryptedName, null)
        if (!encrypted.isNullOrBlank()) {
            return try { decrypt(encrypted, "$preferencesName:$name") } catch (_: Exception) { "" }
        }

        val legacy = prefs.getString(name, null) ?: return ""
        if (legacy.isBlank()) return ""
        return try {
            val ciphertext = encrypt(legacy, "$preferencesName:$name")
            if (prefs.edit().putString(encryptedName, ciphertext).remove(name).commit()) legacy
            else legacy
        } catch (_: Exception) {
            // Preserve access if the device keystore is temporarily unavailable.
            legacy
        }
    }

    fun put(context: Context, preferencesName: String, name: String, value: String) {
        val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        if (value.isBlank()) {
            remove(context, preferencesName, name)
            return
        }
        val ciphertext = encrypt(value, "$preferencesName:$name")
        check(prefs.edit().putString("${name}_encrypted", ciphertext).remove(name).commit()) {
            "Could not persist encrypted app credential"
        }
    }

    fun remove(context: Context, preferencesName: String, name: String) {
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit().remove(name).remove("${name}_encrypted").commit()
    }
}
