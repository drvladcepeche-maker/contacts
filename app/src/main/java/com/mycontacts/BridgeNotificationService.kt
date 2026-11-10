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
import org.json.JSONObject

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
            AppLog.info(this, TAG, "Service created")
        } catch (e: Exception) {
            Log.e(TAG, "Service initialization failed", e)
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
            AppLog.info(this, TAG, "Foreground service started")
            if (!running) {
                running = true
                handler.post(pollTask)
            }
            recordDiagnostic("service_state", "running")
            return START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start foreground service", e)
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
                    }
                    processDiagnosticsCommands()
                    synchronized(QUEUE_LOCK) {
                        pendingCount = readPending(prefs.getString(KEY_PENDING, "[]")).length()
                    }
                    if (pendingCount > 0) postPendingNotification(pendingCount)
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

    private fun postPendingNotification(count: Int) {
        val manager = getSystemService(NotificationManager::class.java)
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
            manager.notify(ALERT_ID, buildNotification("Ожидают подтверждения: " + count, true))
            recordDiagnostic("last_notification_error", "")
            Log.i(TAG, "Pending-command notification posted; count=" + count)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish pending-command notification", e)
            recordDiagnostic("last_notification_error", e.javaClass.simpleName + ": " + (e.message ?: "no message"))
        }
    }

    private fun readPending(value: String?): JSONArray =
        try { JSONArray(value ?: "[]") } catch (e: Exception) {
            Log.e(TAG, "Pending queue JSON is corrupt; using an empty queue", e)
            JSONArray()
        }

    /** Read-only diagnostics are answered in the foreground service, even while the Activity is minimized. */
    private fun processDiagnosticsCommands() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val pending = synchronized(QUEUE_LOCK) {
            readPending(prefs.getString(KEY_PENDING, "[]"))
        }
        val requests = (0 until pending.length()).mapNotNull { pending.optJSONObject(it) }
            .filter { it.optString("action") == "get_diagnostics" }
        for (command in requests) {
            val id = command.optString("id")
            if (id.isBlank()) continue
            try {
                val result = JSONObject().put("diagnostics", AppDiagnostics.collect(this))
                bridge.complete(id, result)
                synchronized(QUEUE_LOCK) {
                    val current = readPending(prefs.getString(KEY_PENDING, "[]"))
                    val kept = JSONArray()
                    for (i in 0 until current.length()) {
                        val item = current.optJSONObject(i) ?: continue
                        if (item.optString("id") != id) kept.put(item)
                    }
                    if (!prefs.edit().putString(KEY_PENDING, kept.toString()).commit()) {
                        throw IllegalStateException("Could not remove completed diagnostics request from local queue")
                    }
                }
                Log.i(TAG, "Answered on-demand diagnostics request " + id)
            } catch (e: Exception) {
                Log.e(TAG, "Diagnostics request failed: " + id, e)
                AppLog.error(this, TAG, "Diagnostics request failed", e)
                recordDiagnostic("last_diagnostics_error", e.javaClass.simpleName + ": " + (e.message ?: "no message"))
            }
        }
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

    private fun buildNotification(message: String, alert: Boolean): Notification {
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
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Мои контакты").setContentText(message)
            .setContentIntent(pendingIntent).setAutoCancel(false).setOnlyAlertOnce(true)
            .setOngoing(!alert).setCategory(Notification.CATEGORY_SERVICE).build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recordDiagnostic("service_state", "stopped")
        AppLog.info(this, TAG, "Foreground service destroyed")
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
