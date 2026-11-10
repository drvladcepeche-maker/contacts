package com.mycontacts

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App-owned diagnostics. Android system logcat is not accessible to ordinary apps without special privileges. */
object AppLog {
    private const val FILE_NAME = "mycontacts.log"
    private const val DIAGNOSTICS_FILE_NAME = "mycontacts-diagnostics.jsonl"
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
        if (file.exists()) file.readText(Charsets.UTF_8) else "Лог пока пуст.\n"
    }

    /** Recent app-owned logs only; redact contact identifiers and credentials before remote sharing. */
    fun readForDiagnostics(context: Context, maxLines: Int = 160): String {
        val recent = read(context).lineSequence().toList().takeLast(maxLines).joinToString("\n")
        val redacted = recent
            .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~-]{12,}"), "Bearer [REDACTED]")
            .replace(Regex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", RegexOption.IGNORE_CASE), "[EMAIL]")
            .replace(Regex("(?<!\\w)\\+?\\d[\\d ()-]{7,}\\d(?!\\w)"), "[PHONE]")
            .replace(Regex("\\b[0-9a-fA-F]{48,}\\b"), "[TOKEN]")
        return redacted.takeLast(16000)
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

    private fun write(context: Context, level: String, message: String, error: Throwable?) {
        val app = context.applicationContext
        var shouldUpload = false
        synchronized(lock) {
            try {
                val file = File(app.filesDir, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val backup = File(app.filesDir, "mycontacts-old.log")
                    backup.delete()
                    file.renameTo(backup)
                }
                val stack = error?.let { android.util.Log.getStackTraceString(it) }.orEmpty()
                file.appendText(buildString {
                    append(formatter.format(Date())).append(" ").append(level).append(" ").append(message).append("\n")
                    if (stack.isNotBlank()) append(stack).append("\n")
                }, Charsets.UTF_8)

                // Keep a durable structured queue for remote diagnostics. INFO is included
                // for updater stages; routine INFO messages from other components stay local.
                val remote = level in setOf("WARN", "ERROR", "FATAL") ||
                    (level == "INFO" && (
                        message.startsWith("ApkUpdater:") ||
                        message.startsWith("TelegramResolver:")
                    ))
                if (remote) {
                    val diagnostics = File(app.filesDir, DIAGNOSTICS_FILE_NAME)
                    if (diagnostics.exists() && diagnostics.length() > 2L * 1024L * 1024L) {
                        val retained = diagnostics.readLines(Charsets.UTF_8).takeLast(400)
                        diagnostics.writeText(retained.joinToString("\n", postfix = if (retained.isEmpty()) "" else "\n"), Charsets.UTF_8)
                    }
                    val packageInfo = try { app.packageManager.getPackageInfo(app.packageName, 0) } catch (_: Exception) { null }
                    val safeMessage = sanitizeDiagnostic(message).take(2000)
                    val safeStack = sanitizeDiagnostic(stack).take(12000)
                    val event = JSONObject()
                        .put("event_id", UUID.randomUUID().toString())
                        .put("occurred_at", Instant.now().toString())
                        .put("level", level)
                        .put("tag", sanitizeDiagnostic(message.substringBefore(":")).take(120))
                        .put("message", safeMessage)
                        .put("stack_trace", safeStack.ifBlank { JSONObject.NULL })
                        .put("app_version", packageInfo?.versionName ?: BuildConfig.VERSION_NAME)
                    diagnostics.appendText(event.toString() + "\n", Charsets.UTF_8)
                    shouldUpload = true
                }
            } catch (_: Exception) {
                // Logging must never crash the application.
            }
        }
        if (shouldUpload) DiagnosticsSync.flush(app)
    }

    private fun sanitizeDiagnostic(value: String): String = value
        .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~-]{12,}"), "Bearer [REDACTED]")
        .replace(Regex("(?i)(token|api[_-]?key|password)=([^&\\s]+)"), "\$1=[REDACTED]")
        .replace(Regex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", RegexOption.IGNORE_CASE), "[EMAIL]")
        .replace(Regex("(?<!\\w)\\+?\\d[\\d ()-]{7,}\\d(?!\\w)"), "[PHONE]")
        .replace(Regex("\\b[0-9a-fA-F]{48,}\\b"), "[TOKEN]")

    fun pendingRemoteEntries(context: Context, limit: Int = 50): JSONArray = synchronized(lock) {
        val file = File(context.applicationContext.filesDir, DIAGNOSTICS_FILE_NAME)
        val result = JSONArray()
        if (!file.exists()) return@synchronized result
        file.readLines(Charsets.UTF_8).takeLast(500).forEach { line ->
            if (result.length() >= limit) return@forEach
            try { result.put(JSONObject(line)) } catch (_: Exception) { }
        }
        result
    }

    fun markRemoteUploaded(context: Context, eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        synchronized(lock) {
            try {
                val file = File(context.applicationContext.filesDir, DIAGNOSTICS_FILE_NAME)
                if (!file.exists()) return
                val remaining = file.readLines(Charsets.UTF_8).filter { line ->
                    try { JSONObject(line).optString("event_id") !in eventIds } catch (_: Exception) { true }
                }
                val temp = File(context.applicationContext.filesDir, "$DIAGNOSTICS_FILE_NAME.tmp")
                temp.writeText(remaining.joinToString("\n", postfix = if (remaining.isEmpty()) "" else "\n"), Charsets.UTF_8)
                if (!temp.renameTo(file)) {
                    file.writeText(remaining.joinToString("\n", postfix = if (remaining.isEmpty()) "" else "\n"), Charsets.UTF_8)
                    temp.delete()
                }
            } catch (_: Exception) { }
        }
    }
}

object DiagnosticsSync {
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun registerNetworkCallback(context: Context) {
        if (networkCallback != null) return
        val app = context.applicationContext
        try {
            val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    flush(app)
                }
            }
            synchronized(this) {
                if (networkCallback == null) {
                    manager.registerDefaultNetworkCallback(callback)
                    networkCallback = callback
                }
            }
        } catch (_: Exception) {
            // Foreground retries still work if Android refuses callback registration.
        }
    }

    fun flush(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            try {
                val bridge = ContactsBridge(app)
                if (bridge.token().isBlank()) return@execute
                // Drain the bounded local queue in batches. Failed requests leave all
                // entries on disk; the next resume or new error retries them.
                while (true) {
                    val entries = AppLog.pendingRemoteEntries(app, 50)
                    if (entries.length() == 0) break
                    val response = bridge.uploadDiagnostics(entries)
                    if (response.optInt("rejected", 0) != 0 ||
                        response.optInt("received", 0) < entries.length()) break
                    val ids = (0 until entries.length()).mapNotNull { i ->
                        entries.optJSONObject(i)?.optString("event_id")?.takeIf { it.isNotBlank() }
                    }.toSet()
                    if (ids.isEmpty()) break
                    AppLog.markRemoteUploaded(app, ids)
                }
            } catch (_: Exception) {
                // Keep the queue for the next foreground/resume or subsequent event.
            } finally {
                running.set(false)
            }
        }
    }
}
