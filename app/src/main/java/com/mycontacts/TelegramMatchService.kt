package com.mycontacts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.drinkless.tdlib.TdApi
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Performs Telegram contact matching independently of the Activity and reports progress
 * in an ongoing foreground-service notification.
 */
class TelegramMatchService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val startedMatching = AtomicBoolean(false)
    private lateinit var repo: ContactRepo
    private var session: TelegramTdlibSession? = null
    private var candidates: List<Candidate> = emptyList()
    private var index = 0
    private var checked = 0
    private var matched = 0
    private var errors = 0
    private var pendingUsernameWrites = 0
    private var batchesFinished = false
    private var completed = false
    private val stateLock = Any()

    private data class Candidate(val contact: Contact, val phone: String)

    override fun onCreate() {
        super.onCreate()
        AppLog.info(this, TAG, "Foreground service created")
        repo = ContactRepo(contentResolver)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Подготовка сопоставления…", 0, 0, ongoing = true))
        AppLog.info(this, TAG, "Matching requested")
        io.execute {
            try {
                val all = repo.loadAll()
                candidates = all.mapNotNull { contact ->
                    val raw = contact.phones.firstOrNull { it.trim().startsWith("+") } ?: return@mapNotNull null
                    val digits = raw.filter { it.isDigit() }
                    if (digits.length in 8..15) Candidate(contact, "+$digits") else null
                }
                if (candidates.isEmpty()) {
                    finishWithError("Нет номеров в международном формате +код страны.")
                    return@execute
                }
                val configured = getSharedPreferences("telegram_tdlib", MODE_PRIVATE)
                    .getInt("api_id", 0) > 0
                if (!configured) {
                    finishWithError("Сначала подключите Telegram и выполните авторизацию в приложении.")
                    return@execute
                }
                main.post {
                    session = TelegramTdlibSession.get(this, { message ->
                        if (message.startsWith("Telegram: ошибка")) {
                            updateNotification("Ошибка Telegram: " + message.removePrefix("Telegram: "), checked, candidates.size)
                        }
                    }, {
                        main.post { beginMatching() }
                    })
                    if (session?.isReady() == true) beginMatching()
                    else if (session?.resumeIfConfigured() == false) {
                        finishWithError("Не найдены сохранённые параметры Telegram. Откройте приложение и подключите аккаунт.")
                    } else {
                        updateNotification("Подключение к Telegram…", checked, candidates.size)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not prepare Telegram matching", e)
            AppLog.error(this, TAG, "Could not prepare Telegram matching", e)
                finishWithError("Не удалось подготовить контакты: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return START_NOT_STICKY
    }

    private fun beginMatching() {
        if (!startedMatching.compareAndSet(false, true)) return
        AppLog.info(this, TAG, "Matching started; candidates=${candidates.size}")
        updateNotification("Сопоставление запущено", 0, candidates.size)
        processNextBatch()
    }

    private fun processNextBatch() {
        if (completed) return
        if (index >= candidates.size) {
            synchronized(stateLock) { batchesFinished = true }
            finishIfReady()
            return
        }
        val batch = candidates.subList(index, minOf(index + 50, candidates.size))
        index += batch.size
        val imported = batch.map { c ->
            TdApi.ImportedContact(c.phone, c.contact.name.take(64).ifBlank { "Contact" }, "", null)
        }.toTypedArray()
        val activeSession = session
        if (activeSession == null) {
            finishWithError("TDLib-сессия недоступна.")
            return
        }
        activeSession.importPhoneBatch(imported) { ids, error ->
            if (error != null || ids == null) {
                synchronized(stateLock) {
                    checked += batch.size
                    errors += batch.size
                }
                updateNotification("Ошибка пачки: ${error ?: "нет результата"}", checked, candidates.size)
                main.postDelayed({ processNextBatch() }, BATCH_DELAY_MS)
                return@importPhoneBatch
            }
            for (i in batch.indices) {
                val id = ids.getOrNull(i) ?: 0L
                val candidate = batch[i]
                synchronized(stateLock) { checked++ }
                if (id > 0L) {
                    synchronized(stateLock) {
                        matched++
                        pendingUsernameWrites++
                    }
                    activeSession.fetchUsername(id) { username ->
                        io.execute {
                            try {
                                repo.updateTelegramLookup(
                                    candidate.contact.id,
                                    id.toString(),
                                    username.ifBlank { candidate.contact.telegramUsername }
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save Telegram fields for contact ${candidate.contact.id}", e)
                                synchronized(stateLock) { errors++ }
                            } finally {
                                synchronized(stateLock) { pendingUsernameWrites-- }
                                finishIfReady()
                            }
                        }
                    }
                }
            }
            updateNotification("Проверено $checked из ${candidates.size}", checked, candidates.size)
            main.postDelayed({ processNextBatch() }, BATCH_DELAY_MS)
        }
    }

    private fun finishIfReady() {
        val shouldFinish = synchronized(stateLock) { batchesFinished && pendingUsernameWrites == 0 && !completed }
        if (shouldFinish) {
            synchronized(stateLock) { if (completed) return; completed = true }
            val summary = "Готово. Проверено: $checked; найдено ID: $matched; ошибок: $errors."
            AppLog.info(this, TAG, summary)
            updateNotification(summary, candidates.size, candidates.size, ongoing = false, completed = true)
            stopForeground(false)
            stopSelf()
        }
    }

    private fun finishWithError(message: String) {
        if (completed) return
        completed = true
        Log.w(TAG, message)
        AppLog.error(this, TAG, message)
        updateNotification(message, checked, candidates.size.coerceAtLeast(1), ongoing = false, completed = true)
        stopForeground(false)
        stopSelf()
    }

    private fun updateNotification(message: String, progress: Int, total: Int, ongoing: Boolean = true, completed: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Notification permission not granted; foreground work continues without drawer updates")
        }
        AppLog.info(this, TAG, message)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(message, progress, total, ongoing, completed))
    }

    private fun notification(
        message: String,
        progress: Int,
        total: Int,
        ongoing: Boolean,
        completed: Boolean = false
    ): Notification {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 7310, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(if (completed) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_sync)
            .setContentTitle(if (completed) "Мои контакты — результат Telegram" else "Мои контакты — Telegram")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(pi)
            .setOnlyAlertOnce(!completed)
            .setAutoCancel(completed)
            .setOngoing(ongoing)
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (total > 0 && !completed) builder.setProgress(total, progress.coerceIn(0, total), false)
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Сопоставление Telegram", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Прогресс и завершение сопоставления Telegram ID и username"
                }
            )
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_MATCH = "com.mycontacts.action.MATCH_TELEGRAM"
        private const val TAG = "TelegramMatchService"
        private const val CHANNEL_ID = "telegram_matching_progress"
        private const val NOTIFICATION_ID = 7311
        private const val BATCH_DELAY_MS = 3000L
    }
}
