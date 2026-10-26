package com.mycontacts

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.drinkless.tdlib.TdApi
import java.util.Locale

class TelegramMatcherActivity : Activity() {
    private lateinit var repo: ContactRepo
    private lateinit var session: TelegramTdlibSession
    private lateinit var status: TextView
    private lateinit var authButton: Button
    private lateinit var matchButton: Button
    private var lastPrompt = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ContactRepo(contentResolver)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        status = TextView(this).apply { text = "Telegram MTProto / TDLib"; textSize = 16f }
        authButton = Button(this).apply {
            text = "Подключить Telegram"
            setOnClickListener { askApiCredentials() }
        }
        matchButton = Button(this).apply {
            text = "Сопоставить номера"
            isEnabled = false
            setOnClickListener { confirmMatch() }
        }
        root.addView(status, ViewGroup.LayoutParams(-1, -2))
        root.addView(authButton)
        root.addView(matchButton)
        setContentView(root)

        session = TelegramTdlibSession.get(this, { message ->
            runOnUiThread {
                status.text = message
                when {
                    message.contains("Нужен номер телефона") -> askOnce("phone") { askPhone() }
                    message.contains("Нужен код подтверждения") -> askOnce("code") { askCode() }
                    message.contains("Нужен пароль двухэтапной проверки") -> askOnce("password") { askPassword() }
                    message.contains("требует адрес электронной почты") -> askOnce("email") {
                        askText("Электронная почта Telegram", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) { value ->
                            session.submitEmail(value)
                        }
                    }
                    message.contains("требует код из электронной почты") -> askOnce("email-code") {
                        askText("Код из электронной почты", InputType.TYPE_CLASS_TEXT) { value ->
                            session.submitEmailCode(value)
                        }
                    }
                }
            }
        }, {
            runOnUiThread {
                matchButton.isEnabled = true
                authButton.text = "Telegram подключён"
            }
        })
        if (!session.resumeIfConfigured()) askApiCredentials()
    }

    private fun askOnce(key: String, action: () -> Unit) {
        if (lastPrompt == key) return
        lastPrompt = key
        action()
    }

    private fun askApiCredentials() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
        }
        val apiId = EditText(this).apply {
            hint = "Telegram api_id"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val apiHash = EditText(this).apply {
            hint = "Telegram api_hash"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        box.addView(apiId)
        box.addView(apiHash)
        AlertDialog.Builder(this)
            .setTitle("Данные приложения Telegram")
            .setMessage("Получите api_id и api_hash на my.telegram.org → API development tools. Они сохраняются только на этом телефоне.")
            .setView(box)
            .setPositiveButton("Продолжить") { _, _ ->
                val id = apiId.text.toString().trim().toIntOrNull()
                val hash = apiHash.text.toString().trim()
                if (id == null || hash.isBlank()) {
                    status.text = "Нужно указать оба параметра."
                    return@setPositiveButton
                }
                lastPrompt = ""
                try { session.start(id, hash) }
                catch (e: Exception) { status.text = "Не удалось запустить TDLib: ${e.message}" }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askPhone() = askText("Номер Telegram в международном формате (+...)", InputType.TYPE_CLASS_PHONE) {
        lastPrompt = ""
        try { session.submitPhone(it) } catch (e: Exception) { status.text = e.message ?: "Ошибка номера" }
    }

    private fun askCode() = askText("Код подтверждения Telegram", InputType.TYPE_CLASS_NUMBER) {
        lastPrompt = ""
        try { session.submitCode(it) } catch (e: Exception) { status.text = e.message ?: "Ошибка кода" }
    }

    private fun askPassword() = askText("Пароль двухэтапной проверки Telegram", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) {
        lastPrompt = ""
        try { session.submitPassword(it) } catch (e: Exception) { status.text = e.message ?: "Ошибка пароля" }
    }

    private fun askText(title: String, inputType: Int, submit: (String) -> Unit) {
        val input = EditText(this).apply { this.inputType = inputType; hint = title }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Продолжить") { _, _ -> submit(input.text.toString().trim()) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmMatch() {
        if (!session.isReady() || isMatching) return
        val contacts = repo.loadAll()
        val candidates = contacts.mapNotNull { contact ->
            val phone = contact.phones.firstOrNull { it.trim().startsWith("+") }
                ?.let { normalizeInternationalPhone(it) }
            if (phone.isNullOrBlank()) null else Candidate(contact, phone)
        }
        val skipped = contacts.size - candidates.size
        if (candidates.isEmpty()) {
            status.text = "Не найдено номеров в международном формате +код страны. Обработано контактов: 0."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Передать номера Telegram?")
            .setMessage("Будет передано в Telegram до ${candidates.size} номеров из контактов для сопоставления. Telegram может сохранить их в списке контактов аккаунта. Номера без международного префикса пропущены: $skipped. Продолжить?")
            .setPositiveButton("Передать и сопоставить") { _, _ -> requestNotificationPermissionAndStart() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private data class Candidate(val contact: Contact, val phone: String)

    private fun normalizeInternationalPhone(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+") && digits.length in 8..15) "+$digits" else ""
    }

    private fun requestNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        startMatchingService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            // Continue even if denied; Android may still show the foreground-service task notification,
            // but progress/completion alerts in the notification drawer can be suppressed.
            startMatchingService()
        }
    }

    private fun startMatchingService() {
        try {
            val intent = Intent(this, TelegramMatchService::class.java).apply {
                action = TelegramMatchService.ACTION_MATCH
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            status.text = "Сопоставление запущено в фоне. Ход работы и результат будут в уведомлениях."
            matchButton.isEnabled = false
        } catch (e: Exception) {
            status.text = "Не удалось запустить фоновое сопоставление: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 7312
    }

    override fun onDestroy() { super.onDestroy() }
}
