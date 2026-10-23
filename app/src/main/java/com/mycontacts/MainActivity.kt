package com.mycontacts

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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

class MainActivity : Activity() {

    private lateinit var repo: ContactRepo
    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var status: TextView
    private var all: List<Contact> = emptyList()
    private var shown: List<Contact> = emptyList()

    private val reqPerm = 1
    private val reqExportVcf = 10
    private val reqExportCsv = 11
    private val reqImport = 12

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ContactRepo(contentResolver)
        buildUi()
        if (hasPerms()) refresh()
        else requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), reqPerm)
    }

    private fun hasPerms() =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (code == reqPerm && hasPerms()) refresh()
        else toast("Нужен доступ к контактам")
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
        fun btn(t: String, a: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { a() }
        }
        row1.addView(btn("Добавить") { editDialog(null) })
        row1.addView(btn("Дубликаты") { duplicatesDialog() })
        row2.addView(btn("Импорт") { pickImport() })
        row2.addView(btn("VCF") { createDoc("contacts.vcf", "text/x-vcard", reqExportVcf) })
        row2.addView(btn("CSV") { createDoc("contacts.csv", "text/csv", reqExportCsv) })
        status = TextView(this)
        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { editDialog(it) } }
        list.setOnItemLongClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { confirmDelete(it) }; true }
        listOf<View>(search, row1, row2, status, list).forEach { root.addView(it) }
        setContentView(root)
    }

    private fun refresh() {
        try { all = repo.loadAll() } catch (e: Exception) { toast("Ошибка: ${e.message}") }
        applyFilter()
    }

    private fun applyFilter() {
        val q = search.text.toString().trim().lowercase()
        val digits = q.filter { it.isDigit() }
        shown = if (q.isEmpty()) all else all.filter { c ->
            c.name.lowercase().contains(q) ||
                (digits.isNotEmpty() && c.phones.any { it.filter { ch -> ch.isDigit() }.contains(digits) })
        }
        status.text = "Контактов: ${shown.size} из ${all.size}. Нажатие — правка, долгое — удалить."
        list.adapter = object : ArrayAdapter<Contact>(this, android.R.layout.simple_list_item_2, shown) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
                val c = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).text = c.name
                v.findViewById<TextView>(android.R.id.text2).text = c.phones.joinToString(", ")
                return v
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
                reqExportVcf -> write(uri, repo.toVcf(all))
                reqExportCsv -> write(uri, repo.toCsv(all))
                reqImport -> importFrom(uri)
            }
        } catch (e: Exception) { toast("Ошибка: ${e.message}") }
    }

    private fun write(uri: Uri, text: String) {
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        toast("Экспортировано: ${all.size}")
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

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
