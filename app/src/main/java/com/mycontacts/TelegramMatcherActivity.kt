package com.mycontacts

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val handler = Handler(Looper.getMainLooper())
    private var lastPrompt = ""
    private var isMatching = false

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
            .setPositiveButton("Передать и сопоставить") { _, _ -> runMatching(candidates, skipped) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private data class Candidate(val contact: Contact, val phone: String)

    private fun normalizeInternationalPhone(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return if (trimmed.startsWith("+") && digits.length in 8..15) "+$digits" else ""
    }

    private fun runMatching(candidates: List<Candidate>, skipped: Int) {
        if (isMatching) return
        isMatching = true
        matchButton.isEnabled = false
        var index = 0
        var matched = 0
        var checked = 0
        var errors = 0
        val progress = ProgressDialog(this).apply {
            setTitle("Сопоставление Telegram")
            setMessage("Подготовка…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = candidates.size
            setCancelable(false)
            show()
        }
        fun nextBatch() {
            if (index >= candidates.size) {
                progress.dismiss()
                isMatching = false
                matchButton.isEnabled = true
                status.text = "Готово. Проверено: $checked; найдено ID: $matched; ошибок: $errors; пропущено номеров: $skipped."
                AlertDialog.Builder(this).setTitle("Сопоставление завершено")
                    .setMessage(status.text).setPositiveButton("ОК", null).show()
                return
            }
            val start = index
            val batch = candidates.subList(start, minOf(start + 50, candidates.size))
            index += batch.size
            val input = batch.mapIndexed { i, candidate ->
                TdApi.ImportedContact(candidate.phone, candidate.contact.name.take(64).ifBlank { "Contact" }, "", null)
            }.toTypedArray()
            progress.setMessage("Проверено $checked из ${candidates.size}…")
            session.importPhoneBatch(input) { ids, error ->
                runOnUiThread {
                    if (error != null || ids == null) {
                        errors += batch.size
                        status.text = "Ошибка пачки: ${error ?: "нет результата"}"
                    } else {
                        for (i in batch.indices) {
                            val id = ids.getOrNull(i) ?: 0L
                            val candidate = batch[i]
                            checked++
                            if (id > 0L) {
                                matched++
                                session.fetchUsername(id) { username ->
                                    repo.updateTelegramLookup(candidate.contact.id, id.toString(), username.ifBlank { candidate.contact.telegramUsername })
                                }
                            }
                        }
                    }
                    progress.progress = checked
                    progress.setMessage("Проверено $checked из ${candidates.size}…")
                    handler.postDelayed({ nextBatch() }, 3000)
                }
            }
        }
        nextBatch()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
