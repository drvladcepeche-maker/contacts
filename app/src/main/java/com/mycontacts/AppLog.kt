package com.mycontacts

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App-owned diagnostics. Android system logcat is not accessible to ordinary apps without special privileges. */
object AppLog {
    private const val FILE_NAME = "mycontacts.log"
    private const val MAX_BYTES = 1024 * 1024
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write(app, "FATAL", "Uncaught exception on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        info(app, "AppLog", "Crash handler installed; versionCode=${app.packageManager.getPackageInfo(app.packageName, 0).versionCode}")
    }

    fun info(context: Context, tag: String, message: String) = write(context, "INFO", tag + ": " + message, null)
    fun warn(context: Context, tag: String, message: String, error: Throwable? = null) = write(context, "WARN", tag + ": " + message, error)
    fun error(context: Context, tag: String, message: String, error: Throwable? = null) = write(context, "ERROR", tag + ": " + message, error)

    fun read(context: Context): String = synchronized(lock) {
        val file = File(context.filesDir, FILE_NAME)
        val body = if (file.exists()) file.readText(Charsets.UTF_8) else "Лог пока пуст.\n"
        val prefs = context.getSharedPreferences("mycontacts_diagnostics", Context.MODE_PRIVATE)
        val pending = try { org.json.JSONArray(prefs.getString("pending", "[]")).length() } catch (_: Exception) { -1 }
        val lastError = prefs.getString("last_upload_error", "").orEmpty()
        body + buildString {
            append("\n--- Отправка диагностики ---\n")
            append("Ожидают отправки: ").append(pending).append("\n")
            append("Последняя успешная отправка: ").append(prefs.getString("last_upload_at", "нет")).append("\n")
            append("Последняя отправка: принято ").append(prefs.getInt("last_upload_received", 0))
                .append(", записано ").append(prefs.getInt("last_upload_inserted", 0)).append("\n")
            if (lastError.isNotBlank()) append("Последняя ошибка отправки: ").append(redact(lastError)).append("\n")
        }
    }

