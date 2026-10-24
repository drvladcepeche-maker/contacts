package com.mycontacts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Polls the bridge while the activity is in the background.
 * Claimed commands are persisted locally until MainActivity is resumed.
 */
class BridgeNotificationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bridge: ContactsBridge
    private var running = false

    override fun onCreate() {
        super.onCreate()
        bridge = ContactsBridge(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bridge.token().isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(ONGOING_ID, buildNotification("Ожидание команд", false))
        if (!running) {
            running = true
            handler.post(pollTask)
        }
        return START_STICKY
    }

    private val pollTask = object : Runnable {
        override fun run() {
            if (!running) return
            Thread {
                var added = 0
                try {
                    val commands = bridge.poll()
                    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                    val pending = try { JSONArray(prefs.getString(KEY_PENDING, "[]")) } catch (_: Exception) { JSONArray() }
                    val known = HashSet<String>()
                    for (i in 0 until pending.length()) known.add(pending.optJSONObject(i)?.optString("id") ?: "")
                    for (i in 0 until commands.length()) {
                        val command = commands.optJSONObject(i) ?: continue
                        val id = command.optString("id")
                        if (id.isNotBlank() && known.add(id)) {
                            pending.put(command)
                            added++
                        }
                    }
                    if (added > 0) prefs.edit().putString(KEY_PENDING, pending.toString()).apply()
                } catch (_: Exception) {
                    // A temporary network failure is retried on the next poll.
                }
                if (running) {
                    if (added > 0) {
                        val count = getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getString(KEY_PENDING, "[]").let { try { JSONArray(it).length() } catch (_: Exception) { added } }
                        val manager = getSystemService(NotificationManager::class.java)
                        manager.notify(ALERT_ID, buildNotification("Ожидают подтверждения: $count", true))
                    }
                    handler.postDelayed(this, POLL_MS)
                }
            }.start()
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Связь с ChatGPT", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Работа фоновой синхронизации контактов" })
            manager.createNotificationChannel(NotificationChannel(
                ALERT_CHANNEL_ID, "Команды для подтверждения", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Уведомления о командах, требующих подтверждения на телефоне" })
        }
    }

    private fun buildNotification(message: String, alert: Boolean): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_bridge_queue", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, if (alert) ALERT_CHANNEL_ID else CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Мои контакты")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setAutoCancel(alert)
            .setOngoing(!alert)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PREFS = "bridge_service_queue"
        const val KEY_PENDING = "pending_commands"
        const val CHANNEL_ID = "contacts_bridge_service"
        const val ALERT_CHANNEL_ID = "contacts_bridge_alerts"
        const val ONGOING_ID = 7301
        const val ALERT_ID = 7302
        const val POLL_MS = 15000L
    }
}
