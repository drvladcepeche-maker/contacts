package com.mycontacts

import android.os.RemoteException
import android.os.Environment
import java.io.File
import java.security.MessageDigest

/** Runs as Shizuku shell/root identity; validates the APK before invoking Package Manager. */
class ApkInstallUserService : IApkInstallService.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    @Throws(RemoteException::class)
    override fun installApk(apkPath: String, expectedSha256: String): String {
        val allowedRoot = File(
            Environment.getExternalStorageDirectory(),
            Environment.DIRECTORY_DOWNLOADS + "/MyContacts"
        ).canonicalFile
        val apk = File(apkPath).canonicalFile
        require(apk.parentFile == allowedRoot) { "APK path is outside Download/MyContacts" }
        require(apk.isFile && apk.canRead()) { "Shell cannot read the downloaded APK" }
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid expected SHA-256" }
        require(sha256(apk) == expectedSha256) { "APK changed after verification; installation cancelled" }

        val process = ProcessBuilder(
            "pm", "install", "-r", "--user", "0", apk.absolutePath
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0 || !output.contains("Success")) {
            throw IllegalStateException("Package Manager failed (exit=$exitCode): " + output.take(500))
        }
        return output
    }

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
}
