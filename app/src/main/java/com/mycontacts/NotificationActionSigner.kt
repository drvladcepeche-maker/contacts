package com.mycontacts

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Authenticates notification action extras before MainActivity accepts them.
 * The exported launcher activity can be started by other apps, so command ID and
 * decision extras alone are not proof that Android delivered our PendingIntent.
 */
object NotificationActionSigner {
    private const val KEY_ALIAS = "mycontacts_notification_action_hmac_v1"

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                "AndroidKeyStore",
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generator.generateKey()
        }
        return store.getKey(KEY_ALIAS, null) as SecretKey
    }

    private fun payload(commandId: String, decision: String): ByteArray =
        "bridge-command-notification:v1:$commandId:$decision".toByteArray(Charsets.UTF_8)

    fun sign(commandId: String, decision: String): String? {
        if (!isValidCommandId(commandId) || decision !in setOf("approve", "reject")) return null
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key())
            Base64.encodeToString(mac.doFinal(payload(commandId, decision)), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun verify(commandId: String, decision: String, signature: String): Boolean {
        if (!isValidCommandId(commandId) || decision !in setOf("approve", "reject") || signature.isBlank()) return false
        return try {
            val expected = Base64.decode(sign(commandId, decision) ?: return false, Base64.NO_WRAP)
            val supplied = Base64.decode(signature, Base64.NO_WRAP)
            MessageDigest.isEqual(expected, supplied)
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidCommandId(value: String): Boolean =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
            .matches(value)
}
