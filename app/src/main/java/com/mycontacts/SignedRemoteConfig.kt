package com.mycontacts

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Verifies a detached ECDSA-SHA256 signature over the exact UTF-8 payload bytes,
 * then parses a deliberately small, non-executable remote configuration schema.
 *
 * The caller must supply the public key pinned by the host application and persist
 * lastAppliedSequence only after the configuration has been atomically applied.
 */
class SignedRemoteConfig private constructor(
    val sequence: Long,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val featureFlags: Map<String, Boolean>,
    val syncPollIntervalSeconds: Int?
) {
    companion object {
        private const val MAX_ENVELOPE_CHARS = 100_000
        private const val MAX_PAYLOAD_BYTES = 65_536
        private const val MAX_LIFETIME_SECONDS = 30L * 24L * 60L * 60L
        private const val CLOCK_SKEW_SECONDS = 300L
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

        @JvmStatic
        fun verify(
            envelopeJson: String,
            pinnedPublicKey: PublicKey,
            nowEpochSeconds: Long,
            lastAppliedSequence: Long
        ): SignedRemoteConfig {
            require(envelopeJson.length <= MAX_ENVELOPE_CHARS) { "Envelope is too large" }
            require(pinnedPublicKey.algorithm.equals("EC", ignoreCase = true)) {
                "Only an EC verification key is accepted"
            }

            val envelope = JSONObject(envelopeJson)
            requireExactKeys(envelope, setOf("payload", "signature"), "envelope")

            val payloadText = envelope.get("payload") as? String
                ?: throw IllegalArgumentException("Envelope payload must be a string")
            val signatureText = envelope.get("signature") as? String
                ?: throw IllegalArgumentException("Envelope signature must be a string")

            val payloadBytes = decodeBase64Url(payloadText, "payload")
            require(payloadBytes.isNotEmpty() && payloadBytes.size <= MAX_PAYLOAD_BYTES) {
                "Payload size is invalid"
            }
            val signatureBytes = decodeBase64Url(signatureText, "signature")
            require(signatureBytes.size in 64..80) { "ECDSA signature length is invalid" }

            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pinnedPublicKey)
            verifier.update(payloadBytes)
            if (!verifier.verify(signatureBytes)) {
                throw SecurityException("Remote configuration signature is invalid")
            }

            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val payloadString = decoder.decode(ByteBuffer.wrap(payloadBytes)).toString()
            val payload = JSONObject(payloadString)
            requireExactKeys(
                payload,
                setOf("schema_version", "sequence", "issued_at", "expires_at", "config"),
                "payload"
            )

            require(readInteger(payload, "schema_version") == 1L) {
                "Unsupported configuration schema"
            }
            val sequence = readInteger(payload, "sequence")
            require(sequence > lastAppliedSequence && sequence > 0L) {
                "Configuration sequence is stale or has already been applied"
            }

            require(nowEpochSeconds >= 0L) { "Current time is invalid" }
            val issuedAt = readInteger(payload, "issued_at")
            val expiresAt = readInteger(payload, "expires_at")
            require(issuedAt >= 0L) { "Configuration issue time is invalid" }
            require(issuedAt <= nowEpochSeconds + CLOCK_SKEW_SECONDS) {
                "Configuration issue time is in the future"
            }
            require(expiresAt > nowEpochSeconds && expiresAt > issuedAt) {
                "Configuration has expired or has an invalid time range"
            }
            require(expiresAt - issuedAt <= MAX_LIFETIME_SECONDS) {
                "Configuration validity period is too long"
            }

            val config = payload.get("config") as? JSONObject
                ?: throw IllegalArgumentException("Config must be an object")
            requireExactKeys(config, setOf("features", "sync"), "config")

            val features = config.opt("features") as? JSONObject
                ?: throw IllegalArgumentException("Config features must be an object")
            requireOnlyKeys(
                features,
                setOf("telegram_match_enabled", "brave_automation_enabled"),
                "features"
            )
            val featureFlags = LinkedHashMap<String, Boolean>()
            for (key in listOf("telegram_match_enabled", "brave_automation_enabled")) {
                if (features.has(key)) {
                    featureFlags[key] = features.get(key) as? Boolean
                        ?: throw IllegalArgumentException("Feature flag $key must be boolean")
                }
            }

            val sync = config.opt("sync") as? JSONObject
                ?: throw IllegalArgumentException("Config sync must be an object")
            requireOnlyKeys(sync, setOf("poll_interval_seconds"), "sync")
            val pollInterval = if (sync.has("poll_interval_seconds")) {
                readInteger(sync, "poll_interval_seconds").also {
                    require(it in 15L..300L) { "Sync poll interval is outside the allowed range" }
                }.toInt()
            } else null

            return SignedRemoteConfig(
                sequence = sequence,
                issuedAtEpochSeconds = issuedAt,
                expiresAtEpochSeconds = expiresAt,
                featureFlags = featureFlags.toMap(),
                syncPollIntervalSeconds = pollInterval
            )
        }

        private fun decodeBase64Url(value: String, field: String): ByteArray {
            require(value.isNotBlank() && value.length <= MAX_ENVELOPE_CHARS) {
                "$field encoding is empty or too large"
            }
            return try {
                Base64.getUrlDecoder().decode(value)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$field is not valid base64url", e)
            }
        }

        private fun readInteger(obj: JSONObject, key: String): Long {
            val value = obj.get(key)
            require(value is Number) { "$key must be an integer" }
            val number = value.toDouble()
            require(number.isFinite() &&
                number >= -MAX_SAFE_INTEGER.toDouble() &&
                number <= MAX_SAFE_INTEGER.toDouble() &&
                number == number.toLong().toDouble()) {
                "$key must be a safe integer"
            }
            return value.toLong()
        }

        private fun requireExactKeys(obj: JSONObject, allowed: Set<String>, label: String) {
            requireOnlyKeys(obj, allowed, label)
            require(obj.length() == allowed.size) { "$label is missing required fields" }
        }

        private fun requireOnlyKeys(obj: JSONObject, allowed: Set<String>, label: String) {
            val iterator = obj.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                require(key in allowed) { "Unknown $label field: $key" }
            }
        }
    }
}
