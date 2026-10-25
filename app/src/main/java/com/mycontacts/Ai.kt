package com.mycontacts

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Ai {
    const val OPENAI = "openai"
    const val CLAUDE = "claude"

    /** Единая точка входа: provider = "openai" (ChatGPT) или "claude" (Anthropic). */
    fun ask(apiKey: String, model: String, system: String, user: String, provider: String = OPENAI): String =
        if (provider == CLAUDE) askClaude(apiKey, model, system, user) else askOpenAi(apiKey, model, system, user)

    private fun askOpenAi(apiKey: String, model: String, system: String, user: String): String {
        val conn = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer " + apiKey)
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
            body.put("model", model)
            body.put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (e: Exception) { text.take(300) }
                throw Exception("HTTP " + code + ": " + msg)
            }
            return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        } finally {
            conn.disconnect()
        }
    }

    private fun askClaude(apiKey: String, model: String, system: String, user: String): String {
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
            body.put("model", model)
            body.put("max_tokens", 4096)
            body.put("system", system)
            body.put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (e: Exception) { text.take(300) }
                throw Exception("HTTP " + code + ": " + msg)
            }
            val parts = JSONObject(text).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optString("type") == "text") sb.append(p.getString("text"))
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }
}
