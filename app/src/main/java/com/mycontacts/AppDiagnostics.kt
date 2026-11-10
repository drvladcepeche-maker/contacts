package com.mycontacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject

/**
 * Read-only diagnostics returned only in response to an authenticated ChatGPT request.
 * Contact contents, device tokens, Telegram API credentials and account identifiers are excluded.
 */
object AppDiagnostics {
    fun collect(context: Context): JSONObject {
        val app = context.applicationContext
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val bridgePrefs = app.getSharedPreferences(BridgeNotificationService.PREFS, Context.MODE_PRIVATE)
        val telegramPrefs = app.getSharedPreferences("telegram_match_progress", Context.MODE_PRIVATE)
        val telegramConfig = app.getSharedPreferences("telegram_tdlib", Context.MODE_PRIVATE)
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        fun granted(permission: String): Boolean =
            app.packageManager.checkPermission(permission, app.packageName) == PackageManager.PERMISSION_GRANTED

        val telegram = JSONObject()
            .put("index", telegramPrefs.getInt("index", 0))
            .put("checked", telegramPrefs.getInt("checked", 0))
            .put("matched", telegramPrefs.getInt("matched", 0))
            .put("errors", telegramPrefs.getInt("errors", 0))
            .put("api_configured", telegramConfig.getInt("api_id", 0) > 0)

        val runtime = JSONObject()
            .put("bridge_service_state", bridgePrefs.getString("service_state", "unknown"))
            .put("last_poll_at_ms", bridgePrefs.getString("last_poll_at", "") ?: "")
            .put("last_poll_error", bridgePrefs.getString("last_poll_error", "") ?: "")
            .put("last_notification_error", bridgePrefs.getString("last_notification_error", "") ?: "")
            .put("last_service_start_error", bridgePrefs.getString("last_service_start_error", "") ?: "")
            .put("pending_command_count", try {
                org.json.JSONArray(bridgePrefs.getString(BridgeNotificationService.KEY_PENDING, "[]")).length()
            } catch (_: Exception) { -1 })
            .put("notifications_enabled", notificationManager.areNotificationsEnabled())
            .put("battery_optimization_exempt", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                power.isIgnoringBatteryOptimizations(app.packageName) else true)

        return JSONObject()
            .put("collected_at", java.time.Instant.now().toString())
            .put("app", JSONObject()
                .put("package_name", app.packageName)
                .put("version_name", packageInfo.versionName ?: BuildConfig.VERSION_NAME)
                .put("version_code", packageInfo.versionCode)
                .put("build_type", BuildConfig.BUILD_TYPE)
                .put("debug", BuildConfig.DEBUG))
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER ?: "")
                .put("model", Build.MODEL ?: "")
                .put("android_release", Build.VERSION.RELEASE ?: "")
                .put("sdk_int", Build.VERSION.SDK_INT))
            .put("permissions", JSONObject()
                .put("read_contacts", granted(Manifest.permission.READ_CONTACTS))
                .put("write_contacts", granted(Manifest.permission.WRITE_CONTACTS))
                .put("post_notifications", if (Build.VERSION.SDK_INT >= 33)
                    granted(Manifest.permission.POST_NOTIFICATIONS) else true))
            .put("bridge", JSONObject()
                .put("device_token_configured", ContactsBridge(app).token().isNotBlank())
                .put("last_poll_at_ms", bridgePrefs.getString("last_poll_at", "") ?: ""))
            .put("runtime", runtime)
            .put("telegram_matching", telegram)
            .put("recent_app_logs", AppLog.readForDiagnostics(app, 160))
    }
}
