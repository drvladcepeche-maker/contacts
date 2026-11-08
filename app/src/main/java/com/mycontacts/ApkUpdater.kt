package com.mycontacts

import android.app.Activity
import android.content.ContentValues
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Verified, Shizuku-backed in-place updater for this exact application package. */
object ApkUpdater {
    private const val RELEASES_API =
        "https://api.github.com/repos/drvladcepeche-maker/contacts/releases?per_page=100"
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
                val releases = JSONArray(downloadText(RELEASES_API))
                var release: JSONObject? = null
                var newestPublishedAt = ""
                for (i in 0 until releases.length()) {
                    val candidate = releases.optJSONObject(i) ?: continue
                    if (!candidate.optBoolean("prerelease") || candidate.optBoolean("draft")) continue
                    if (!candidate.optString("tag_name").startsWith("mycontacts-app-")) continue
                    val candidateAssets = candidate.optJSONArray("assets") ?: continue
                    var hasApkAsset = false
                    var hasHashAsset = false
                    for (assetIndex in 0 until candidateAssets.length()) {
                        val assetName = candidateAssets.optJSONObject(assetIndex)?.optString("name")
                        if (assetName == APK_ASSET) hasApkAsset = true
                        if (assetName == HASH_ASSET) hasHashAsset = true
                    }
                    if (!hasApkAsset || !hasHashAsset) continue
                    val publishedAt = candidate.optString("published_at", candidate.optString("created_at"))
                    if (publishedAt > newestPublishedAt) {
                        newestPublishedAt = publishedAt
                        release = candidate
                    }
                }
                val latestRelease = release ?: throw IllegalStateException("Подписанный релиз приложения ещё не опубликован")
                val assets = latestRelease.optJSONArray("assets")
                    ?: throw IllegalStateException("В релизе нет списка файлов")
                var apkUrl: String? = null
                var hashUrl: String? = null
                var releaseDigest: String? = null
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    when (asset.optString("name")) {
                        APK_ASSET -> {
                            apkUrl = asset.optString("browser_download_url")
                            releaseDigest = asset.optString("digest").takeIf {
                                it.startsWith("sha256:")
                            }?.removePrefix("sha256:")
                        }
                        HASH_ASSET -> hashUrl = asset.optString("browser_download_url")
                    }
                }
                val downloadUrl = apkUrl ?: throw IllegalStateException("APK ещё не опубликован")
                val dir = File(activity.cacheDir, "verified-updates")
                if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Не удалось создать каталог обновления")
                val apk = File(dir, APK_ASSET)
                download(downloadUrl, apk)
                // Prefer GitHub's SHA-256 digest for the exact uploaded APK asset.
                // The sidecar file remains a compatibility fallback for older releases.
                val expectedHash = (releaseDigest ?: run {
                    val checksumUrl = hashUrl
                        ?: throw IllegalStateException("Контрольная сумма APK отсутствует")
                    downloadText(checksumUrl).trim().substringBefore(' ').lowercase()
                }).lowercase()
                require(expectedHash.matches(Regex("[0-9a-f]{64}"))) {
                    "GitHub не вернул корректную SHA-256 контрольную сумму"
                }
                val actualHash = sha256(apk)
                require(actualHash == expectedHash) {
                    "SHA-256 не совпала (APK ${actualHash.take(12)}, релиз ${expectedHash.take(12)}). Загрузка отменена."
                }

                @Suppress("DEPRECATION")
                val packageFlags = if (Build.VERSION.SDK_INT >= 28) {
                    PackageManager.GET_SIGNING_CERTIFICATES
                } else {
                    PackageManager.GET_SIGNATURES
                }
                val archive = activity.packageManager.getPackageArchiveInfo(
                    apk.absolutePath, packageFlags
                ) ?: throw IllegalStateException("Android не распознал APK")
                require(archive.packageName == activity.packageName) {
                    "Пакет обновления не совпадает с установленным приложением"
                }
                val installed = activity.packageManager.getPackageInfo(
                    activity.packageName, packageFlags
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
                val installFile = publishToDownloads(activity, apk)
                val installResult = installViaUserService(activity, installFile, expectedHash)
                if (!installResult.contains("Success")) {
                    throw IllegalStateException("Android не подтвердил установку: " + installResult.take(500))
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

    /**
     * GitHub access from mobile networks can briefly fail during DNS resolution.
     * Retry only transient name-resolution and socket-timeout failures; never weaken
     * checksum, package, version, or signing-certificate verification.
     */
    private fun <T> retryNetwork(url: String, action: () -> T): T {
        var lastError: Exception? = null
        for (attempt in 0..3) {
            try {
                return action()
            } catch (error: UnknownHostException) {
                lastError = error
                if (attempt == 3) {
                    throw IllegalStateException(
                        "DNS не смог определить адрес сервера обновлений: ${error.message ?: URL(url).host}. " +
                            "Проверьте подключение и DNS телефона; повторите проверку позже.",
                        error
                    )
                }
            } catch (error: SocketTimeoutException) {
                lastError = error
                if (attempt == 3) {
                    throw IllegalStateException(
                        "Сервер обновлений не ответил за отведённое время (${URL(url).host}). " +
                            "Проверьте соединение и повторите проверку.",
                        error
                    )
                }
            }
            try {
                Thread.sleep(1000L * (attempt + 1))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Проверка обновления прервана", interrupted)
            }
        }
        throw IllegalStateException("Сетевая ошибка при проверке обновления", lastError)
    }

    private fun downloadText(url: String): String = retryNetwork(url) {
        val connection = open(url, "application/octet-stream")
        try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, destination: File) {
        retryNetwork(url) {
            // A timed-out attempt must never leave a partial APK to be mistaken for a complete download.
            if (destination.exists() && !destination.delete()) {
                throw IllegalStateException("Не удалось очистить неполный файл обновления")
            }
            val connection = open(url, "application/octet-stream")
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
    }

    private fun open(url: String, accept: String = "application/vnd.github+json"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MyContacts-Android-Updater")
            setRequestProperty("Accept", accept)
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

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Shell UID cannot read app-private cache files; publish the verified APK in Downloads. */
    private fun publishToDownloads(activity: Activity, source: File): File {
        if (Build.VERSION.SDK_INT < 29) {
            throw IllegalStateException("Автоустановка через Shizuku требует Android 10 или новее")
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val destination = File(File(downloads, "MyContacts"), APK_ASSET)
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/MyContacts/"
        val resolver = activity.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(APK_ASSET, relativePath)
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, APK_ASSET)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Android не создал файл обновления в Downloads")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Не удалось записать APK в Downloads")
            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return destination
    }

    private fun installViaUserService(activity: Activity, apk: File, expectedHash: String): String {
        if (Shizuku.getVersion() < 10) {
            throw IllegalStateException("Нужен Shizuku API 10 или новее")
        }
        val args = Shizuku.UserServiceArgs(
            ComponentName(activity.packageName, ApkInstallUserService::class.java.name)
        ).daemon(false)
            .processNameSuffix("apk-installer")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        val latch = CountDownLatch(1)
        var service: IApkInstallService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = IApkInstallService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                latch.countDown()
            }
        }
        Shizuku.bindUserService(args, connection)
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw IllegalStateException("Shizuku UserService не подключился за 20 секунд")
            }
            val remote = service ?: throw IllegalStateException("Shizuku UserService вернул пустой Binder")
            return remote.installApk(apk.absolutePath, expectedHash)
        } finally {
            try {
                Shizuku.unbindUserService(args, connection, true)
            } catch (_: Exception) {
                // The app may already be replaced and its process may be shutting down.
            }
        }
    }
}