    fun exportToDownloads(context: Context): String {
        val content = read(context)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "MyContacts-log-$stamp.txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MyContacts")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Android не создал файл в папке Загрузки")
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("Не удалось открыть файл лога для записи")
            return "Download/MyContacts/$name"
        }
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MyContacts")
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Не удалось создать папку Загрузки/MyContacts")
        File(directory, name).writeText(content, Charsets.UTF_8)
        return "Download/MyContacts/$name"
    }

    fun exportTermuxGuide(context: Context): String {
        val guide = """
            Мои контакты — редактирование исходников через Termux

            Важно: Termux редактирует ИСХОДНЫЙ КОД из GitHub, а не код уже установленного APK.
            Чтобы получить APK, сохраните изменения и отправьте их в GitHub; GitHub Actions соберёт пакет.

            1. Установите Termux из официального источника проекта.
            2. Откройте Termux и выполните:
               pkg update
               pkg install git nano
               git clone https://github.com/drvladcepeche-maker/contacts.git
               cd contacts
            3. Откройте файл для редактирования, например:
               nano app/src/main/java/com/mycontacts/MainActivity.kt
            4. Сохраните файл, затем:
               git status
               git add app
               git commit -m "Update MyContacts"
               git push
            5. В GitHub откройте Actions и скачайте APK из успешной сборки.

            Для git push потребуется авторизация GitHub (обычно Personal Access Token вместо пароля).
            Не сохраняйте токены, API-ключи или пароли в исходниках и не отправляйте их в чат.

            Обновление APK поверх установленного требует того же applicationId и той же подписи.
            Если сертификат подписи отличается, Android выдаст INSTALL_FAILED_UPDATE_INCOMPATIBLE.
            Не удаляйте установленное приложение, пока не проверите резервную копию данных.
        """.trimIndent() + "\n"
        val name = "MyContacts-Termux-guide.txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MyContacts")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Android не создал файл инструкции")
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(guide.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("Не удалось записать инструкцию")
        } else {
            @Suppress("DEPRECATION")
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MyContacts")
            if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Не удалось создать папку")
            File(directory, name).writeText(guide, Charsets.UTF_8)
        }
        return "Download/MyContacts/$name"
    }


    fun exportTermuxSetupScript(context: Context): String {
        val script = """#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
pkg update -y
pkg install -y git nano
if [ -d "${'$'}HOME/contacts/.git" ]; then
  git -C "${'$'}HOME/contacts" pull --ff-only
else
  git clone https://github.com/drvladcepeche-maker/contacts.git "${'$'}HOME/contacts"
fi
echo
echo "Исходники доступны: ${'$'}HOME/contacts"
echo "Редактируйте Kotlin-файлы в app/src/main/java/com/mycontacts/"
echo "Изменения APK собираются через GitHub Actions после отправки commit в GitHub."
"""
        val name = "MyContacts-Termux-setup.sh"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/x-shellscript")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MyContacts")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Android не создал setup-скрипт")
            context.contentResolver.openOutputStream(uri, "w")?.use { it.write(script.toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("Не удалось записать setup-скрипт")
        } else {
            @Suppress("DEPRECATION")
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MyContacts")
            if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("Не удалось создать папку")
            File(directory, name).writeText(script, Charsets.UTF_8)
        }
        return "Download/MyContacts/$name"
    }

    private val uploadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var lastFlushScheduledAt = 0L

    /** Retry persisted diagnostic events, throttled to avoid repeated network requests. */
    fun flush(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFlushScheduledAt < 30_000L) return
        lastFlushScheduledAt = now
        val app = context.applicationContext
        uploadExecutor.execute {
            try {
                val prefs = app.getSharedPreferences("mycontacts_diagnostics", Context.MODE_PRIVATE)
                val batch = synchronized(lock) {
                    val current = try { org.json.JSONArray(prefs.getString("pending", "[]")) }
                    catch (_: Exception) { org.json.JSONArray() }
                    val sanitized = org.json.JSONArray()
                    for (i in 0 until current.length()) {
                        val item = current.optJSONObject(i) ?: continue
                        sanitized.put(org.json.JSONObject()
                            .put("event_id", item.optString("event_id"))
                            .put("occurred_at", item.optString("occurred_at"))
                            .put("level", item.optString("level", "ERROR"))
                            .put("tag", redact(item.optString("tag")))
                            .put("message", redact(item.optString("message")).take(1000))
                            .put("stack_trace", org.json.JSONObject.NULL)
                            .put("app_version", item.optString("app_version")))
                    }
                    prefs.edit().putString("pending", sanitized.toString()).commit()
                    org.json.JSONArray().also { out ->
                        for (i in 0 until minOf(sanitized.length(), 50)) {
                            sanitized.optJSONObject(i)?.let { out.put(org.json.JSONObject(it.toString())) }
                        }
                    }
                }
                if (batch.length() == 0) return@execute
                val response = ContactsBridge(app).request(
                    org.json.JSONObject().put("action", "upload_diagnostics").put("entries", batch)
                )
                val received = response.optInt("received", -1)
                if (received < 0) {
                    val message = "Diagnostics upload rejected by server"
                    prefs.edit().putString("last_upload_error", message).apply()
                    android.util.Log.e("AppLog", message)
                    lastFlushScheduledAt = 0L
                    return@execute
                }
                val sent = HashSet<String>()
                for (i in 0 until batch.length()) sent.add(batch.optJSONObject(i)?.optString("event_id") ?: "")
                synchronized(lock) {
                    val current = try { org.json.JSONArray(prefs.getString("pending", "[]")) }
                    catch (_: Exception) { org.json.JSONArray() }
                    val remaining = org.json.JSONArray()
                    for (i in 0 until current.length()) {
                        val item = current.optJSONObject(i) ?: continue
                        if (!sent.contains(item.optString("event_id"))) remaining.put(item)
                    }
                    prefs.edit().putString("pending", remaining.toString()).commit()
                }
                prefs.edit()
                    .putString("last_upload_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.format(Date()))
                    .putInt("last_upload_received", received)
                    .putInt("last_upload_inserted", response.optInt("inserted", 0))
                    .putString("last_upload_error", "")
                    .apply()
            } catch (e: Exception) {
                // Keep the queue intact; only the exception class is retained for privacy.
                // Retry on the next flush.
                val message = "Diagnostics upload failed (${e.javaClass.simpleName})"
                app.getSharedPreferences("mycontacts_diagnostics", Context.MODE_PRIVATE)
                    .edit().putString("last_upload_error", message).apply()
                android.util.Log.e("AppLog", message)
                lastFlushScheduledAt = 0L
            }
        }
    }

    private fun redact(value: String): String {
        var safe = value
        safe = safe.replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*"), "Bearer [REDACTED]")
        safe = Regex("(?i)(token|password|secret|api[_-]?key|access[_-]?token)(\\s*[:=]\\s*)[^\\s,;]+").replace(safe) {
            it.groupValues[1] + it.groupValues[2] + "[REDACTED]"
        }
        safe = Regex("(?i)\\b(command|job|device|contact|local)[_-]?id\\b(\\s*[:=]\\s*|\\s+)[^\\s,;]+").replace(safe) {
            it.groupValues[1] + "_id=[REDACTED]"
        }
        safe = safe.replace(Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b"), "[ID]")
        safe = safe.replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]")
        safe = safe.replace(Regex("\\+?\\d[\\d ()-]{7,}\\d"), "[PHONE]")
        safe = safe.replace(Regex("\\b[A-Fa-f0-9]{32,}\\b"), "[TOKEN]")
        return safe.take(12000)
    }

    private fun enqueueDiagnostic(context: Context, level: String, message: String, error: Throwable?) {
        try {
            val app = context.applicationContext
            val exceptionType = error?.javaClass?.simpleName
            val diagnosticMessage = if (exceptionType.isNullOrBlank()) message else message + " [exception=" + exceptionType + "]"
            val event = org.json.JSONObject()
                .put("event_id", java.util.UUID.randomUUID().toString())
                .put("occurred_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date()))
                .put("level", level)
                .put("tag", redact(message.substringBefore(":").take(120)))
                .put("message", redact(diagnosticMessage.substringAfter(": ", diagnosticMessage).take(1000)))
                .put("stack_trace", org.json.JSONObject.NULL)
                .put("app_version", BuildConfig.VERSION_NAME)
            val prefs = app.getSharedPreferences("mycontacts_diagnostics", Context.MODE_PRIVATE)
            synchronized(lock) {
                val current = try { org.json.JSONArray(prefs.getString("pending", "[]")) }
                catch (_: Exception) { org.json.JSONArray() }
                current.put(event)
                val trimmed = org.json.JSONArray()
                for (i in maxOf(0, current.length() - 200) until current.length()) trimmed.put(current.getJSONObject(i))
                prefs.edit().putString("pending", trimmed.toString()).commit()
            }
            if (level != "INFO") flush(app, force = true)
        } catch (_: Exception) {
            // Diagnostics must never interrupt the application.
        }
    }

    private fun write(context: Context, level: String, message: String, error: Throwable?) {
        synchronized(lock) {
            try {
                val file = File(context.applicationContext.filesDir, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val backup = File(context.filesDir, "mycontacts-old.log")
                    backup.delete()
                    file.renameTo(backup)
                }
                val safeMessage = redact(message)
                val exceptionType = error?.javaClass?.simpleName
                file.appendText(buildString {
                    append(formatter.format(Date())).append(" ").append(level).append(" ").append(safeMessage)
                    if (!exceptionType.isNullOrBlank()) append(" [exception=").append(exceptionType).append("]")
                    append("\n")
                }, Charsets.UTF_8)
            } catch (_: Exception) {
                // Logging must never crash the application.
            }
        }
        enqueueDiagnostic(context, level, redact(message), error)
    }
}
