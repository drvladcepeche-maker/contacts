package com.mycontacts

import android.app.Activity
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Verified, Shizuku-backed in-place updater for this exact application package. */
object ApkUpdater {
    private const val RELEASE_API =
        "https://api.github.com/repos/drvladcepeche-maker/contacts/releases/tags/app-latest"
    private const val APK_ASSET = "moi-kontakty.apk"
    private const val HASH_ASSET = "moi-kontakty.apk.sha256"

    fun checkAndInstall(activity: Activity) {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(activity, "Сначала запустите Shizuku", Toast.LENGTH_LONG).show()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, "Сначала выдайте приложению разрешение Shizuku", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(activity, "Проверяю подписанное обновление…", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val release = JSONObject(downloadText(RELEASE_API))
                val assets = release.optJSONArray("assets")
                    ?: throw IllegalStateException("В релизе нет списка файлов")
                var apkUrl: String? = null
                var hashUrl: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    when (asset.optString("name")) {
                        APK_ASSET -> apkUrl = asset.optString("browser_download_url")
                        HASH_ASSET -> hashUrl = asset.optString("browser_download_url")
                    }
                }
                val downloadUrl = apkUrl ?: throw IllegalStateException("APK ещё не опубликован")
                val checksumUrl = hashUrl ?: throw IllegalStateException("Контрольная сумма APK отсутствует")
                val dir = File(activity.cacheDir, "verified-updates")
                if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Не удалось создать каталог обновления")
                val apk = File(dir, APK_ASSET)
                download(downloadUrl, apk)
                val expectedHash = downloadText(checksumUrl).trim().substringBefore(' ').lowercase()
                require(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "Некорректная SHA-256 контрольная сумма" }
                require(sha256(apk.readBytes()) == expectedHash) { "Контрольная сумма APK не совпадает; установка отменена" }

                val archive = activity.packageManager.getPackageArchiveInfo(
                    apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES
                ) ?: throw IllegalStateException("Android не распознал APK")
                require(archive.packageName == activity.packageName) {
                    "Пакет обновления не совпадает с установленным приложением"
                }
                val installed = activity.packageManager.getPackageInfo(
                    activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES
                )
                val archiveSigners = signerDigests(archive)
                val installedSigners = signerDigests(installed)
                require(archiveSigners.isNotEmpty() && archiveSigners == installedSigners) {
                    "Подпись APK отличается от установленной. Обновление заблокировано; данные не затронуты."
                }
                val newVersion = versionCode(archive)
                val oldVersion = versionCode(installed)
                if (newVersion <= oldVersion) {
                    throw IllegalStateException(
                        if (newVersion == oldVersion) "Установлена последняя версия (${archive.versionName})"
                        else "Опубликованная версия старее установленной; установка отменена"
                    )
                }

                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "Подпись и целостность подтверждены. Устанавливаю версию ${archive.versionName}…",
                        Toast.LENGTH_LONG
                    ).show()
                }
                val command = "pm install -r --user 0 ${shellQuote(apk.absolutePath)}"
                val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
                process.outputStream.close()
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                val exit = process.waitFor()
                if (exit != 0 || !stdout.contains("Success")) {
                    throw IllegalStateException(
                        "Android не подтвердил установку (код $exit): " + (stderr.ifBlank { stdout }).take(500)
                    )
                }
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        "Обновление установлено. Закройте и снова откройте приложение.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                activity.runOnUiThread {
                    Toast.makeText(activity, "Обновление не установлено: $message", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun downloadText(url: String): String {
        val connection = open(url)
        try {
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, destination: File) {
        val connection = open(url)
        try {
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 100L * 1024L * 1024L) { "APK превышает допустимый размер 100 МБ" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MyContacts-Android-Updater")
            setRequestProperty("Accept", "application/vnd.github+json")
            connect()
            if (responseCode !in 200..299) {
                val code = responseCode
                disconnect()
                throw IllegalStateException("GitHub вернул HTTP $code")
            }
        }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet()
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
