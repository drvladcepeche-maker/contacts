package com.mycontacts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class RemoteConfigStoreTest {
    private val now = 1_800_000_000L
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(256)
    }.generateKeyPair()

    private class MemoryPersistence : RemoteConfigStatePersistence {
        var value: String? = null
        var failWrites = false
        override fun readState(): String? = value
        override fun writeStateAtomically(serializedState: String): Boolean {
            if (failWrites) return false
            value = serializedState
            return true
        }
    }

    private fun verified(sequence: Long, enabled: Boolean, expiresAt: Long = now + 3600): SignedRemoteConfig {
        val payload = JSONObject()
            .put("schema_version", 1)
            .put("sequence", sequence)
            .put("issued_at", now - 30)
            .put("expires_at", expiresAt)
            .put("config", JSONObject()
                .put("features", JSONObject().put("telegram_match_enabled", enabled))
                .put("sync", JSONObject().put("poll_interval_seconds", 45)))
            .toString().toByteArray(StandardCharsets.UTF_8)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(payload)
        val envelope = JSONObject()
            .put("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(payload))
            .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()))
            .toString()
        return SignedRemoteConfig.verify(envelope, keyPair.public, now, 0L)
    }

    @Test
    fun appliesVerifiedSnapshotAndPersistsSequence() {
        val persistence = MemoryPersistence()
        val store = RemoteConfigStore(persistence)
        assertTrue(store.applyVerified(verified(1L, true), now))
        val current = store.current(now)
        assertEquals(1L, current?.sequence)
        assertEquals(true, current?.featureFlags?.get("telegram_match_enabled"))
        assertEquals(1L, store.lastAppliedSequence())
    }

    @Test
    fun rejectsReplayEvenAfterRollback() {
        val store = RemoteConfigStore(MemoryPersistence())
        assertTrue(store.applyVerified(verified(1L, true), now))
        assertTrue(store.applyVerified(verified(2L, false), now))
        assertTrue(store.rollback(now))
        assertEquals(1L, store.current(now)?.sequence)
        assertEquals(2L, store.lastAppliedSequence())
        assertFalse(store.applyVerified(verified(2L, false), now))
    }

    @Test
    fun failedAtomicWriteLeavesPreviousStateUntouched() {
        val persistence = MemoryPersistence()
        val store = RemoteConfigStore(persistence)
        assertTrue(store.applyVerified(verified(1L, true), now))
        val before = persistence.value
        persistence.failWrites = true
        assertFalse(store.applyVerified(verified(2L, false), now))
        assertEquals(before, persistence.value)
        assertEquals(1L, store.current(now)?.sequence)
    }

    @Test
    fun expiredActiveAndPreviousConfigurationsAreNotRestored() {
        val store = RemoteConfigStore(MemoryPersistence())
        assertTrue(store.applyVerified(verified(1L, true, expiresAt = now + 10), now))
        assertTrue(store.applyVerified(verified(2L, false), now))
        assertFalse(store.rollback(now + 11))
        assertNull(store.current(now + 4000))
    }

    @Test
    fun rollbackIsUnavailableWithoutPreviousSnapshot() {
        val store = RemoteConfigStore(MemoryPersistence())
        assertFalse(store.rollback(now))
    }

    @Test
    fun expiredConfigCannotBePersistedAfterItWasVerified() {
        val store = RemoteConfigStore(MemoryPersistence())
        val expired = verified(1L, true, expiresAt = now + 10)
        assertFalse(store.applyVerified(expired, now + 10))
        assertEquals(0L, store.lastAppliedSequence())
    }
}
