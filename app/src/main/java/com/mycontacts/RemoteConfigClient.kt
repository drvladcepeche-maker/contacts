package com.mycontacts

import android.content.Context
import org.json.JSONObject

/**
 * Explicit remote-config sync entry point. It is intentionally not scheduled or
 * invoked by the foreground poller until server key provisioning is complete.
 */
class RemoteConfigClient(context: Context) {
    private val bridge = ContactsBridge(context.applicationContext)
    private val store = RemoteConfigStore(
        SharedPreferencesRemoteConfigPersistence(context.applicationContext)
    )
    private val pinnedKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteConfigPublicKey.load()
    }

    fun fetchAndApply(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        require(nowEpochSeconds >= 0L) { "Current time is invalid" }
        val envelope: JSONObject = bridge.requestRemoteConfigEnvelope()
        val highWatermark = store.lastAppliedSequence()
        // Verify authenticity and freshness first, then let the durable store
        // distinguish an idempotent repeat of the active sequence from a stale replay.
        val verified = SignedRemoteConfig.verify(
            envelopeJson = envelope.toString(),
            pinnedPublicKey = pinnedKey,
            nowEpochSeconds = nowEpochSeconds,
            lastAppliedSequence = 0L
        )
        if (verified.sequence == highWatermark) {
            val active = store.current(nowEpochSeconds)
            if (active?.sequence == verified.sequence) return false
            throw IllegalStateException(
                "Remote config sequence was already accepted but no matching active snapshot exists"
            )
        }
        if (verified.sequence < highWatermark) {
            throw IllegalArgumentException("Remote config sequence is older than the accepted high-watermark")
        }
        return store.applyVerified(verified, nowEpochSeconds)
    }

    fun current(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): AppliedRemoteConfig? =
        store.current(nowEpochSeconds)

    fun rollback(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
        store.rollback(nowEpochSeconds)

    fun lastAppliedSequence(): Long = store.lastAppliedSequence()
}
