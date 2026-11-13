package com.mycontacts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SyncSummary(
    val attempted: Int = 0, val unique: Int = 0, val inserted: Int = 0,
    val updated: Int = 0, val skipped: Int = 0, val duplicates: Int = 0,
    val conflicts: Map<String, List<String>> = emptyMap(),
    val manifestRecorded: Boolean = false,
    val manifestError: String? = null,
    val syncId: String? = null
) {
    val synced: Int get() = inserted + updated
    fun toJson(): JSONObject = JSONObject().put("attempted", attempted)
        .put("unique_contacts", unique).put("inserted", inserted)
        .put("updated_existing", updated).put("skipped_existing", skipped)
        .put("duplicate_ids_in_batch_removed", duplicates)
        .put("manifest_recorded", manifestRecorded)
        .put("manifest_error", manifestError ?: JSONObject.NULL)
        .put("sync_id", syncId ?: JSONObject.NULL)
        .put("conflicts", JSONArray().apply { conflicts.forEach { (id, fields) ->
            put(JSONObject().put("id", id).put("fields", JSONArray(fields)))
        } })
}
private class BridgeHttpException(val statusCode: Int, message: String) : IllegalStateException(message)

class ContactsBridge(context: Context) {
    companion object {
        const val BASE_URL = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/contacts-bridge"
        const val MCP_URL = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/contacts-mcp"
        const val REMOTE_CONFIG_URL = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/remote-config"
    }

    private val prefs = context.getSharedPreferences("contacts_bridge", Context.MODE_PRIVATE)

    fun token(): String = prefs.getString("device_token", "") ?: ""
    fun saveToken(value: String) { prefs.edit().putString("device_token", value.trim()).apply() }
    fun clearToken() { prefs.edit().remove("device_token").apply() }

    private fun requestWithToken(token: String, body: JSONObject, endpoint: String = BASE_URL): JSONObject {
        require(token.isNotBlank()) { "Токен не указан" }
        // Report the installed package/version on every bridge request. This is
        // diagnostic metadata, not an authorization or signature-trust decision.
        val requestBody = JSONObject(body.toString()).put("_client", JSONObject()
            .put("package_name", BuildConfig.APPLICATION_ID)
            .put("version_name", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("build_type", BuildConfig.BUILD_TYPE))
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            conn.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val json = JSONObject(text)
            if (conn.responseCode !in 200..299) throw BridgeHttpException(conn.responseCode, json.optString("error", "HTTP ${conn.responseCode}"))
            return json
        } finally {
            conn.disconnect()
        }
    }

    fun request(body: JSONObject): JSONObject {
        val current = token()
        require(current.isNotBlank()) { "Сначала привяжите устройство" }
        return try {
            requestWithToken(current, body)
        } catch (e: BridgeHttpException) {
            if (e.statusCode == 401) {
                // A rejected device token must not be retried forever by the foreground poller.
                clearToken()
                throw IllegalStateException("Токен устройства отклонён сервером. Требуется повторная привязка.", e)
            }
            throw e
        }
    }

    /** Exchanges an admin token for a new device token. The admin token is never persisted. */
    fun pairDevice(adminToken: String, deviceName: String = "Android contacts app — refreshed sync"): JSONObject {
        val response = requestWithToken(
            adminToken.trim(),
            JSONObject().put("action", "pair_device").put("device_name", deviceName)
        )
        val issued = response.optString("device_token").trim()
        require(issued.isNotBlank()) { "Сервер не вернул токен устройства" }
        saveToken(issued)
        return response
    }


    /** Fetches the signed remote configuration using the paired device token. */
    fun requestRemoteConfigEnvelope(): JSONObject {
        val current = token()
        require(current.isNotBlank()) { "Сначала привяжите устройство" }
        return try {
            requestWithToken(current, JSONObject(), REMOTE_CONFIG_URL)
        } catch (e: BridgeHttpException) {
            if (e.statusCode == 401) {
                clearToken()
                throw IllegalStateException(
                    "Токен устройства отклонён сервером. Требуется повторная привязка.", e
                )
            }
            throw e
        }
    }

    @Volatile var lastSyncSummary: SyncSummary = SyncSummary()
        private set

    fun sync(contacts: List<Contact>): Int = syncBatches(sequenceOf(contacts)).synced

