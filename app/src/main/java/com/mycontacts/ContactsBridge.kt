package com.mycontacts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ContactsBridge(context: Context) {
    companion object {
        const val BASE_URL = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/contacts-bridge"
        const val MCP_URL = "https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/contacts-mcp"
    }

    private val prefs = context.getSharedPreferences("contacts_bridge", Context.MODE_PRIVATE)

    fun token(): String = prefs.getString("device_token", "") ?: ""
    fun saveToken(value: String) { prefs.edit().putString("device_token", value.trim()).apply() }

    fun request(body: JSONObject): JSONObject {
        val token = token()
        require(token.isNotBlank()) { "Сначала укажите токен устройства" }
        val conn = (URL(BASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val json = JSONObject(text)
            if (conn.responseCode !in 200..299) throw IllegalStateException(json.optString("error", "HTTP ${conn.responseCode}"))
            return json
        } finally {
            conn.disconnect()
        }
    }

    @Volatile var lastSyncConflicts: List<String> = emptyList()
        private set

    fun sync(contacts: List<Contact>): Int {
        var total = 0
        val conflicts = LinkedHashSet<String>()
        for (batch in contacts.chunked(1000)) {
            val arr = JSONArray()
            batch.forEach { c ->
                arr.put(JSONObject()
                    .put("id", c.id.toString())
                    .put("name", c.name)
                    .put("phones", JSONArray(c.phones))
                    .put("telegram_id", c.telegramId)
                    .put("telegram_username", c.telegramUsername)
                    .put("accounts", JSONArray().apply {
                        c.accountSources.forEach { account ->
                            put(JSONObject()
                                .put("account_name", account.name ?: JSONObject.NULL)
                                .put("account_type", account.type ?: JSONObject.NULL)
                                .put("label", account.label))
                        }
                    }))
            }
            val response = request(JSONObject().put("action", "sync_contacts").put("contacts", arr))
            total += response.optInt("synced")
            val rows = response.optJSONArray("conflicts") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isNotBlank()) conflicts.add(id)
            }
        }
        // Do not prune snapshots during routine sync: the server is append-only by contact ID.
        lastSyncConflicts = conflicts.toList()
        return total
    }

    fun queueTelegramMatch(): JSONObject = request(JSONObject().put("action", "queue_telegram_match"))

    fun poll(): JSONArray = request(JSONObject().put("action", "poll_commands")).optJSONArray("commands") ?: JSONArray()

    fun complete(id: String, result: JSONObject? = null, error: String? = null) {
        val body = JSONObject().put("action", "complete_command").put("command_id", id)
        if (error != null) body.put("status", "failed").put("error_message", error)
        else body.put("status", "completed").put("result", result ?: JSONObject().put("ok", true))
        request(body)
    }
}