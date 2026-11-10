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

/** Runs Telegram phone matching in a foreground service so it survives Activity closure. */
class TelegramMatchService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val startedMatching = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
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
    private val progressPrefs by lazy { getSharedPreferences(PROGRESS_PREFS, MODE_PRIVATE) }

    private data class Candidate(val contacts: List<Contact>, val phone: String)

    override fun onCreate() {
        super.onCreate()
        repo = ContactRepo(contentResolver)
        createChannel()
        AppLog.info(this, TAG, "Telegram matching service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Подготовка сопоставления…", 0, 0, ongoing = true))
        if (!initialized.compareAndSet(false, true)) return START_STICKY
        val isNewRun = intent?.action == ACTION_MATCH
        getSharedPreferences("telegram_match_runtime", MODE_PRIVATE).edit()
            .putString("state", "preparing").putString("last_error", "").putLong("updated_at", System.currentTimeMillis()).apply()
        AppLog.info(this, TAG, "Matching started; newRun=$isNewRun")
        if (isNewRun) progressPrefs.edit().clear().apply()
        io.execute {
            try {
                val all = repo.loadAll(includeFields = false)
                val phoneRows = all.flatMap { contact ->
                    contact.phones.mapNotNull { raw ->
                        normalizeInternationalPhone(raw).takeIf { it.isNotBlank() }
                            ?.let { contact to it }
                    }
                }
                candidates = phoneRows.groupBy { it.second }.map { (phone, rows) ->
                    Candidate(rows.map { it.first }.distinctBy { it.id }, phone)
                }
                if (isNewRun) {
                    index = 0
                    checked = 0
                    matched = 0
                    errors = 0
                    saveProgress()
                } else {
                    index = progressPrefs.getInt(KEY_INDEX, 0).coerceIn(0, candidates.size)
                    checked = progressPrefs.getInt(KEY_CHECKED, 0).coerceAtLeast(0)
                    matched = progressPrefs.getInt(KEY_MATCHED, 0).coerceAtLeast(0)
                    errors = progressPrefs.getInt(KEY_ERRORS, 0).coerceAtLeast(0)
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
                AppLog.error(this@TelegramMatchService, TAG, "Could not prepare Telegram matching", e)
                finishWithError("Не удалось подготовить контакты: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        return START_STICKY
    }

    private fun normalizeInternationalPhone(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+") && digits.length in 8..15) "+$digits" else ""
    }

    private fun beginMatching() {
        if (!startedMatching.compareAndSet(false, true)) return
        getSharedPreferences("telegram_match_runtime", MODE_PRIVATE).edit()
            .putString("state", "running").putLong("updated_at", System.currentTimeMillis()).apply()
        AppLog.info(this, TAG, "Telegram matching is running; candidates=${candidates.size}")
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
        val batchEnd = minOf(index + BATCH_SIZE, candidates.size)
        val batch = candidates.subList(index, batchEnd)
        val imported = batch.map { candidate ->
            TdApi.ImportedContact(candidate.phone, candidate.contacts.firstOrNull()?.name?.take(64).orEmpty().ifBlank { "Contact" }, "", null)
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
                    index = batchEnd
                }
                saveProgress()
                val batchError = error?.toString() ?: "No result returned"
                AppLog.warn(this, TAG, "Telegram batch import failed: $batchError")
                getSharedPreferences("telegram_match_runtime", MODE_PRIVATE).edit()
                    .putString("state", "running_with_errors").putString("last_error", batchError.take(1000))
                    .putLong("updated_at", System.currentTimeMillis()).apply()
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
                                val foundNames = username.split(",")
                                    .map { it.trim().removePrefix("@") }
                                    .filter { it.isNotBlank() && !it.equals("Скрыт", ignoreCase = true) }
                                candidate.contacts.forEach { contact ->
                                    val previousNames = contact.telegramUsername.split(",")
                                        .map { it.trim().removePrefix("@") }
                                        .filter { it.isNotBlank() && !it.equals("Скрыт", ignoreCase = true) }
                                    val allNames = (previousNames + foundNames).distinct()
                                    val storedUsername = if (allNames.isEmpty()) "Скрыт" else allNames.joinToString(", ")
                                    repo.updateTelegramLookup(contact.id, id.toString(), storedUsername)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to save Telegram fields for contact ${candidate.contacts.firstOrNull()?.id}", e)
                                AppLog.error(this@TelegramMatchService, TAG, "Failed to save Telegram fields", e)
                                synchronized(stateLock) { errors++ }
                            } finally {
                                synchronized(stateLock) { pendingUsernameWrites-- }
                                finishIfReady()
                            }
                        }
                    }
                }
            }
            index = batchEnd
            saveProgress()
            updateNotification("Проверено $checked из ${candidates.size}", checked, candidates.size)
            main.postDelayed({ processNextBatch() }, BATCH_DELAY_MS)
        }
    }

    private fun finishIfReady() {
        val shouldFinish = synchronized(stateLock) { batchesFinished && pendingUsernameWrites == 0 && !completed }
        if (shouldFinish) {
            synchronized(stateLock) { if (completed) return; completed = true }
            val summary = "Готово. Проверено: $checked; найдено ID: $matched; ошибок: $errors."
            getSharedPreferences("telegram_match_runtime", MODE_PRIVATE).edit()
                .putString("state", "completed").putString("last_error", "").putLong("updated_at", System.currentTimeMillis()).apply()
            AppLog.info(this, TAG, summary)
            updateNotification(summary, candidates.size, candidates.size, ongoing = false, completed = true)
            progressPrefs.edit().clear().apply()
            stopForeground(false)
            stopSelf()
        }
    }

    private fun saveProgress() {
        progressPrefs.edit()
            .putInt(KEY_INDEX, index)
            .putInt(KEY_CHECKED, checked)
            .putInt(KEY_MATCHED, matched)
            .putInt(KEY_ERRORS, errors)
            .apply()
    }

    private fun finishWithError(message: String) {
        if (completed) return
        completed = true
        Log.w(TAG, message)
        getSharedPreferences("telegram_match_runtime", MODE_PRIVATE).edit()
            .putString("state", "error").putString("last_error", message.take(1000)).putLong("updated_at", System.currentTimeMillis()).apply()
        AppLog.error(this, TAG, message)
        updateNotification(message, checked, candidates.size.coerceAtLeast(1), ongoing = false, completed = true)
        stopForeground(false)
        stopSelf()
    }

    private fun updateNotification(message: String, progress: Int, total: Int, ongoing: Boolean = true, completed: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Notification permission not granted; foreground work continues without drawer updates")
            AppLog.warn(this, TAG, "Notification permission not granted; foreground work continues")
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(message, progress, total, ongoing, completed))
    }

    private fun notification(message: String, progress: Int, total: Int, ongoing: Boolean, completed: Boolean = false): Notification {
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
        private const val BATCH_SIZE = 50
        private const val BATCH_DELAY_MS = 300_000L
        private const val PROGRESS_PREFS = "telegram_match_progress"
        private const val KEY_INDEX = "index"
        private const val KEY_CHECKED = "checked"
        private const val KEY_MATCHED = "matched"
        private const val KEY_ERRORS = "errors"
    }
}
