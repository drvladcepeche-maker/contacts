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
        if (file.exists()) file.readText(Charsets.UTF_8) else "Лог пока пуст.\n"
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
if [ -d "\$HOME/contacts/.git" ]; then
  git -C "\$HOME/contacts" pull --ff-only
else
  git clone https://github.com/drvladcepeche-maker/contacts.git "\$HOME/contacts"
fi
echo
echo "Исходники доступны: \$HOME/contacts"
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
        synchronized(lock) {
            try {
                val file = File(context.applicationContext.filesDir, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val backup = File(context.filesDir, "mycontacts-old.log")
                    backup.delete()
                    file.renameTo(backup)
                }
                file.appendText(buildString {
                    append(formatter.format(Date())).append(" ").append(level).append(" ").append(message).append("\n")
                    if (error != null) {
                        append(android.util.Log.getStackTraceString(error)).append("\n")
                    }
                }, Charsets.UTF_8)
            } catch (_: Exception) {
                // Logging must never crash the application.
            }
        }
    }
}
