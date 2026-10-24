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

class MainActivity : Activity() {

    private lateinit var repo: ContactRepo
    private lateinit var bridge: ContactsBridge
    private val bridgeHandler = Handler(Looper.getMainLooper())
    private var bridgePolling = false
    private val activeCommandIds = HashSet<String>()

    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var status: TextView
    private var all: List<Contact> = emptyList()
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
        row2.addView(btn("ChatGPT") { aiDialog() })
        row3.addView(btn("Excel") { exportXlsxDialog() })
        row3.addView(btn("Выделить всё") { selectAll() })
        row3.addView(btn("Снять") { clearSelection() })
        row3.addView(btn("Удалить выбранные") { deleteSelected() })
        row4.addView(btn("Передать в ChatGPT") { shareWithChatGPT() })
        row5.addView(btn("Связь с ChatGPT") { bridgeDialog() })
        status = TextView(this)
        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { if (selected.isNotEmpty()) toggle(it) else editDialog(it) } }
        list.setOnItemLongClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { toggle(it) }; true }
        listOf<View>(search, row1, row2, row3, row4, row5, status, list).forEach { root.addView(it) }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        startBridgeService()
        drainBridgeQueue()
        startBridgeQueueDrain()
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

    private fun bridgeDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 12, 40, 0) }
        val token = EditText(this).apply {
            hint = "Токен устройства"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(bridge.token())
        }
        val info = TextView(this).apply {
            text = "После сохранения приложение будет синхронизировать список контактов с защищённым сервером и проверять команды, пока приложение открыто. Изменение и удаление потребуют подтверждения на телефоне."
            setPadding(0, 0, 0, 16)
        }
        box.addView(info); box.addView(token)
        AlertDialog.Builder(this).setTitle("Связь с ChatGPT")
            .setView(box)
            .setPositiveButton("Сохранить и синхронизировать") { _, _ ->
                val value = token.text.toString().trim()
                if (value.isBlank()) { toast("Введите токен устройства"); return@setPositiveButton }
                bridge.saveToken(value)
                bg {
                    try {
                        val n = bridge.sync(repo.loadAll())
                        runOnUiThread {
                            toast("Синхронизировано контактов: $n")
                            startBridgeService()
                        }
                    } catch (e: Exception) { runOnUiThread { toast("Ошибка связи: ${e.message}") } }
                }
            }
            .setNeutralButton("Синхронизировать сейчас") { _, _ ->
                bg {
                    try {
                        val n = bridge.sync(repo.loadAll())
                        runOnUiThread { toast("Синхронизировано контактов: $n") }
                    } catch (e: Exception) { runOnUiThread { toast("Ошибка связи: ${e.message}") } }
                }
            }
            .setNegativeButton("Закрыть", null).show()
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
                val rows = repo.loadAll().filter { c ->
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
                        AlertDialog.Builder(this).setTitle("ChatGPT предлагает изменить контакт")
                            .setMessage((if (contactId > 0) "Изменить" else "Создать") + " контакт?\nИмя: $name\nТелефон: $phone")
                            .setPositiveButton("Разрешить") { _, _ ->
                                bg {
                                    try {
                                        if (contactId > 0) repo.update(contactId, name, phone) else repo.add(name, phone)
                                        val found = repo.loadAll().firstOrNull { it.name == name && (phone.isBlank() || phone in it.phones) }
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
            "delete_contact" -> {
                runOnUiThread {
                    val contactId = payload.optLong("id", -1)
                    val c = repo.loadAll().firstOrNull { it.id == contactId }
                    if (c == null) bg { try { completeBridgeCommand(id, error = "Контакт не найден") } catch (_: Exception) {} }
                    else AlertDialog.Builder(this).setTitle("Удалить контакт?")
                        .setMessage("${c.name}\n${c.phones.joinToString(", ")}")
                        .setPositiveButton("Удалить") { _, _ ->
                            bg { try { repo.delete(c.id); completeBridgeCommand(id); runOnUiThread { refresh(); toast("Контакт удалён") } }
                                catch (e: Exception) { try { completeBridgeCommand(id, error = e.message ?: "Ошибка удаления") } catch (_: Exception) {} } }
                        }
                        .setNegativeButton("Отклонить") { _, _ -> bg { try { completeBridgeCommand(id, error = "Отклонено пользователем на телефоне") } catch (_: Exception) {} } }
                        .show()
                }
            }
            "merge_contacts" -> {
                runOnUiThread {
                    val ids = payload.optJSONArray("ids") ?: JSONArray()
                    val group = (0 until ids.length()).mapNotNull { ix ->
                        val cid = ids.optLong(ix, -1); repo.loadAll().firstOrNull { it.id == cid }
                    }.distinctBy { it.id }
                    if (group.size < 2) bg { try { completeBridgeCommand(id, error = "Для объединения нужны минимум два контакта") } catch (_: Exception) {} }
                    else AlertDialog.Builder(this).setTitle("Объединить контакты?")
                        .setMessage(group.joinToString("\n") { it.name + " — " + it.phones.joinToString(", ") })
                        .setPositiveButton("Объединить") { _, _ ->
                            bg { try { repo.merge(group); completeBridgeCommand(id); runOnUiThread { refresh(); toast("Контакты объединены") } }
                                catch (e: Exception) { try { completeBridgeCommand(id, error = e.message ?: "Ошибка объединения") } catch (_: Exception) {} } }
                        }
                        .setNegativeButton("Отклонить") { _, _ -> bg { try { completeBridgeCommand(id, error = "Отклонено пользователем на телефоне") } catch (_: Exception) {} } }
                        .show()
                }
            }
            "sync_contacts" -> bg {
                try { val n = bridge.sync(repo.loadAll()); completeBridgeCommand(id, JSONObject().put("synced", n)) }
                catch (e: Exception) { try { completeBridgeCommand(id, error = e.message ?: "Ошибка синхронизации") } catch (_: Exception) {} }
            }
            else -> bg { try { completeBridgeCommand(id, error = "Неподдерживаемая команда: $action") } catch (_: Exception) {} }
        }
    }

    private fun refresh() {
        try { all = repo.loadAll() } catch (e: Exception) { toast("Ошибка: ${e.message}") }
        selected.retainAll(all.map { it.id }.toSet())
        applyFilter()
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
                v.findViewById<TextView>(android.R.id.text2).text = c.phones.joinToString(", ")
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
                    val csv = repo.toFullFieldsCsv()
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                    toast("Полный экспорт: " + csv.lineSequence().drop(1).count { it.isNotBlank() } + " строк полей")
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
        try { work() } catch (e: Exception) { runOnUiThread { toast("Ошибка: " + e.message) } }
    }.start()

    private fun transferDialog() {
        bg {
            val accs = repo.accounts()
            runOnUiThread {
                val labels = accs.map { it.first.label + " — " + it.second }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Откуда копировать")
                    .setItems(labels) { _, i -> pickTarget(accs.map { it.first }, accs[i].first, accs[i].second) }
                    .setNegativeButton("Отмена", null).show()
            }
        }
    }

    private fun pickTarget(accs: List<Acc>, src: Acc, count: Int) {
        val targets = accs.filter { it != src }
        AlertDialog.Builder(this).setTitle("Куда копировать")
            .setItems(targets.map { it.label }.toTypedArray()) { _, i ->
                val dst = targets[i]
                AlertDialog.Builder(this).setTitle("Подтвердите")
                    .setMessage("Скопировать контакты (" + count + ") из «" + src.label + "» в «" + dst.label +
                        "»?\nИсходные контакты не удаляются. После копирования будет проверка.")
                    .setPositiveButton("Копировать") { _, _ -> runTransfer(src, dst) }
                    .setNegativeButton("Отмена", null).show()
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun runTransfer(src: Acc, dst: Acc) {
        toast("Идёт копирование…")
        bg {
            val r = repo.transfer(src, dst)
            runOnUiThread { refresh(); showTransferResult(r) }
        }
    }

    private fun showTransferResult(r: TransferResult) {
        val ok = r.total > 0 && r.verified == r.total
        val msg = "Найдено в источнике: " + r.total + "\nСкопировано: " + r.copied +
            "\nПроверено в целевом аккаунте: " + r.verified +
            (if (r.missing.isNotEmpty()) "\nНе подтверждено: " + r.missing.take(20).joinToString(", ") else "") +
            (if (ok) "\n\nВсё подтверждено." else "\n\nУдаление из источника недоступно, пока не подтверждены все контакты.")
        val b = AlertDialog.Builder(this).setTitle("Результат переноса").setMessage(msg)
        if (ok) {
            b.setPositiveButton("Удалить из источника") { _, _ ->
                AlertDialog.Builder(this).setTitle("Удалить исходные контакты (" + r.total + ")?")
                    .setMessage("Копии в целевом аккаунте проверены. Это действие необратимо.")
                    .setPositiveButton("Удалить") { _, _ ->
                        bg { repo.deleteRaws(r.rawIds); runOnUiThread { refresh(); toast("Удалено из источника") } }
                    }
                    .setNegativeButton("Оставить", null).show()
            }
            b.setNegativeButton("Оставить", null)
        } else b.setPositiveButton("Закрыть", null)
        b.show()
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
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val key = EditText(this).apply {
            hint = "OpenAI API-ключ"; setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
        }
        val model = EditText(this).apply { hint = "Модель"; setSingleLine(); setText(prefs.getString("model", "gpt-4o-mini")) }
        val withData = android.widget.CheckBox(this).apply {
            text = "Отправить ChatGPT список контактов (имена и номера, до 7000)"
        }
        val q = EditText(this).apply { hint = "Вопрос или задача, например: найди контакты, которые похожи на дубликаты" }
        listOf<View>(key, model, withData, q).forEach { box.addView(it) }
        val sv = android.widget.ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("ChatGPT")
            .setView(sv)
            .setPositiveButton("Спросить") { _, _ ->
                val k = key.text.toString().trim()
                val m = model.text.toString().trim().ifEmpty { "gpt-4o-mini" }
                val question = q.text.toString().trim()
                if (k.isEmpty() || question.isEmpty()) { toast("Нужны ключ и вопрос"); return@setPositiveButton }
                prefs.edit().putString("key", k).putString("model", m).apply()
                val ctx = if (withData.isChecked)
                    "\n\nКонтакты:\n" + all.take(7000).joinToString("\n") { it.name + ": " + it.phones.joinToString(", ") }
                else ""
                toast("Запрос отправлен…")
                bg {
                    val reply = Ai.ask(k, m, "Ты помощник по работе с контактами. Отвечай по-русски, кратко.", question + ctx)
                    runOnUiThread {
                        val tv = TextView(this).apply { text = reply; setTextIsSelectable(true); setPadding(48, 24, 48, 0) }
                        AlertDialog.Builder(this).setTitle("Ответ ChatGPT")
                            .setView(android.widget.ScrollView(this).apply { addView(tv) })
                            .setPositiveButton("Закрыть", null).show()
                    }
                }
            }
            .setNeutralButton("Правки") { _, _ ->
                val k = key.text.toString().trim()
                val m = model.text.toString().trim().ifEmpty { "gpt-4o-mini" }
                if (k.isEmpty()) { toast("Нужен API-ключ"); return@setNeutralButton }
                prefs.edit().putString("key", k).putString("model", m).apply()
                suggestEdits(k, m, q.text.toString().trim())
            }
            .setNegativeButton("Отмена", null).show()
    }

    private data class Proposal(val c: Contact, val name: String, val phone: String)

    private fun suggestEdits(k: String, m: String, wish: String) {
        val data = all.take(7000).joinToString("\n") { it.id.toString() + "|" + it.name + "|" + it.phones.joinToString(",") }
        val system = "Ты помощник по приведению контактов в порядок. Верни ТОЛЬКО JSON без пояснений и без markdown: " +
            "{\"edits\":[{\"id\":число,\"name\":\"новое имя\",\"phone\":\"новый номер\"}]}. " +
            "Включай только контакты, которые нужно изменить. Ничего не выдумывай: исправляй регистр, лишние пробелы, " +
            "опечатки в очевидных случаях и формат номера. Если менять нечего, верни {\"edits\":[]}."
        val user = (if (wish.isNotEmpty()) "Пожелание: " + wish + "\n\n" else "") + "Формат строк: id|имя|телефоны\n" + data
        toast("Запрос отправлен, контакты уходят в OpenAI…")
        bg {
            val reply = Ai.ask(k, m, system, user)
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
        if (props.isEmpty()) { toast("ChatGPT не предложил правок"); return }
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
