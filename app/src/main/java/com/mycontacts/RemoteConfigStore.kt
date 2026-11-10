package com.mycontacts

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * One-key persistence contract: implementations must replace the complete state
 * atomically, never update active/previous/high-watermark independently.
 */
interface RemoteConfigStatePersistence {
    fun readState(): String?
    fun writeStateAtomically(serializedState: String): Boolean
}

/** SharedPreferences stores the whole state in one value and commits synchronously. */
class SharedPreferencesRemoteConfigPersistence(context: Context) : RemoteConfigStatePersistence {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun readState(): String? = preferences.getString(STATE_KEY, null)

    override fun writeStateAtomically(serializedState: String): Boolean =
        preferences.edit().putString(STATE_KEY, serializedState).commit()

    private companion object {
        const val PREFERENCES_NAME = "signed_remote_config"
        const val STATE_KEY = "state_v1"
    }
}

data class AppliedRemoteConfig(
    val sequence: Long,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val featureFlags: Map<String, Boolean>,
    val syncPollIntervalSeconds: Int?
)

/**
 * Persists only already-verified configurations. Active state, last-known-good
 * state and replay-protection high-watermark are encoded in one JSON value.
 */
class RemoteConfigStore(private val persistence: RemoteConfigStatePersistence) {

    fun current(nowEpochSeconds: Long): AppliedRemoteConfig? {
        require(nowEpochSeconds >= 0L) { "Current time is invalid" }
        val state = readState()
        val active = state.optJSONObject("active") ?: return null
        val parsed = decodeConfig(active)
        return parsed.takeIf { it.expiresAtEpochSeconds > nowEpochSeconds }
    }

    /**
     * Returns false for replay/stale sequence or failed durable write.
     * The previous active configuration remains available for one-step rollback.
     */
    fun applyVerified(config: SignedRemoteConfig): Boolean {
        val state = readState()
        val highWatermark = state.optLong("highest_sequence", 0L)
        if (config.sequence <= highWatermark) return false

        val previousActive = state.optJSONObject("active")
        val nextState = JSONObject()
            .put("schema_version", STORAGE_SCHEMA_VERSION)
            .put("highest_sequence", config.sequence)
            .put("active", encodeConfig(config))
            .put("previous", previousActive ?: JSONObject.NULL)

        return persistence.writeStateAtomically(nextState.toString())
    }

    /**
     * Restores the previous configuration if it is still valid. The high-watermark
     * is deliberately not rolled back, preventing replay after rollback.
     */
    fun rollback(nowEpochSeconds: Long): Boolean {
        require(nowEpochSeconds >= 0L) { "Current time is invalid" }
        val state = readState()
        val previous = state.optJSONObject("previous") ?: return false
        if (previous.optLong("expires_at", 0L) <= nowEpochSeconds) return false

        val nextState = JSONObject()
            .put("schema_version", STORAGE_SCHEMA_VERSION)
            .put("highest_sequence", state.optLong("highest_sequence", 0L))
            .put("active", previous)
            .put("previous", JSONObject.NULL)

        return persistence.writeStateAtomically(nextState.toString())
    }

    fun lastAppliedSequence(): Long =
        readState().optLong("highest_sequence", 0L)

    private fun readState(): JSONObject {
        val raw = persistence.readState() ?: return emptyState()
        val state = try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Stored remote configuration state is corrupt", error)
        }
        require(state.optInt("schema_version", -1) == STORAGE_SCHEMA_VERSION) {
            "Stored remote configuration schema is unsupported"
        }
        require(state.optLong("highest_sequence", -1L) >= 0L) {
            "Stored remote configuration sequence is invalid"
        }
        return state
    }

    private fun emptyState() = JSONObject()
        .put("schema_version", STORAGE_SCHEMA_VERSION)
        .put("highest_sequence", 0L)
        .put("active", JSONObject.NULL)
        .put("previous", JSONObject.NULL)

    private fun encodeConfig(config: SignedRemoteConfig): JSONObject {
        val flags = JSONObject()
        config.featureFlags.toSortedMap().forEach { (key, value) -> flags.put(key, value) }
        return JSONObject()
            .put("sequence", config.sequence)
            .put("issued_at", config.issuedAtEpochSeconds)
            .put("expires_at", config.expiresAtEpochSeconds)
            .put("features", flags)
            .put("poll_interval_seconds", config.syncPollIntervalSeconds ?: JSONObject.NULL)
    }

    private fun decodeConfig(json: JSONObject): AppliedRemoteConfig {
        val flagsJson = json.optJSONObject("features")
            ?: throw IllegalStateException("Stored feature flags are invalid")
        val flags = LinkedHashMap<String, Boolean>()
        val keys = flagsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            flags[key] = flagsJson.get(key) as? Boolean
                ?: throw IllegalStateException("Stored feature flag is invalid: $key")
        }
        val intervalValue = json.opt("poll_interval_seconds")
        val interval = if (intervalValue == null || intervalValue == JSONObject.NULL) {
            null
        } else {
            (intervalValue as? Number)?.toInt()
                ?: throw IllegalStateException("Stored poll interval is invalid")
        }
        return AppliedRemoteConfig(
            sequence = json.optLong("sequence", -1L),
            issuedAtEpochSeconds = json.optLong("issued_at", -1L),
            expiresAtEpochSeconds = json.optLong("expires_at", -1L),
            featureFlags = flags.toMap(),
            syncPollIntervalSeconds = interval
        )
    }

    private companion object {
        const val STORAGE_SCHEMA_VERSION = 1
    }
}
