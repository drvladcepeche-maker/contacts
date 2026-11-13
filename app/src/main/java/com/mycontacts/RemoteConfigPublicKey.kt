package com.mycontacts

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Public verification key pinned in the signed host APK; it is not a secret. */
object RemoteConfigPublicKey {
    private const val SPKI_DER_BASE64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE+/vuBNKogmd2IAXNAqHCxhWTnSOz0AJPj33FKfy4ImcH9tEOjYgBG8W93lmP9jPjupJb1o0o8Jsf2xAhFltSfg=="

    private const val EXPECTED_SHA256 =
        "142b34dbec6bd761d60ed26344e2aacf4c7a05a47b7d4a4b3e80d6871987d0fc"

    @JvmStatic
    fun load(): PublicKey {
        val encoded = Base64.getDecoder().decode(SPKI_DER_BASE64)
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(encoded)
            .joinToString("") { "%02x".format(it) }
        require(fingerprint == EXPECTED_SHA256) {
            "Pinned remote config public key fingerprint mismatch"
        }

        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        val ecKey = key as? ECPublicKey
            ?: throw IllegalStateException("Pinned remote config key is not an EC public key")
        require(ecKey.params.curve.field.fieldSize == 256) {
            "Pinned remote config key must use P-256"
        }
        return key
    }

    @JvmStatic
    fun fingerprintSha256(): String = EXPECTED_SHA256
}
