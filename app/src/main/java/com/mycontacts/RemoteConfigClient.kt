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
        val verified = SignedRemoteConfig.verify(
            envelopeJson = envelope.toString(),
            pinnedPublicKey = pinnedKey,
            nowEpochSeconds = nowEpochSeconds,
            lastAppliedSequence = store.lastAppliedSequence()
        )
        return store.applyVerified(verified, nowEpochSeconds)
    }

    fun current(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): AppliedRemoteConfig? =
        store.current(nowEpochSeconds)

    fun rollback(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
        store.rollback(nowEpochSeconds)

    fun lastAppliedSequence(): Long = store.lastAppliedSequence()
}
