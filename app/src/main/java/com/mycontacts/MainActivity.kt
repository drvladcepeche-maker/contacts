package com.mycontacts

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var repo: ContactRepo
    private lateinit var bridge: ContactsBridge
    private val bridgeHandler = Handler(Looper.getMainLooper())
    private var bridgePolling = false
    private var waitingForInstallPermission = false
    private lateinit var shizukuStatus: TextView
    private val shizukuPermissionRequestCode = 101
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == shizukuPermissionRequestCode) {
            updateShizukuStatus()
            toast(if (grantResult == PackageManager.PERMISSION_GRANTED) {
                "Shizuku разрешил доступ приложению «Мои контакты — привязка»."
            } else {
                "Shizuku не выдал разрешение приложению."
            })
        }
    }
    private val activeCommandIds = HashSet<String>()

    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var status: TextView
    private var all: List<Contact> = emptyList()
    private var refreshGeneration = 0
    private var shown: List<Contact> = emptyList()
    private var pendingXlsxExport: List<Contact>? = null

    private val reqPerm = 1
    private val reqExportVcf = 10
    private val reqExportCsv = 11
    private val reqImport = 12
    private val reqExportXlsx = 13
    private val reqExportFullCsv = 15
    private val selected = HashSet<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        AppLog.installCrashHandler(this)
        AppLog.info(this, "MainActivity", "onCreate")
        repo = ContactRepo(contentResolver)
        bridge = ContactsBridge(this)
        buildUi()
        if (hasPerms()) {
            refresh()
            requestNotificationPermissionIfNeeded()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), reqPerm)
        }
    }

    private fun hasPerms() =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == reqPerm) {
            if (hasPerms()) {
                refresh()
                requestNotificationPermissionIfNeeded()
            } else toast("Нужен доступ к контактам")
        } else if (code == 14) {
            // Notification permission is optional for contact management.
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 14)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        search = EditText(this).apply { hint = "Поиск по имени или номеру"; setSingleLine() }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { applyFilter() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row4 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row5 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row6 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row7 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row8 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row9 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row10 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { a() }
        }
        row1.addView(btn("Добавить") { editDialog(null) })
        row1.addView(btn("Дубликаты") { duplicatesDialog() })
        row1.addView(btn("Перенос") { transferDialog() })
        row2.addView(btn("Импорт") { pickImport() })
        row2.addView(btn("VCF") { createDoc("contacts.vcf", "text/x-vcard", reqExportVcf) })
        row2.addView(btn("CSV") { createDoc("contacts.csv", "text/csv", reqExportCsv) })
        row2.addView(btn("Полный CSV") { createDoc("contacts_all_fields.csv", "text/csv", reqExportFullCsv) })
        row2.addView(btn("ChatGPT / Claude") { aiDialog() })
        row3.addView(btn("Excel") { exportXlsxDialog() })
        row3.addView(btn("Выделить всё") { selectAll() })
        row3.addView(btn("Снять") { clearSelection() })
        row3.addView(btn("Удалить выбранные") { deleteSelected() })
        row4.addView(btn("Передать в ChatGPT") { shareWithChatGPT() })
        row5.addView(btn("Связь с ChatGPT") { bridgeDialog("ChatGPT") })
        row5.addView(btn("Связь с Claude") { bridgeDialog("Claude") })
        row6.addView(btn("Telegram ID по номерам") { startActivity(Intent(this, TelegramMatcherActivity::class.java)) })
        row6.addView(btn("Серверная проверка") { queueTelegramMatch() })
        row7.addView(btn("Аккаунты синхронизации") { showAccountSyncSettings() })
        row8.addView(btn("Сохранить логи") { exportAppLogs() })
        row8.addView(btn("Файлы для Termux") { exportTermuxGuide() })
        row9.addView(btn("Разрешить установку APK") { requestUnknownSourcesPermission() })
        row10.addView(btn("Доступ Shizuku") { requestShizukuPermission() })
        val row11 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row11.addView(btn("Проверить обновления") { ApkUpdater.checkAndInstall(this@MainActivity) })
        shizukuStatus = TextView(this)
        status = TextView(this)
        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { if (selected.isNotEmpty()) toggle(it) else editDialog(it) } }
        list.setOnItemLongClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { toggle(it) }; true }
        listOf<View>(search, row1, row2, row3, row4, row5, row6, row7, row8, row9, row10, row11, shizukuStatus, status, list).forEach { root.addView(it) }
        setContentView(root)
        updateShizukuStatus()
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus()
        if (waitingForInstallPermission) {
            waitingForInstallPermission = false
            if (android.os.Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()) {
                toast("Разрешение на установку APK выдано")
            } else {
                toast("Разрешение на установку APK не выдано")
            }
        }
        startBridgeService()
        drainBridgeQueue()
        startBridgeQueueDrain()
        DiagnosticsSync.registerNetworkCallback(this)
        DiagnosticsSync.flush(this)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    override fun onPause() {
        bridgePolling = false
        bridgeHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun startBridgeService() {
        if (bridge.token().isBlank()) return
        try {
            val serviceIntent = Intent(this, BridgeNotificationService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
            getSharedPreferences(BridgeNotificationService.PREFS, MODE_PRIVATE).edit().putString("last_service_start_error", "").apply()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start bridge foreground service", e)
            getSharedPreferences(BridgeNotificationService.PREFS, MODE_PRIVATE).edit()
                .putString("last_service_start_error", e.javaClass.simpleName + ": " + (e.message ?: "no message")).apply()
            toast("Не удалось запустить фоновую связь: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun startBridgeQueueDrain() {
        if (bridgePolling) return
        bridgePolling = true
        bridgeHandler.post(object : Runnable {
            override fun run() {
                if (!bridgePolling || isFinishing) return
                drainBridgeQueue()
                bridgeHandler.postDelayed(this, 3000)
            }
        })
    }

    private fun enqueueBridgeCommand(command: JSONObject) {
        val id = command.optString("id")
        if (id.isBlank()) throw IllegalArgumentException("Claimed command has no id")
        val prefs = getSharedPreferences(BridgeNotificationService.PREFS, MODE_PRIVATE)
        synchronized(BridgeNotificationService.QUEUE_LOCK) {
            val pending = try { JSONArray(prefs.getString(BridgeNotificationService.KEY_PENDING, "[]")) } catch (_: Exception) { JSONArray() }
            for (i in 0 until pending.length()) if (pending.optJSONObject(i)?.optString("id") == id) return
            pending.put(command)
            if (!prefs.edit().putString(BridgeNotificationService.KEY_PENDING, pending.toString()).commit()) {
                throw IllegalStateException("Could not persist command " + id + " before handling")
            }
        }
    }

    private fun drainBridgeQueue() {
        val prefs = getSharedPreferences(BridgeNotificationService.PREFS, MODE_PRIVATE)
        val pending = try { JSONArray(prefs.getString(BridgeNotificationService.KEY_PENDING, "[]")) }
            catch (e: Exception) {
                android.util.Log.e("MainActivity", "Could not read pending command queue", e)
                JSONArray()
            }
        for (i in 0 until pending.length()) {
            val command = pending.optJSONObject(i) ?: continue
            val id = command.optString("id")
            if (id.isBlank()) continue
            // Read-only diagnostics and remote config refresh are owned by BridgeNotificationService.
            // Never let the UI queue dispatcher race the foreground service.
            if (command.optString("action") == "get_diagnostics" ||
                command.optString("action") == "refresh_remote_config") continue
            val alreadyHandling = synchronized(activeCommandIds) { !activeCommandIds.add(id) }
            if (alreadyHandling) continue
            try {
                handleBridgeCommand(command)
            } catch (e: Exception) {
                synchronized(activeCommandIds) { activeCommandIds.remove(id) }
                android.util.Log.e("MainActivity", "Command handling failed; command retained in queue: " + id, e)
            }
        }
    }

    private fun removeQueuedCommand(id: String) {
        val prefs = getSharedPreferences(BridgeNotificationService.PREFS, MODE_PRIVATE)
        synchronized(BridgeNotificationService.QUEUE_LOCK) {
            val current = try { JSONArray(prefs.getString(BridgeNotificationService.KEY_PENDING, "[]")) } catch (_: Exception) { JSONArray() }
            val kept = JSONArray()
            for (i in 0 until current.length()) {
                val item = current.optJSONObject(i) ?: continue
                if (item.optString("id") != id) kept.put(item)
            }
            prefs.edit().putString(BridgeNotificationService.KEY_PENDING, kept.toString()).commit()
        }
        synchronized(activeCommandIds) { activeCommandIds.remove(id) }
    }

    /**
     * Persist the local outcome before acknowledging it remotely. If the network
     * fails after the contact operation, the next queue drain retries only the
     * acknowledgement instead of executing the contact operation again.
     */
    private fun persistCommandOutcome(id: String, result: JSONObject?, error: String?) {
        val prefs = getSharedPreferences(BridgeNotificationService.PREFS, MODE_PRIVATE)
        synchronized(BridgeNotificationService.QUEUE_LOCK) {
            val current = try {
                JSONArray(prefs.getString(BridgeNotificationService.KEY_PENDING, "[]"))
            } catch (_: Exception) {
                JSONArray()
            }
            var found = false
            for (i in 0 until current.length()) {
                val item = current.optJSONObject(i) ?: continue
                if (item.optString("id") != id) continue
                val updated = JSONObject(item.toString())
                    .put("_local_execution_done", true)
                    .put("_local_result", result ?: JSONObject.NULL)
                    .put("_local_error", error ?: JSONObject.NULL)
                current.put(i, updated)
                found = true
                break
            }
            if (!found) throw IllegalStateException("Cannot persist outcome: command " + id + " is not in local queue")
            if (!prefs.edit().putString(BridgeNotificationService.KEY_PENDING, current.toString()).commit()) {
                throw IllegalStateException("Could not persist outcome for command " + id)
            }
        }
    }

    private fun completeBridgeCommand(id: String, result: JSONObject? = null, error: String? = null) {
        try {
            persistCommandOutcome(id, result, error)
            bridge.complete(id, result, error)
            removeQueuedCommand(id)
        } catch (e: Exception) {
            synchronized(activeCommandIds) { activeCommandIds.remove(id) }
            android.util.Log.e("MainActivity", "Could not complete command " + id + "; retained for acknowledgement retry", e)
            throw e
        }
    }

    private fun bridgeDialog(name: String = "ChatGPT") {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 12, 40, 0) }
        val token = EditText(this).apply {
            hint = "Административный токен для привязки"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val info = TextView(this).apply {
            text = "Для первичной привязки вставьте административный токен из защищённой настройки MCP. Он используется только один раз и не сохраняется. Сервер выдаст отдельный токен устройства; приложение сохранит только его. Не вставляйте сюда anon key, service_role key или токен устройства другого приложения."
            setPadding(0, 0, 0, 16)
        }
        box.addView(info)
        if (name == "Claude") {
            val how = TextView(this).apply {
                text = "Чтобы Claude видел контакты, добавьте адрес MCP-сервера в Claude: Настройки → Коннекторы → Добавить свой коннектор. " +
                    "Токен устройства ниже нужен только телефону; доступ самого Claude к серверу настраивается отдельно и сюда не вставляется."
                setPadding(0, 0, 0, 12)
            }
            val copy = android.widget.Button(this).apply {
                text = "Копировать адрес MCP"
                setOnClickListener {
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("MCP", ContactsBridge.MCP_URL))
                    toast("Адрес MCP скопирован")
                }
            }
            box.addView(how); box.addView(copy)
        }
        box.addView(token)
        AlertDialog.Builder(this).setTitle("Привязка устройства — " + name)
            .setView(box)
            .setPositiveButton("Привязать и синхронизировать") { _, _ ->
                val value = token.text.toString().trim()
                if (value.isBlank()) { toast("Введите административный токен для привязки"); return@setPositiveButton }
                bg {
                    var paired = false
                    try {
                        bridge.pairDevice(value)
                        paired = true
                        val summary = syncContacts()
                        runOnUiThread {
                            showSyncResult(summary)
                            startBridgeService()
                            toast("Устройство привязано. Административный токен не сохранён.")
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            val prefix = if (paired) "Устройство привязано, но синхронизация не завершилась" else "Ошибка привязки"
                            toast("$prefix: ${e.message}")
                            if (paired) startBridgeService()
                        }
                    }
                }
            }
            .setNeutralButton("Синхронизировать сейчас") { _, _ ->
                bg {
                    try {
                        val summary = syncContacts()
                        runOnUiThread { showSyncResult(summary) }
                    } catch (e: Exception) { runOnUiThread { toast("Ошибка связи: ${e.message}") } }
                }
            }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun showSyncResult(summary: SyncSummary) {
        val conflicts = summary.conflicts
        val manifestStatus = if (summary.manifestRecorded) {
            "Реестр ID: записан."
        } else {
            "Реестр ID: не записан (${summary.manifestError ?: "ошибка"})."
        }
        val message = "Проверено: ${summary.attempted}. Уникальных: ${summary.unique}. Добавлено: ${summary.inserted}. Обновлено: ${summary.updated}. Без изменений: ${summary.skipped}. Конфликтов: ${conflicts.size}. Повторов в пакетах: ${summary.duplicates}. $manifestStatus"
        status.text = message
        toast(message)
        if (conflicts.isNotEmpty() && !isFinishing && !isDestroyed) {
            val details = conflicts.entries.take(50).joinToString("\n") { (id, fields) ->
                if (fields.isEmpty()) id else "$id — ${fields.joinToString(", ")}"
            }
            AlertDialog.Builder(this).setTitle("Расхождения данных (${conflicts.size})")
                .setMessage(details).setPositiveButton("Закрыть", null).show()
        }
    }

    private fun contactJson(c: Contact): JSONObject = JSONObject()
        .put("id", c.id).put("name", c.name).put("phones", JSONArray(c.phones))

    private fun handleBridgeCommand(command: JSONObject) {
        val id = command.optString("id")
        if (id.isBlank()) return

        // An earlier attempt may have changed the phone successfully but failed
        // to acknowledge the result to Supabase. Retry the acknowledgement only.
        if (command.optBoolean("_local_execution_done", false)) {
            val savedResult = command.optJSONObject("_local_result")
            val savedError = if (command.isNull("_local_error")) null else command.optString("_local_error").takeIf { it.isNotBlank() }
            bg {
                try {
                    completeBridgeCommand(id, savedResult, savedError)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Acknowledgement retry failed for command " + id, e)
                }
            }
            return
        }

        val action = command.optString("action")
        val payload = command.optJSONObject("payload") ?: JSONObject()
        when (action) {
            "list_contacts", "search_contacts" -> {
                val q = payload.optString("query").trim().lowercase()
                val limit = payload.optInt("limit", 100).coerceIn(1, 500)
                val rows = contactsForSync().filter { c ->
                    action == "list_contacts" || c.name.lowercase().contains(q) ||
                        c.phones.any { it.contains(q) }
                }.take(limit)
                val arr = JSONArray()
                rows.forEach { arr.put(contactJson(it)) }
                bg { try { completeBridgeCommand(id, JSONObject().put("contacts", arr).put("count", rows.size)) } catch (_: Exception) {} }
            }
            "upsert_contact" -> {
                runOnUiThread {
                    val contactId = payload.optLong("id", -1)
                    val name = payload.optString("name").trim()
                    val phone = payload.optString("phone").trim()
                    if (name.isBlank()) {
                        bg { try { completeBridgeCommand(id, error = "Поле name обязательно") } catch (_: Exception) {} }
                    } else {
                        AlertDialog.Builder(this).setTitle("ИИ предлагает изменить контакт")
                            .setMessage((if (contactId > 0) "Изменить" else "Создать") + " контакт?\nИмя: $name\nТелефон: $phone")
                            .setPositiveButton("Разрешить") { _, _ ->
                                bg {
                                    try {
                                        val current = if (contactId > 0) repo.loadAll(includeFields = false).firstOrNull { it.id == contactId } else null
                                        if (contactId > 0 && current == null) throw IllegalStateException("Контакт не найден")
                                        val target = writableAccountFor(current)
                                            ?: throw IllegalStateException("Нет разрешённого для записи аккаунта. Проверьте настройки аккаунтов синхронизации.")
                                        if (current != null) repo.updateInAccount(contactId, name, phone, target)
                                        else repo.addToAccount(name, phone, target)
                                        val found = repo.loadAll(includeFields = false).firstOrNull { it.name == name && (phone.isBlank() || phone in it.phones) }
                                        completeBridgeCommand(id, JSONObject().put("ok", true).put("contact", found?.let { contactJson(it) } ?: JSONObject()))
                                        runOnUiThread { refresh(); toast("Контакт сохранён") }
                                    } catch (e: Exception) { try { completeBridgeCommand(id, error = e.message ?: "Ошибка записи") } catch (_: Exception) {} }
                                }
                            }
                            .setNegativeButton("Отклонить") { _, _ -> bg { try { completeBridgeCommand(id, error = "Отклонено пользователем на телефоне") } catch (_: Exception) {} } }
                            .show()
                    }
                }
            }
            "update_telegram_fields" -> {
                bg {
                    try {
                        val contactId = payload.optLong("id", -1)
                        require(contactId > 0) { "Некорректный ID контакта" }
                        val telegramId = payload.optString("telegram_id", "")
                        val username = payload.optString("telegram_username", "")
                        val contact = repo.loadAll(includeFields = false).firstOrNull { it.id == contactId }
                            ?: throw IllegalStateException("Контакт не найден")
                        val target = writableAccountFor(contact)
                            ?: throw IllegalStateException("Нет аккаунта контакта с разрешённой записью")
                        repo.updateTelegramLookupInAccount(contactId, telegramId, username, target)
                        completeBridgeCommand(id, JSONObject().put("ok", true).put("contact_id", contactId))
                        runOnUiThread { refresh(); toast("Telegram-данные контакта обновлены") }
                    } catch (e: Exception) {
                        try { completeBridgeCommand(id, error = e.message ?: "Не удалось обновить Telegram-поля") } catch (_: Exception) {}
                    }
                }
            }
            "delete_contact" -> {
                runOnUiThread {
                    val contactId = payload.optLong("id", -1)
                    val c = repo.loadAll(includeFields = false).firstOrNull { it.id == contactId }
                    if (c == null) bg { try { completeBridgeCommand(id, error = "Контакт не найден") } catch (_: Exception) {} }
                    else AlertDialog.Builder(this).setTitle("Удалить контакт?")
                        .setMessage("${c.name}\n${c.phones.joinToString(", ")}")
                        .setPositiveButton("Удалить") { _, _ ->
                            bg { try {
                                val target = writableAccountFor(c)
                                    ?: throw IllegalStateException("Нет аккаунта контакта с разрешённой записью")
                                repo.deleteFromAccount(c.id, target)
                                completeBridgeCommand(id)
                                runOnUiThread { refresh(); toast("Контакт удалён из аккаунта: " + target.label) }
                            } catch (e: Exception) { try { completeBridgeCommand(id, error = e.message ?: "Ошибка удаления") } catch (_: Exception) {} } }
                        }
                        .setNegativeButton("Отклонить") { _, _ -> bg { try { completeBridgeCommand(id, error = "Отклонено пользователем на телефоне") } catch (_: Exception) {} } }
                        .show()
                }
            }
            "merge_contacts" -> {
                runOnUiThread {
                    val ids = payload.optJSONArray("ids") ?: JSONArray()
                    val group = (0 until ids.length()).mapNotNull { ix ->
                        val cid = ids.optLong(ix, -1); repo.loadAll(includeFields = false).firstOrNull { it.id == cid }
                    }.distinctBy { it.id }
                    if (group.size < 2) bg { try { completeBridgeCommand(id, error = "Для объединения нужны минимум два контакта") } catch (_: Exception) {} }
                    else AlertDialog.Builder(this).setTitle("Объединить контакты?")
                        .setMessage(group.joinToString("\n") { it.name + " — " + it.phones.joinToString(", ") })
                        .setPositiveButton("Объединить") { _, _ ->
                            bg { try {
                                val target = repo.accounts().map { it.first }.firstOrNull { account ->
                                    accountFlag(account, "enabled") && accountFlag(account, "write") &&
                                        group.all { account in it.accountSources }
                                } ?: throw IllegalStateException("Для объединения включите запись в одном аккаунте, содержащем все выбранные контакты")
                                repo.mergeInAccount(group, target)
                                completeBridgeCommand(id)
                                runOnUiThread { refresh(); toast("Контакты объединены в аккаунте: " + target.label) }
                            } catch (e: Exception) { try { completeBridgeCommand(id, error = e.message ?: "Ошибка объединения") } catch (_: Exception) {} } }
                        }
                        .setNegativeButton("Отклонить") { _, _ -> bg { try { completeBridgeCommand(id, error = "Отклонено пользователем на телефоне") } catch (_: Exception) {} } }
                        .show()
                }
            }
            "sync_contacts" -> bg {
try {
                    val summary = syncContacts()
                    completeBridgeCommand(id, summary.toJson())
                    runOnUiThread { showSyncResult(summary) }
                }
                catch (e: Exception) { try { completeBridgeCommand(id, error = e.message ?: "Ошибка синхронизации") } catch (_: Exception) {} }
            }
            else -> bg { try { completeBridgeCommand(id, error = "Неподдерживаемая команда: $action") } catch (_: Exception) {} }
        }
    }


    // Account-level permissions apply to bridge synchronization and remote write commands.
    // If an aggregated contact spans both allowed and disabled accounts, omit the whole
    // aggregate rather than leaking fields from the disabled account.
    private val accountSyncPrefs by lazy { getSharedPreferences("account_sync", MODE_PRIVATE) }

    private fun accountKey(account: Acc): String {
        val raw = (account.type ?: "") + "|" + (account.name ?: "")
        return "account_" + android.util.Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }

    private fun accountFlag(account: Acc, flag: String, defaultValue: Boolean = true): Boolean =
        accountSyncPrefs.getBoolean(accountKey(account) + "_" + flag, defaultValue)

    private fun allowedReadAccounts(): Set<Acc> = repo.accounts().map { it.first }
        .filter { accountFlag(it, "enabled") && accountFlag(it, "read") }
        .toSet()

    /** Lightweight contact rows for remote list/search commands. */
    private fun contactsForSync(): List<Contact> {
        val allowed = allowedReadAccounts()
        if (allowed.isEmpty()) return emptyList()
        return repo.loadAll(includeFields = false).filter { contact ->
            contact.accountSources.isNotEmpty() && contact.accountSources.all { it in allowed }
        }
    }

    /** Full-field sync is paged at the provider and HTTP layers. */
    private fun syncContacts(): SyncSummary =
        bridge.syncBatches(repo.syncBatches(allowedReadAccounts(), batchSize = 100))

    private fun writableAccountFor(contact: Contact?): Acc? =
        repo.accounts().map { it.first }.firstOrNull { account ->
            accountFlag(account, "enabled") && accountFlag(account, "write") &&
                (contact == null || account in contact.accountSources)
        }

    private fun showAccountSyncSettings() {
        bg {
            val accounts = repo.accounts().map { it.first }
            runOnUiThread {
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 12, 24, 12)
                }
                accounts.forEach { account ->
                    box.addView(TextView(this).apply {
                        text = account.label
                        textSize = 16f
                        setPadding(0, 14, 0, 2)
                    })
                    val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    fun option(label: String, flag: String) =
                        android.widget.CheckBox(this).apply {
                            text = label
                            isChecked = accountFlag(account, flag)
                            setOnCheckedChangeListener { _, checked ->
                                accountSyncPrefs.edit()
                                    .putBoolean(accountKey(account) + "_" + flag, checked)
                                    .apply()
                            }
                        }
                    options.addView(option("Участвует в синхронизации", "enabled"))
                    options.addView(option("Чтение: передавать контакты этого аккаунта", "read"))
                    options.addView(option("Запись: разрешать изменения в этом аккаунте", "write"))
                    box.addView(options)
                    box.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        )
                        setBackgroundColor(0xFFCCCCCC.toInt())
                    })
                }
                if (accounts.isEmpty()) {
                    box.addView(TextView(this).apply { text = "Нет доступных аккаунтов контактов." })
                }
                val scroll = android.widget.ScrollView(this).apply { addView(box) }
                AlertDialog.Builder(this)
                    .setTitle("Аккаунты синхронизации")
                    .setMessage("Настройки сохраняются сразу. Запись через ChatGPT/Claude разрешена только для включённых аккаунтов с активной записью. Если один контакт объединён из нескольких аккаунтов и хотя бы один из них отключён для чтения, этот контакт не передаётся. После полной синхронизации исключённые контакты удаляются из серверного снимка.")
                    .setView(scroll)
                    .setPositiveButton("Готово", null)
                    .show()
            }
        }
    }

    private fun refresh() {
        val generation = ++refreshGeneration
        status.text = "Загрузка контактов…"
        bg {
            try {
                val loaded = repo.loadAll(includeFields = false)
                runOnUiThread {
                    if (isFinishing || isDestroyed || generation != refreshGeneration) return@runOnUiThread
                    all = loaded
                    val ids = loaded.asSequence().map { it.id }.toHashSet()
                    selected.retainAll(ids)
                    applyFilter()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && generation == refreshGeneration) {
                        toast("Ошибка загрузки контактов: ${e.message}")
                    }
                }
            }
        }
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val digits = q.filter { it.isDigit() }
        shown = if (q.isEmpty()) all else all.filter { c ->
            c.name.lowercase().contains(q) ||
                (digits.isNotEmpty() && c.phones.any { it.filter { ch -> ch.isDigit() }.contains(digits) })
        }
        updateStatus()
        list.adapter = object : ArrayAdapter<Contact>(this, android.R.layout.simple_list_item_2, shown) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val c = getItem(position)!!
                val on = c.id in selected
                v.findViewById<TextView>(android.R.id.text1).text = (if (on) "✓ " else "") + c.name
                v.findViewById<TextView>(android.R.id.text2).text = listOf(c.phones.joinToString(", "), c.telegramId.takeIf { it.isNotBlank() }?.let { "Telegram ID: $it" }, c.telegramUsername.takeIf { it.isNotBlank() }?.let { if (it.equals("Скрыт", ignoreCase = true)) "Telegram: Скрыт" else "Telegram: " + it.split(",").map { name -> "@" + name.trim().removePrefix("@") }.joinToString(", ") }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
                v.setBackgroundColor(if (on) 0x3366BB6A else 0)
                return v
            }
        }
    }

    private fun updateStatus() {
        status.text = "Контактов: " + shown.size + " из " + all.size + ". Выбрано: " + selected.size +
            (if (selected.isEmpty()) ". Нажатие — правка, долгое — выбрать." else ". Нажатие — отметить.")
    }

    private fun refreshRows() {
        (list.adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
        updateStatus()
    }

    private fun toggle(c: Contact) {
        if (!selected.remove(c.id)) selected.add(c.id)
        refreshRows()
    }

    private fun selectAll() {
        selected.addAll(shown.map { it.id })
        refreshRows()
    }

    private fun clearSelection() {
        selected.clear()
        refreshRows()
    }

    private fun deleteSelected() {
        if (selected.isEmpty()) { toast("Ничего не выбрано"); return }
        val ids = selected.toList()
        AlertDialog.Builder(this).setTitle("Удалить выбранные контакты (" + ids.size + ")?")
            .setMessage("Действие необратимо.")
            .setPositiveButton("Удалить") { _, _ ->
                bg {
                    for (id in ids) repo.delete(id)
                    runOnUiThread { selected.clear(); refresh(); toast("Удалено: " + ids.size) }
                }
            }
            .setNegativeButton("Отмена", null).show()
    }

    /** Экспортируются выбранные контакты, а если ничего не выбрано, то все. */
    private fun exportList(): List<Contact> = if (selected.isEmpty()) all else all.filter { it.id in selected }

    /**
     * Excel export can use the merged aggregate list or a single Android account.
     * Account-scoped exports are built from RawContacts/Data rows, so numbers from
     * other accounts merged into the same aggregate contact are not included.
     */
    private fun exportXlsxDialog() {
        bg {
            val accounts = repo.accounts()
            runOnUiThread {
                val labels = listOf("Все контакты (объединённый список) — ${exportList().size}") +
                    accounts.map { it.first.label + " — " + it.second + " записей" }
                AlertDialog.Builder(this)
                    .setTitle("Экспорт Excel: источник контактов")
                    .setItems(labels.toTypedArray()) { _, index ->
                        if (index == 0) {
                            pendingXlsxExport = exportList().toList()
                            createDoc("contacts.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", reqExportXlsx)
                        } else {
                            val acc = accounts[index - 1].first
                            bg {
                                try {
                                    val scoped = repo.entriesOf(acc).map { entry ->
                                        Contact(entry.rawId, entry.name.ifBlank { "(без имени)" },
                                            entry.phones.map { it.first }.distinct(), listOf(acc))
                                    }
                                    runOnUiThread {
                                        pendingXlsxExport = scoped
                                        toast("Источник: ${acc.label}; строк контактов: ${scoped.size}")
                                        createDoc("contacts.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", reqExportXlsx)
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread { toast("Не удалось прочитать аккаунт: ${e.message}") }
                                }
                            }
                        }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
    }

    private fun editDialog(c: Contact?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val name = EditText(this).apply { hint = "Имя"; setText(c?.name ?: "") }
        val phone = EditText(this).apply {
            hint = "Телефон"; inputType = InputType.TYPE_CLASS_PHONE; setText(c?.phones?.firstOrNull() ?: "")
        }
        box.addView(name); box.addView(phone)
        AlertDialog.Builder(this).setTitle(if (c == null) "Новый контакт" else "Правка")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) { toast("Введите имя"); return@setPositiveButton }
                try {
                    if (c == null) repo.add(n, phone.text.toString().trim())
                    else repo.update(c.id, n, phone.text.toString().trim())
                } catch (e: Exception) { toast("Ошибка: ${e.message}") }
                refresh()
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun confirmDelete(c: Contact) {
        AlertDialog.Builder(this).setTitle("Удалить «${c.name}»?")
            .setPositiveButton("Удалить") { _, _ -> repo.delete(c.id); refresh() }
            .setNegativeButton("Отмена", null).show()
    }

    private fun duplicatesDialog() {
        val groups = repo.findDuplicates(all)
        if (groups.isEmpty()) { toast("Дубликатов не найдено"); return }
        val text = groups.joinToString("\n\n") { g -> g.joinToString("\n") { "${it.name}  ${it.phones.firstOrNull() ?: ""}" } }
        AlertDialog.Builder(this).setTitle("Групп дубликатов: ${groups.size}")
            .setMessage(text)
            .setPositiveButton("Объединить все") { _, _ ->
                try { groups.forEach { repo.merge(it) } } catch (e: Exception) { toast("Ошибка: ${e.message}") }
                refresh(); toast("Готово")
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun createDoc(name: String, mime: String, code: Int) {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = mime; putExtra(Intent.EXTRA_TITLE, name)
        }, code)
    }

    private fun pickImport() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, reqImport)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        val uri: Uri = data?.data ?: return
        if (result != RESULT_OK) return
        try {
            when (code) {
                reqExportVcf -> write(uri, repo.toVcf(exportList()))
                reqExportCsv -> write(uri, repo.toCsv(exportList()))
                reqExportFullCsv -> {
                    toast("Формируется полный CSV…")
                    bg {
                        val csv = repo.toFullFieldsCsv()
                        val rows = csv.lineSequence().drop(1).count { it.isNotBlank() }
                        if (rows == 0) {
                            throw IllegalStateException("Системная база не вернула ни одной строки данных. Файл не сохранён.")
                        }
                        val output = contentResolver.openOutputStream(uri, "wt")
                            ?: throw java.io.IOException("Android не открыл файл для записи")
                        output.use {
                            it.write(csv.toByteArray(Charsets.UTF_8))
                            it.flush()
                        }
                        runOnUiThread { toast("Полный экспорт сохранён: $rows строк полей") }
                    }
                }
                reqExportXlsx -> writeBytes(uri, repo.toXlsx(pendingXlsxExport ?: exportList()))
                reqImport -> importFrom(uri)
            }
        } catch (e: Exception) { toast("Ошибка: ${e.message}") }
    }

    private fun write(uri: Uri, text: String) {
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        toast("Экспортировано: ${exportList().size}")
    }

    private fun writeBytes(uri: Uri, bytes: ByteArray) {
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
        val count = pendingXlsxExport?.size ?: exportList().size
        toast("Экспортировано в Excel: $count")
        pendingXlsxExport = null
    }

    private fun importFrom(uri: Uri) {
        val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
        val items = if (text.contains("BEGIN:VCARD", true)) repo.parseVcf(text) else repo.parseCsv(text)
        val existing = all.map { it.name.lowercase() + "|" + (it.phones.firstOrNull() ?: "") }.toSet()
        var added = 0
        for ((n, p) in items) {
            if ((n.lowercase() + "|" + p) in existing) continue
            repo.add(n, p); added++
        }
        refresh()
        toast("Импортировано: $added из ${items.size}")
    }

    private fun bg(work: () -> Unit) = Thread {
        try { work() } catch (e: Exception) {
            AppLog.error(this, "MainActivity", "Background operation failed", e)
            runOnUiThread { toast("Ошибка: " + e.message) }
        }
    }.start()

    private fun transferDialog() {
        AlertDialog.Builder(this).setTitle("Операция с аккаунтом")
            .setItems(arrayOf("Копировать все контакты и номера", "Переместить все контакты и номера")) { _, mode ->
                val move = mode == 1
                bg {
                    val accs = repo.accounts()
                    runOnUiThread {
                        if (accs.size < 2) {
                            toast("Для переноса нужны как минимум два аккаунта")
                            return@runOnUiThread
                        }
                        val labels = accs.map { it.first.label + " — " + it.second }.toTypedArray()
                        AlertDialog.Builder(this).setTitle("Выберите исходный аккаунт")
                            .setItems(labels) { _, i -> pickTarget(accs.map { it.first }, accs[i].first, accs[i].second, move) }
                            .setNegativeButton("Отмена", null).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun pickTarget(accs: List<Acc>, src: Acc, count: Int, move: Boolean) {
        val targets = accs.filter { it != src }
        AlertDialog.Builder(this).setTitle("Выберите целевой аккаунт")
            .setItems(targets.map { it.label }.toTypedArray()) { _, i ->
                val dst = targets[i]
                val action = if (move) "переместить" else "скопировать"
                val removal = if (move) {
                    "\n\nИсходные контакты будут удалены только после проверки всех копий в целевом аккаунте."
                } else {
                    "\n\nИсходные контакты останутся без изменений."
                }
                AlertDialog.Builder(this).setTitle("Подтвердите операцию")
                    .setMessage("Операция: $action $count контактов из «" + src.label + "» в «" + dst.label + "»." +
                        removal + "\n\nПереносятся имена, все номера телефонов, адреса электронной почты и Telegram-поле.")
                    .setPositiveButton(if (move) "Переместить" else "Копировать") { _, _ -> runTransfer(src, dst, move) }
                    .setNegativeButton("Отмена", null).show()
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun runTransfer(src: Acc, dst: Acc, move: Boolean) {
        toast(if (move) "Идёт копирование для переноса…" else "Идёт копирование…")
        bg {
            val r = repo.transfer(src, dst)
            runOnUiThread { refresh(); showTransferResult(r, src, move) }
        }
    }

    private fun showTransferResult(r: TransferResult, src: Acc, move: Boolean) {
        val verified = r.total > 0 && r.verified == r.total
        val msg = "Найдено в источнике: " + r.total + "\nСкопировано: " + r.copied +
            "\nПроверено в целевом аккаунте: " + r.verified +
            (if (r.missing.isNotEmpty()) "\nНе подтверждено: " + r.missing.take(20).joinToString(", ") else "") +
            (if (verified) "\n\nВсе контакты подтверждены в целевом аккаунте." else
                "\n\nИсходные контакты сохранены. Удаление запрещено, пока все копии не подтверждены.")
        val builder = AlertDialog.Builder(this).setTitle(if (move) "Результат перемещения" else "Результат копирования").setMessage(msg)
        if (move && verified) {
            builder.setPositiveButton("Удалить исходные контакты") { _, _ ->
                AlertDialog.Builder(this).setTitle("Последнее подтверждение")
                    .setMessage("Будут удалены " + r.total + " исходных записей аккаунта «" + src.label +
                        "». Проверенные копии останутся в целевом аккаунте. Продолжить?")
                    .setPositiveButton("Удалить") { _, _ ->
                        bg {
                            repo.deleteRaws(r.rawIds)
                            val remaining = repo.entriesOf(src).count { it.rawId in r.rawIds }
                            runOnUiThread {
                                refresh()
                                if (remaining == 0) toast("Перемещение завершено: исходные записи удалены")
                                else toast("Удаление выполнено не полностью: осталось записей $remaining")
                            }
                        }
                    }
                    .setNegativeButton("Оставить", null).show()
            }
            builder.setNegativeButton("Оставить исходные", null)
        } else builder.setPositiveButton("Закрыть", null)
        builder.show()
    }

    /** Передаёт контакты в установленное приложение ChatGPT через системное меню Android. */
    private fun shareWithChatGPT() {
        val contacts = exportList().take(7000)
        if (contacts.isEmpty()) { toast("Нет контактов для передачи"); return }
        val payload = buildString {
            append("Помоги обработать мои контакты. Сначала анализируй и предлагай изменения, не выдумывай данные. ")
            append("Если предлагаешь исправления, верни их списком: исходное имя и номер → предлагаемое изменение. ")
            append("Не применяй изменения автоматически. Контактов в этом сообщении: ")
            append(contacts.size).append("\n\n")
            contacts.forEach { c ->
                append(c.name.replace("\n", " ").replace("\r", " "))
                val phones = c.phones.joinToString(", ")
                if (phones.isNotBlank()) append(": ").append(phones)
                append("\n")
            }
            if (exportList().size > contacts.size) append("\nПереданы первые 7000 контактов; всего выбрано/доступно: ").append(exportList().size)
        }
        AlertDialog.Builder(this)
            .setTitle("Передать контакты в ChatGPT?")
            .setMessage("Будут переданы имена и номера телефонов " + contacts.size + " контактов. Передача произойдёт только после выбора приложения в следующем окне. Не отправляйте данные без согласия, если в контактах есть чужая конфиденциальная информация.")
            .setPositiveButton("Продолжить") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Контакты для анализа")
                    putExtra(Intent.EXTRA_TEXT, payload)
                }
                try { startActivity(Intent.createChooser(send, "Передать данные в ChatGPT или другое приложение")) }
                catch (e: Exception) { toast("Не удалось открыть меню передачи: " + e.message) }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun aiDialog() {
        val prefs = getSharedPreferences("ai", MODE_PRIVATE)
        var provider = prefs.getString("provider", Ai.OPENAI) ?: Ai.OPENAI
        fun keyPref(p: String) = if (p == Ai.CLAUDE) "key_claude" else "key"
        fun modelPref(p: String) = if (p == Ai.CLAUDE) "model_claude" else "model"
        fun defModel(p: String) = if (p == Ai.CLAUDE) "claude-haiku-4-5" else "gpt-4o-mini"
        fun label(p: String) = if (p == Ai.CLAUDE) "Claude" else "ChatGPT"

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val group = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val rbGpt = android.widget.RadioButton(this).apply { text = "ChatGPT"; id = View.generateViewId() }
        val rbClaude = android.widget.RadioButton(this).apply { text = "Claude"; id = View.generateViewId() }
        group.addView(rbGpt); group.addView(rbClaude)
        val key = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val model = EditText(this).apply { hint = "Модель"; setSingleLine() }
        val withData = android.widget.CheckBox(this)
        val q = EditText(this).apply { hint = "Вопрос или задача, например: найди контакты, которые похожи на дубликаты" }
        fun load() {
            key.hint = if (provider == Ai.CLAUDE) "Anthropic API-ключ" else "OpenAI API-ключ"
            key.setText(prefs.getString(keyPref(provider), ""))
            model.setText(prefs.getString(modelPref(provider), defModel(provider)))
            withData.text = "Отправить " + label(provider) + " список контактов (имена и номера, до 7000)"
        }
        group.setOnCheckedChangeListener { _, id ->
            provider = if (id == rbClaude.id) Ai.CLAUDE else Ai.OPENAI
            load()
        }
        group.check(if (provider == Ai.CLAUDE) rbClaude.id else rbGpt.id)
        listOf<View>(group, key, model, withData, q).forEach { box.addView(it) }
        val sv = android.widget.ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("ИИ-помощник")
            .setView(sv)
            .setPositiveButton("Спросить") { _, _ ->
                val pr = provider
                val k = key.text.toString().trim()
                val m = model.text.toString().trim().ifEmpty { defModel(pr) }
                val question = q.text.toString().trim()
                if (k.isEmpty() || question.isEmpty()) { toast("Нужны ключ и вопрос"); return@setPositiveButton }
                prefs.edit().putString("provider", pr).putString(keyPref(pr), k).putString(modelPref(pr), m).apply()
                val ctx = if (withData.isChecked)
                    "\n\nКонтакты:\n" + all.take(7000).joinToString("\n") { it.name + ": " + it.phones.joinToString(", ") }
                else ""
                toast("Запрос отправлен…")
                bg {
                    val reply = Ai.ask(k, m, "Ты помощник по работе с контактами. Отвечай по-русски, кратко.", question + ctx, pr)
                    runOnUiThread {
                        val tv = TextView(this).apply { text = reply; setTextIsSelectable(true); setPadding(48, 24, 48, 0) }
                        AlertDialog.Builder(this).setTitle("Ответ " + label(pr))
                            .setView(android.widget.ScrollView(this).apply { addView(tv) })
                            .setPositiveButton("Закрыть", null).show()
                    }
                }
            }
            .setNeutralButton("Правки") { _, _ ->
                val pr = provider
                val k = key.text.toString().trim()
                val m = model.text.toString().trim().ifEmpty { defModel(pr) }
                if (k.isEmpty()) { toast("Нужен API-ключ"); return@setNeutralButton }
                prefs.edit().putString("provider", pr).putString(keyPref(pr), k).putString(modelPref(pr), m).apply()
                suggestEdits(k, m, q.text.toString().trim(), pr)
            }
            .setNegativeButton("Отмена", null).show()
    }

    private data class Proposal(val c: Contact, val name: String, val phone: String)

    private fun suggestEdits(k: String, m: String, wish: String, provider: String = Ai.OPENAI) {
        val data = all.take(7000).joinToString("\n") { it.id.toString() + "|" + it.name + "|" + it.phones.joinToString(",") }
        val system = "Ты помощник по приведению контактов в порядок. Верни ТОЛЬКО JSON без пояснений и без markdown: " +
            "{\"edits\":[{\"id\":число,\"name\":\"новое имя\",\"phone\":\"новый номер\"}]}. " +
            "Включай только контакты, которые нужно изменить. Ничего не выдумывай: исправляй регистр, лишние пробелы, " +
            "опечатки в очевидных случаях и формат номера. Если менять нечего, верни {\"edits\":[]}."
        val user = (if (wish.isNotEmpty()) "Пожелание: " + wish + "\n\n" else "") + "Формат строк: id|имя|телефоны\n" + data
        toast("Запрос отправлен, контакты уходят в " + (if (provider == Ai.CLAUDE) "Anthropic" else "OpenAI") + "…")
        bg {
            val reply = Ai.ask(k, m, system, user, provider)
            val json = reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1)
            val arr = org.json.JSONObject(json).getJSONArray("edits")
            val byId = all.associateBy { it.id }
            val props = ArrayList<Proposal>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = byId[o.optLong("id", -1)] ?: continue
                val nn = o.optString("name", c.name).trim()
                val np = o.optString("phone", c.phones.firstOrNull() ?: "").trim()
                if (nn.isBlank()) continue
                val phoneChanged = c.phones.size <= 1 && np != (c.phones.firstOrNull() ?: "")
                if (nn == c.name && !phoneChanged) continue
                props.add(Proposal(c, nn, if (c.phones.size <= 1) np else (c.phones.firstOrNull() ?: "")))
            }
            runOnUiThread { showProposals(props) }
        }
    }

    private fun showProposals(props: List<Proposal>) {
        if (props.isEmpty()) { toast("ИИ не предложил правок"); return }
        val checked = BooleanArray(props.size) { true }
        val labels = props.map {
            it.c.name + " " + (it.c.phones.firstOrNull() ?: "") + "\n→ " + it.name + " " + it.phone
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Правки: " + props.size + ". Снимите галочки с лишнего")
            .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton("Применить") { _, _ ->
                bg {
                    var n = 0
                    for ((i, p) in props.withIndex()) if (checked[i]) { repo.update(p.c.id, p.name, p.phone); n++ }
                    runOnUiThread { refresh(); toast("Применено правок: " + n) }
                }
            }
            .setNegativeButton("Отмена", null).show()
    }



    private fun queueTelegramMatch() {
        // Give immediate feedback; contact sync and server request run in the background.
        status.text = "Серверная проверка: подготовка контактов и отправка запроса…"
        toast("Запускаю серверную проверку…")
        AppLog.info(this, "TelegramResolver", "Server check button pressed")

        bg {
            try {
                AppLog.info(this, "TelegramResolver", "Contact synchronization started for server check")
                val summary = syncContacts()
                AppLog.info(this, "TelegramResolver", "Contact synchronization completed: attempted=${summary.attempted}, synced=${summary.synced}")
                val result = bridge.queueTelegramMatch()
                val count = result.optInt("unique_phone_count")
                val jobId = result.optString("job_id")
                val queued = result.optBoolean("queued")
                AppLog.info(this, "TelegramResolver", "Server check request completed: queued=$queued, jobId=$jobId, uniquePhones=$count")
                runOnUiThread {
                    showSyncResult(summary)
                    status.text = if (queued) {
                        "Серверная проверка поставлена в очередь. Номеров: $count. ID задачи: $jobId"
                    } else {
                        "Серверная проверка не запущена: нет международных номеров."
                    }
                    if (queued) {
                        toast("Серверная проверка поставлена в очередь: $count уникальных номеров.")
                    } else {
                        toast("Нет международных номеров для серверной проверки.")
                    }
                }
            } catch (e: Exception) {
                AppLog.error(this, "TelegramResolver", "Server check failed", e)
                runOnUiThread {
                    val message = e.message ?: e.javaClass.simpleName
                    status.text = "Серверная проверка: ошибка — $message"
                    toast("Не удалось выполнить серверную проверку: $message")
                }
            }
        }
    }

    private fun exportAppLogs() {
        bg {
            try {
                val path = AppLog.exportToDownloads(this)
                AppLog.info(this, "MainActivity", "Logs exported to $path")
                runOnUiThread { toast("Логи сохранены: $path") }
            } catch (e: Exception) {
                AppLog.error(this, "MainActivity", "Log export failed", e)
                runOnUiThread { toast("Не удалось сохранить логи: ${e.message}") }
            }
        }
    }

    private fun exportTermuxGuide() {
        bg {
            try {
                val guide = AppLog.exportTermuxGuide(this)
                val script = AppLog.exportTermuxSetupScript(this)
                AppLog.info(this, "MainActivity", "Termux files exported: $guide; $script")
                runOnUiThread { toast("Инструкция и скрипт сохранены в Загрузки/MyContacts") }
            } catch (e: Exception) {
                AppLog.error(this, "MainActivity", "Termux export failed", e)
                runOnUiThread { toast("Не удалось сохранить файлы: ${e.message}") }
            }
        }
    }


    private fun updateShizukuStatus() {
        shizukuStatus.text = when {
            !Shizuku.pingBinder() -> "Shizuku: не запущен или служба недоступна."
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                "Shizuku: разрешение выдано этому приложению (UID ${Shizuku.getUid()})."
            else -> "Shizuku: приложение ещё не авторизовано."
        }
    }

    private fun requestShizukuPermission() {
        if (Shizuku.isPreV11()) {
            toast("Эта версия Shizuku не поддерживается. Обновите Shizuku.")
            return
        }
        if (!Shizuku.pingBinder()) {
            AlertDialog.Builder(this)
                .setTitle("Shizuku недоступен")
                .setMessage("Запустите службу Shizuku и вернитесь сюда. После запуска нажмите «Доступ Shizuku» ещё раз.")
                .setPositiveButton("Открыть Shizuku") { _, _ ->
                    val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (launch != null) startActivity(launch)
                    else toast("Приложение Shizuku не найдено.")
                }
                .setNegativeButton("Закрыть", null)
                .show()
            updateShizukuStatus()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            toast("Разрешение Shizuku уже выдано этому приложению.")
            updateShizukuStatus()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Запросить доступ Shizuku")
            .setMessage(
                "Shizuku покажет системный запрос. Разрешение будет выдано приложению «Мои контакты — привязка» и позволит ему выполнять разрешённые команды с UID shell. " +
                "Это отдельное разрешение: Termux должен быть авторизован в Shizuku независимо."
            )
            .setPositiveButton("Запросить") { _, _ ->
                try {
                    Shizuku.requestPermission(shizukuPermissionRequestCode)
                } catch (error: Exception) {
                    toast("Не удалось запросить Shizuku: " + (error.message ?: error.javaClass.simpleName))
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Android requires the user to approve unknown-source installation per app.
     * This opens the system page for this exact package; the app cannot grant it itself.
     */
    private fun requestUnknownSourcesPermission() {
        if (android.os.Build.VERSION.SDK_INT < 26) {
            toast("На этой версии Android отдельное разрешение не требуется")
            return
        }
        if (packageManager.canRequestPackageInstalls()) {
            toast("Разрешение на установку APK уже выдано")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Разрешить установку APK")
            .setMessage(
                "Android откроет системную страницу разрешения для приложения «Мои контакты». " +
                "Включите переключатель «Разрешить установку из этого источника», затем вернитесь в приложение."
            )
            .setPositiveButton("Открыть настройки") { _, _ ->
                try {
                    waitingForInstallPermission = true
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    waitingForInstallPermission = false
                    toast("Не удалось открыть разрешение: " + (e.message ?: e.javaClass.simpleName))
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
