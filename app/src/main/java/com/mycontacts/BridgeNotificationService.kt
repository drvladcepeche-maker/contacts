package com.mycontacts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONArray

/** The sole server command poller; claimed commands are persisted before alerts. */
class BridgeNotificationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bridge: ContactsBridge
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        try {
            bridge = ContactsBridge(this)
            createChannels()
            Log.i(TAG, "Service created; notification channels checked")
        } catch (e: Exception) {
            Log.e(TAG, "Service initialization failed", e)
            AppLog.error(this, TAG, "Service initialization failed", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (!::bridge.isInitialized) bridge = ContactsBridge(this)
            if (bridge.token().isBlank()) {
                Log.w(TAG, "Service not started: device token is blank")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            createChannels()
            startForeground(ONGOING_ID, buildNotification("Ожидание команд", false))
            Log.i(TAG, "Foreground service started")
            if (!running) {
                running = true
                handler.post(pollTask)
            }
            return START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start foreground service", e)
            AppLog.error(this, TAG, "Unable to start foreground service", e)
            running = false
            stopSelf(startId)
            return START_NOT_STICKY
        }
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!running) return
            Thread {
                try {
                    val commands = bridge.poll()
                    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                    var pendingCount = 0
                    var pendingCommands = JSONArray()
                    synchronized(QUEUE_LOCK) {
                        val pending = readPending(prefs.getString(KEY_PENDING, "[]"))
                        val known = HashSet<String>()
                        for (i in 0 until pending.length()) known.add(pending.optJSONObject(i)?.optString("id").orEmpty())
                        var added = 0
                        for (i in 0 until commands.length()) {
                            val command = commands.optJSONObject(i) ?: continue
                            val id = command.optString("id")
                            if (id.isNotBlank() && known.add(id)) { pending.put(command); added++ }
                        }
                        if (added > 0) {
                            if (!prefs.edit().putString(KEY_PENDING, pending.toString()).commit()) {
                                throw IllegalStateException("Could not persist claimed commands")
                            }
                            Log.i(TAG, "Persisted " + added + " command(s); pending=" + pending.length())
                        }
                        pendingCount = pending.length()
                        pendingCommands = pending
                    }
                    if (pendingCount > 0) postPendingNotification(pendingCommands)
                    else getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
                    recordDiagnostic("last_poll_at", System.currentTimeMillis().toString())
                    recordDiagnostic("last_poll_error", "")
                } catch (e: Exception) {
                    Log.e(TAG, "Command polling or notification cycle failed", e)
                    AppLog.error(this@BridgeNotificationService, TAG, "Command polling or notification cycle failed", e)
                    recordDiagnostic("last_poll_error", e.javaClass.simpleName + ": " + (e.message ?: "no message"))
                    if (bridge.token().isBlank()) {
                        // A 401 clears the rejected token in ContactsBridge; stop polling until re-paired.
                        running = false
                        stopSelf()
                    }
                } finally {
                    if (running) handler.postDelayed(this, POLL_MS)
                }
            }.start()
        }
    }

    private fun postPendingNotification(pending: JSONArray) {
        val manager = getSystemService(NotificationManager::class.java)
        val count = pending.length()
        val enabled = manager.areNotificationsEnabled()
        val permission = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        recordDiagnostic("notifications_enabled", (enabled && permission).toString())
        if (!enabled || !permission) {
            Log.w(TAG, "Pending commands=" + count + "; notifications disabled (enabled=" + enabled + ", permission=" + permission + ")")
            recordDiagnostic("last_notification_error", "Notifications disabled or POST_NOTIFICATIONS denied")
            return
        }
        try {
            val descriptions = (0 until minOf(count, 3)).mapNotNull { i -> pending.optJSONObject(i)?.let { commandSummary(it) } }
            val more = if (count > 3) "\nИ ещё команд: " + (count - 3) else ""
            val message = "Ожидают решения: " + count + "\n" + descriptions.joinToString("\n") + more
            manager.notify(ALERT_ID, buildNotification(message, true, pending))
            recordDiagnostic("last_notification_error", "")
            Log.i(TAG, "Pending-command notification posted; count=" + count)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish pending-command notification", e)
            AppLog.error(this, TAG, "Failed to publish pending-command notification", e)
            recordDiagnostic("last_notification_error", e.javaClass.simpleName + ": " + (e.message ?: "no message"))
        }
    }

    private fun commandSummary(command: org.json.JSONObject): String {
        val action = command.optString("action")
        val payload = command.optJSONObject("payload") ?: org.json.JSONObject()
        val id = payload.optLong("id", -1)
        val name = payload.optString("name").trim()
        val phone = payload.optString("phone").trim()
        return when (action) {
            "upsert_contact" -> (if (id > 0) "Изменить контакт" else "Создать контакт") + ": " + name.ifBlank { "без имени" } +
                (if (phone.isNotBlank()) " · " + maskPhone(phone) else "")
            "delete_contact" -> "Удалить контакт: " + name.ifBlank { "ID " + id }
            "merge_contacts" -> "Объединить контакты · " + (payload.optJSONArray("ids")?.length() ?: 0) + " записей"
            "update_telegram_fields" -> "Изменить Telegram-поля · ID " + id + " · " + payload.optString("telegram_username")
            "move_contact" -> "Перенести " + name.ifBlank { "контакт · ID " + id } + " · " + payload.optString("source_account_name") + " → " + payload.optString("target_account_name")
            "sync_contacts" -> "Синхронизировать контакты с сервером"
            "list_contacts" -> "Передать список контактов"
            "search_contacts" -> "Найти контакты: " + payload.optString("query").take(50)
            else -> action.ifBlank { "Команда" }
        }
    }

    private fun maskPhone(value: String): String {
        val digits = value.filter { it.isDigit() }
        return if (digits.length <= 4) "••••" else "•••• " + digits.takeLast(4)
    }

    private fun readPending(value: String?): JSONArray =
        try { JSONArray(value ?: "[]") } catch (e: Exception) {
            Log.e(TAG, "Pending queue JSON is corrupt; using an empty queue", e)
            JSONArray()
        }

    private fun recordDiagnostic(key: String, value: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Связь с ChatGPT", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Работа фоновой синхронизации контактов" })
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, "Команды для подтверждения", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Уведомления о командах, требующих подтверждения на телефоне" })
        }
    }

    private fun buildNotification(message: String, alert: Boolean, pending: JSONArray? = null): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_bridge_queue", true)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0))
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, if (alert) ALERT_CHANNEL_ID else CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Мои контакты").setContentText(message)
            .setContentIntent(pendingIntent).setAutoCancel(false).setOnlyAlertOnce(true)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setOngoing(!alert).setCategory(Notification.CATEGORY_SERVICE)
        if (alert && pending != null) {
            for (i in 0 until pending.length()) {
                val command = pending.optJSONObject(i) ?: continue
                val action = command.optString("action")
                if (action !in setOf("upsert_contact", "delete_contact", "merge_contacts", "update_telegram_fields", "move_contact")) continue
                val id = command.optString("id")
                val open = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("notification_command_id", id)
                    putExtra("notification_decision", "approve")
                }
                val approveIntent = PendingIntent.getActivity(
                    this, (id + ":approve").hashCode(), open,
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                )
                builder.addAction(android.R.drawable.ic_menu_save, "Подтвердить", approveIntent)
                val reject = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("notification_command_id", id)
                    putExtra("notification_decision", "reject")
                }
                val rejectIntent = PendingIntent.getActivity(
                    this, (id + ":reject").hashCode(), reject,
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                )
                builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отклонить", rejectIntent)
                break
            }
        }
        return builder.build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        Log.w(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ContactsBridgeService"
        const val PREFS = "bridge_service_queue"
        const val KEY_PENDING = "pending_commands"
        const val CHANNEL_ID = "contacts_bridge_service"
        const val ALERT_CHANNEL_ID = "contacts_bridge_alerts"
        const val ONGOING_ID = 7301
        const val ALERT_ID = 7302
        const val POLL_MS = 15000L
        internal val QUEUE_LOCK = Any()
    }
}