    fun syncBatches(batches: Sequence<List<Contact>>): SyncSummary {
        var attempted = 0; var unique = 0; var inserted = 0; var updated = 0
        var skipped = 0; var duplicates = 0
        val conflicts = LinkedHashMap<String, LinkedHashSet<String>>()
        val localContactIds = LinkedHashSet<String>()
        val syncId = java.util.UUID.randomUUID().toString()
        for (contacts in batches) for (batch in contacts.chunked(100)) {
            if (batch.isEmpty()) continue
            attempted += batch.size
            batch.forEach { localContactIds.add(it.id.toString()) }
            val arr = JSONArray()
            batch.forEach { c ->
                arr.put(JSONObject()
                    .put("id", c.id.toString()).put("name", c.name)
                    .put("phones", JSONArray(c.phones))
                    .put("telegram_id", c.telegramId).put("telegram_username", c.telegramUsername)
                    .put("fields", JSONArray().apply { c.fields.forEach { field ->
                        put(JSONObject().put("mimetype", field.mimetype).put("values", JSONObject().apply {
                            field.values.toSortedMap().forEach { (key, value) -> put(key, value) }
                        }))
                    } })
                    .put("accounts", JSONArray().apply { c.accountSources.forEach { account ->
                        put(JSONObject().put("account_name", account.name ?: JSONObject.NULL)
                            .put("account_type", account.type ?: JSONObject.NULL).put("label", account.label))
                    } }))
            }
            val response = request(JSONObject().put("action", "sync_contacts").put("contacts", arr))
            unique += response.optInt("unique_contacts", batch.size)
            inserted += response.optInt("inserted"); updated += response.optInt("updated_existing")
            skipped += response.optInt("skipped_existing")
            duplicates += response.optInt("duplicate_ids_in_batch_removed")
            val rows = response.optJSONArray("conflicts") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val id = item.optString("id"); if (id.isBlank()) continue
                val fields = conflicts.getOrPut(id) { LinkedHashSet() }
                val names = item.optJSONArray("fields") ?: JSONArray()
                for (j in 0 until names.length()) names.optString(j).takeIf { it.isNotBlank() }?.let(fields::add)
            }
        }

        var manifestRecorded = false
        var manifestError: String? = null
        try {
            val manifestResponse = request(JSONObject()
                .put("action", "record_sync_manifest")
                .put("sync_id", syncId)
                .put("local_contact_ids", JSONArray(localContactIds))
                .put("sync_summary", JSONObject()
                    .put("attempted", attempted)
                    .put("unique_contacts", unique)
                    .put("inserted", inserted)
                    .put("updated_existing", updated)
                    .put("skipped_existing", skipped)
                    .put("conflicts", conflicts.size)
                    .put("duplicates", duplicates))
                .put("conflict_details", JSONArray().apply {
                    conflicts.forEach { (id, fields) ->
                        put(JSONObject().put("id", id).put("fields", JSONArray(fields)))
                    }
                }))
            manifestRecorded = manifestResponse.optBoolean("recorded", false)
            if (!manifestRecorded) manifestError = "Сервер не подтвердил запись реестра"
        } catch (error: Exception) {
            manifestError = error.message ?: error.javaClass.simpleName
        }

        val summary = SyncSummary(
            attempted, unique, inserted, updated, skipped, duplicates,
            conflicts.mapValues { it.value.toList() }, manifestRecorded, manifestError, syncId
        )
        lastSyncSummary = summary
        return summary
    }
    fun uploadDiagnostics(entries: JSONArray): JSONObject = request(
        JSONObject().put("action", "upload_diagnostics").put("entries", entries)
    )

    fun queueTelegramMatch(): JSONObject = request(JSONObject().put("action", "queue_telegram_match"))

    fun poll(): JSONArray = request(JSONObject().put("action", "poll_commands")).optJSONArray("commands") ?: JSONArray()

    fun complete(id: String, result: JSONObject? = null, error: String? = null) {
        val body = JSONObject().put("action", "complete_command").put("command_id", id)
        if (error != null) body.put("status", "failed").put("error_message", error)
        else body.put("status", "completed").put("result", result ?: JSONObject().put("ok", true))
        request(body)
    }
}