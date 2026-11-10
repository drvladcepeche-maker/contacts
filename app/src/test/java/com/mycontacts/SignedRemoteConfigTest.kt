package com.mycontacts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class SignedRemoteConfigTest {
    private val now = 1_800_000_000L
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(256)
    }.generateKeyPair()

    private fun envelope(
        sequence: Long = 4L,
        expiresAt: Long = now + 3600,
        config: JSONObject = JSONObject()
            .put("features", JSONObject()
                .put("telegram_match_enabled", true)
                .put("brave_automation_enabled", false))
            .put("sync", JSONObject().put("poll_interval_seconds", 60))
    ): String {
        val payload = JSONObject()
            .put("schema_version", 1)
            .put("sequence", sequence)
            .put("issued_at", now - 60)
            .put("expires_at", expiresAt)
            .put("config", config)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(payload)
        val signature = signer.sign()
        return JSONObject()
            .put("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(payload))
            .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
            .toString()
    }

    @Test
    fun verifiesValidConfiguration() {
        val result = SignedRemoteConfig.verify(envelope(), keyPair.public, now, 3L)
        assertEquals(4L, result.sequence)
        assertEquals(true, result.featureFlags["telegram_match_enabled"])
        assertEquals(false, result.featureFlags["brave_automation_enabled"])
        assertEquals(60, result.syncPollIntervalSeconds)
    }

    @Test
    fun rejectsModifiedPayload() {
        val original = JSONObject(envelope())
        val payload = String(
            Base64.getUrlDecoder().decode(original.getString("payload")),
            StandardCharsets.UTF_8
        ).replace("\"sequence\":4", "\"sequence\":5")
        val tampered = JSONObject()
            .put("payload", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.toByteArray(StandardCharsets.UTF_8)))
            .put("signature", original.getString("signature"))
            .toString()

        assertThrows(SecurityException::class.java) {
            SignedRemoteConfig.verify(tampered, keyPair.public, now, 0L)
        }
    }

    @Test
    fun rejectsReplayAndExpiredConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            SignedRemoteConfig.verify(envelope(sequence = 4L), keyPair.public, now, 4L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SignedRemoteConfig.verify(envelope(expiresAt = now - 1), keyPair.public, now, 0L)
        }
    }

    @Test
    fun rejectsUnknownConfigurationFields() {
        val invalidConfig = JSONObject()
            .put("features", JSONObject().put("run_shell", true))
            .put("sync", JSONObject())
        assertThrows(IllegalArgumentException::class.java) {
            SignedRemoteConfig.verify(
                envelope(config = invalidConfig), keyPair.public, now, 0L
            )
        }
    }

    @Test
    fun rejectsOutOfRangePollInterval() {
        val invalidConfig = JSONObject()
            .put("features", JSONObject())
            .put("sync", JSONObject().put("poll_interval_seconds", 5))
        assertThrows(IllegalArgumentException::class.java) {
            SignedRemoteConfig.verify(
                envelope(config = invalidConfig), keyPair.public, now, 0L
            )
        }
    }
}
