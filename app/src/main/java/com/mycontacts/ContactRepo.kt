package com.mycontacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.provider.ContactsContract as CC
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName

data class Contact(val id: Long, val name: String, val phones: List<String>, val accountSources: List<Acc> = emptyList())

data class Acc(val type: String?, val name: String?) {
    val label: String get() = if (name == null && type == null) "Телефон (локально)" else "${name ?: "?"} (${type ?: "?"})"
}

data class RawEntry(
    val rawId: Long,
    val name: String,
    val phones: List<Pair<String, Int>>,
    val emails: List<Pair<String, Int>>
)

data class TransferResult(
    val total: Int,
    val copied: Int,
    val verified: Int,
    val missing: List<String>,
    val rawIds: List<Long>
)

class ContactRepo(private val cr: ContentResolver) {

    fun loadAll(): List<Contact> {
        val phones = HashMap<Long, MutableList<String>>()
        cr.query(Phone.CONTENT_URI, arrayOf(Phone.CONTACT_ID, Phone.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1) ?: continue
                phones.getOrPut(c.getLong(0)) { mutableListOf() }.let { if (n !in it) it.add(n) }
            }
        }
        val result = ArrayList<Contact>()
        cr.query(CC.Contacts.CONTENT_URI, arrayOf(CC.Contacts._ID, CC.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, CC.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                result.add(Contact(id, c.getString(1) ?: "(без имени)", phones[id] ?: emptyList()))
            }
        }
        // An aggregated Contacts row may combine several RawContacts from different accounts.
        // Preserve every source account rather than assigning one guessed account to the aggregate.
        val sources = HashMap<Long, LinkedHashSet<Acc>>()
        cr.query(CC.RawContacts.CONTENT_URI,
            arrayOf(CC.RawContacts.CONTACT_ID, CC.RawContacts.ACCOUNT_NAME, CC.RawContacts.ACCOUNT_TYPE),
            CC.RawContacts.DELETED + "=0", null, null)?.use { c ->
            while (c.moveToNext()) {
                val contactId = c.getLong(0)
                val source = Acc(c.getString(2), c.getString(1))
                sources.getOrPut(contactId) { LinkedHashSet() }.add(source)
            }
        }
        return result.map { contact ->
            contact.copy(accountSources = sources[contact.id]?.toList() ?: emptyList())
        }
    }

    fun add(name: String, phone: String) {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(CC.RawContacts.CONTENT_URI)
            .withValue(CC.RawContacts.ACCOUNT_TYPE, null)
            .withValue(CC.RawContacts.ACCOUNT_NAME, null).build())
        ops.add(ContentProviderOperation.newInsert(CC.Data.CONTENT_URI)
            .withValueBackReference(CC.Data.RAW_CONTACT_ID, 0)
            .withValue(CC.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
            .withValue(StructuredName.DISPLAY_NAME, name).build())
        if (phone.isNotBlank()) {
            ops.add(ContentProviderOperation.newInsert(CC.Data.CONTENT_URI)
                .withValueBackReference(CC.Data.RAW_CONTACT_ID, 0)
                .withValue(CC.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, phone)
                .withValue(Phone.TYPE, Phone.TYPE_MOBILE).build())
        }
        cr.applyBatch(CC.AUTHORITY, ops)
    }

    private fun rawIds(contactId: Long): List<Long> {
        val ids = ArrayList<Long>()
        cr.query(CC.RawContacts.CONTENT_URI, arrayOf(CC.RawContacts._ID),
            CC.RawContacts.CONTACT_ID + "=?", arrayOf(contactId.toString()), null)?.use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        return ids
    }

    fun update(contactId: Long, name: String, phone: String) {
        val ops = ArrayList<ContentProviderOperation>()
        val where = CC.Data.CONTACT_ID + "=? AND " + CC.Data.MIMETYPE + "=?"
        ops.add(ContentProviderOperation.newUpdate(CC.Data.CONTENT_URI)
            .withSelection(where, arrayOf(contactId.toString(), StructuredName.CONTENT_ITEM_TYPE))
            .withValue(StructuredName.DISPLAY_NAME, name).build())
        var hasPhone = false
        cr.query(CC.Data.CONTENT_URI, arrayOf(CC.Data._ID), where,
            arrayOf(contactId.toString(), Phone.CONTENT_ITEM_TYPE), null)?.use { hasPhone = it.count > 0 }
        if (hasPhone) {
            ops.add(ContentProviderOperation.newUpdate(CC.Data.CONTENT_URI)
                .withSelection(where, arrayOf(contactId.toString(), Phone.CONTENT_ITEM_TYPE))
                .withValue(Phone.NUMBER, phone).build())
        } else if (phone.isNotBlank()) {
            val raw = rawIds(contactId).firstOrNull()
            if (raw != null) {
                ops.add(ContentProviderOperation.newInsert(CC.Data.CONTENT_URI)
                    .withValue(CC.Data.RAW_CONTACT_ID, raw)
                    .withValue(CC.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, phone)
                    .withValue(Phone.TYPE, Phone.TYPE_MOBILE).build())
            }
        }
        cr.applyBatch(CC.AUTHORITY, ops)
    }

    fun delete(contactId: Long) {
        cr.delete(ContentUris.withAppendedId(CC.Contacts.CONTENT_URI, contactId), null, null)
    }

    private fun norm(p: String): String = p.filter { it.isDigit() }.takeLast(10)

    /** Группы контактов с одинаковым номером телефона. */
    fun findDuplicates(all: List<Contact>): List<List<Contact>> {
        val byPhone = HashMap<String, MutableSet<Contact>>()
        for (c in all) for (p in c.phones) {
            val k = norm(p)
            if (k.length >= 5) byPhone.getOrPut(k) { LinkedHashSet() }.add(c)
        }
        val seen = HashSet<Set<Long>>()
        val groups = ArrayList<List<Contact>>()
        for (g in byPhone.values) {
            if (g.size < 2) continue
            val key = g.map { it.id }.toSet()
            if (seen.add(key)) groups.add(g.toList())
        }
        return groups
    }

    /** Объединение без удаления данных: система связывает raw-контакты в один. */
    fun merge(group: List<Contact>) {
        val raws = group.flatMap { rawIds(it.id) }
        if (raws.size < 2) return
        val ops = ArrayList<ContentProviderOperation>()
        for (i in 1 until raws.size) {
            ops.add(ContentProviderOperation.newUpdate(CC.AggregationExceptions.CONTENT_URI)
                .withValue(CC.AggregationExceptions.RAW_CONTACT_ID1, raws[0])
                .withValue(CC.AggregationExceptions.RAW_CONTACT_ID2, raws[i])
                .withValue(CC.AggregationExceptions.TYPE, CC.AggregationExceptions.TYPE_KEEP_TOGETHER)
                .build())
        }
        cr.applyBatch(CC.AUTHORITY, ops)
    }


    // ---------- Аккаунты и перенос ----------

    /** Аккаунты, найденные через системный провайдер контактов (в т.ч. Google), с числом контактов. */
    fun accounts(): List<Pair<Acc, Int>> {
        val counts = LinkedHashMap<Acc, Int>()
        cr.query(CC.RawContacts.CONTENT_URI,
            arrayOf(CC.RawContacts.ACCOUNT_NAME, CC.RawContacts.ACCOUNT_TYPE),
            CC.RawContacts.DELETED + "=0", null, null)?.use { c ->
            while (c.moveToNext()) {
                val a = Acc(c.getString(1), c.getString(0))
                counts[a] = (counts[a] ?: 0) + 1
            }
        }
        try {
            cr.query(CC.Settings.CONTENT_URI,
                arrayOf(CC.Settings.ACCOUNT_NAME, CC.Settings.ACCOUNT_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0)
                    val t = c.getString(1)
                    if (n != null && t != null && !counts.containsKey(Acc(t, n))) counts[Acc(t, n)] = 0
                }
            }
        } catch (e: Exception) { /* список настроек необязателен */ }
        if (!counts.containsKey(Acc(null, null))) counts[Acc(null, null)] = 0
        return counts.toList()
    }

    private fun accSelection(a: Acc): Pair<String, Array<String>> {
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        if (a.name == null) parts.add(CC.RawContacts.ACCOUNT_NAME + " IS NULL")
        else { parts.add(CC.RawContacts.ACCOUNT_NAME + "=?"); args.add(a.name) }
        if (a.type == null) parts.add(CC.RawContacts.ACCOUNT_TYPE + " IS NULL")
        else { parts.add(CC.RawContacts.ACCOUNT_TYPE + "=?"); args.add(a.type) }
        parts.add(CC.RawContacts.DELETED + "=0")
        return parts.joinToString(" AND ") to args.toTypedArray()
    }

    fun entriesOf(a: Acc): List<RawEntry> {
        // Account columns belong to RawContacts, not reliably to the Data projection.
        // Resolve raw-contact IDs first, then fetch only Data rows owned by those IDs.
        val (sel, args) = accSelection(a)
        val rawIds = ArrayList<Long>()
        cr.query(CC.RawContacts.CONTENT_URI, arrayOf(CC.RawContacts._ID), sel, args, null)?.use { c ->
            while (c.moveToNext()) rawIds.add(c.getLong(0))
        }
        if (rawIds.isEmpty()) return emptyList()

        val names = LinkedHashMap<Long, String>()
        val phones = HashMap<Long, MutableList<Pair<String, Int>>>()
        val emails = HashMap<Long, MutableList<Pair<String, Int>>>()
        for (chunk in rawIds.chunked(400)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val dataSel = CC.Data.RAW_CONTACT_ID + " IN (" + placeholders + ")"
            cr.query(CC.Data.CONTENT_URI,
                arrayOf(CC.Data.RAW_CONTACT_ID, CC.Data.MIMETYPE, CC.Data.DATA1, CC.Data.DATA2),
                dataSel, chunk.map { it.toString() }.toTypedArray(), CC.Data.RAW_CONTACT_ID)?.use { c ->
                while (c.moveToNext()) {
                    val raw = c.getLong(0)
                    names.getOrPut(raw) { "" }
                    val v = c.getString(2) ?: continue
                    val type = c.getInt(3)
                    when (c.getString(1)) {
                        StructuredName.CONTENT_ITEM_TYPE -> names[raw] = v
                        Phone.CONTENT_ITEM_TYPE -> phones.getOrPut(raw) { mutableListOf() }.add(v to type)
                        CC.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> emails.getOrPut(raw) { mutableListOf() }.add(v to type)
                    }
                }
            }
        }
        return rawIds.map { raw -> RawEntry(raw, names[raw] ?: "", phones[raw] ?: emptyList(), emails[raw] ?: emptyList()) }
    }

    private fun key(e: RawEntry): String =
        e.name.trim().lowercase() + "|" +
            e.phones.map { norm(it.first) }.sorted().joinToString(",") + "|" +
            e.emails.map { it.first.trim().lowercase() }.sorted().joinToString(",")

    private fun keyCounts(list: List<RawEntry>): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (e in list) m[key(e)] = (m[key(e)] ?: 0) + 1
        return m
    }

    private fun insertCopy(e: RawEntry, target: Acc) {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(CC.RawContacts.CONTENT_URI)
            .withValue(CC.RawContacts.ACCOUNT_TYPE, target.type)
            .withValue(CC.RawContacts.ACCOUNT_NAME, target.name).build())
        if (e.name.isNotBlank()) {
            ops.add(ContentProviderOperation.newInsert(CC.Data.CONTENT_URI)
                .withValueBackReference(CC.Data.RAW_CONTACT_ID, 0)
                .withValue(CC.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, e.name).build())
        }
        for ((num, t) in e.phones) {
            ops.add(ContentProviderOperation.newInsert(CC.Data.CONTENT_URI)
                .withValueBackReference(CC.Data.RAW_CONTACT_ID, 0)
                .withValue(CC.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, num)
                .withValue(Phone.TYPE, if (t == 0) Phone.TYPE_MOBILE else t).build())
        }
        for ((addr, t) in e.emails) {
            ops.add(ContentProviderOperation.newInsert(CC.Data.CONTENT_URI)
                .withValueBackReference(CC.Data.RAW_CONTACT_ID, 0)
                .withValue(CC.Data.MIMETYPE, CC.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(CC.CommonDataKinds.Email.ADDRESS, addr)
                .withValue(CC.CommonDataKinds.Email.TYPE, if (t == 0) CC.CommonDataKinds.Email.TYPE_HOME else t).build())
        }
        cr.applyBatch(CC.AUTHORITY, ops)
    }

    /** Копирует контакты из источника в целевой аккаунт и проверяет, что копии появились. Источник не меняется. */
    fun transfer(src: Acc, dst: Acc): TransferResult {
        val source = entriesOf(src).filter { it.name.isNotBlank() || it.phones.isNotEmpty() || it.emails.isNotEmpty() }
        val before = keyCounts(entriesOf(dst))
        var copied = 0
        for (e in source) { insertCopy(e, dst); copied++ }
        val after = keyCounts(entriesOf(dst))
        val need = keyCounts(source)
        var verified = 0
        val missing = ArrayList<String>()
        val used = HashMap<String, Int>()
        for (e in source) {
            val k = key(e)
            val n = (used[k] ?: 0) + 1
            used[k] = n
            val gained = (after[k] ?: 0) - (before[k] ?: 0)
            if (gained >= n && n <= (need[k] ?: 0)) verified++ else missing.add(e.name.ifBlank { e.phones.firstOrNull()?.first ?: "?" })
        }
        return TransferResult(source.size, copied, verified, missing, source.map { it.rawId })
    }

    fun deleteRaws(ids: List<Long>) {
        for (id in ids) cr.delete(ContentUris.withAppendedId(CC.RawContacts.CONTENT_URI, id), null, null)
    }


    /**
     * Полный технический экспорт всех Data-строк Android Contacts Provider.
     * Одна строка файла соответствует одному полю/записи контакта, включая
     * тип MIME, значения DATA1..DATA15 и аккаунт исходного RawContact.
     */
    /**
     * Читает ячейку курсора как текст. Поля вроде DATA15 (миниатюра фото) хранятся как BLOB,
     * и getString() на них падает с «Unable to convert BLOB to string», поэтому BLOB заменяется
     * пометкой с размером, а NULL — пустой строкой.
     */
    private fun cell(c: Cursor, column: String): String {
        val i = c.getColumnIndexOrThrow(column)
        return when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> ""
            Cursor.FIELD_TYPE_BLOB -> "[BLOB " + (c.getBlob(i)?.size ?: 0) + " байт]"
            else -> c.getString(i) ?: ""
        }
    }

    fun toFullFieldsCsv(): String {
        data class AccountInfo(val name: String?, val type: String?)
        val accountByRawId = LinkedHashMap<Long, AccountInfo>()
        cr.query(CC.RawContacts.CONTENT_URI,
            arrayOf(CC.RawContacts._ID, CC.RawContacts.ACCOUNT_NAME,
                CC.RawContacts.ACCOUNT_TYPE, CC.RawContacts.DELETED), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) != 0) continue
                accountByRawId[c.getLong(0)] = AccountInfo(c.getString(1), c.getString(2))
            }
        } ?: throw IllegalStateException("Android не предоставил список исходных контактов")

        val contactNames = HashMap<Long, String>()
        cr.query(CC.Contacts.CONTENT_URI,
            arrayOf(CC.Contacts._ID, CC.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
            while (c.moveToNext()) contactNames[c.getLong(0)] = c.getString(1) ?: ""
        } ?: throw IllegalStateException("Android не предоставил список имён контактов")

        val dataColumns = (1..15).map { "data$it" }
        val projection = arrayOf(
            CC.Data._ID, CC.Data.CONTACT_ID, CC.Data.RAW_CONTACT_ID, CC.Data.MIMETYPE,
            *dataColumns.toTypedArray(), CC.Data.IS_PRIMARY, CC.Data.IS_SUPER_PRIMARY, CC.Data.DATA_VERSION
        )
        val headers = listOf("data_row_id", "contact_id", "raw_contact_id", "display_name",
            "account_name", "account_type", "mimetype") + dataColumns +
            listOf("is_primary", "is_super_primary", "data_version")
        val sb = StringBuilder(headers.joinToString(";") { q(it) }).append('\n')
        // Query Data in bounded chunks by raw_contact_id. This avoids large provider queries
        // and avoids sorting on joined contact_id columns in the provider's SQLite query.
        val rawIds = accountByRawId.keys.toList()
        for (chunk in rawIds.chunked(250)) {
            val selection = CC.Data.RAW_CONTACT_ID + " IN (" +
                chunk.joinToString(",") { "?" } + ")"
            cr.query(CC.Data.CONTENT_URI, projection, selection,
                chunk.map { it.toString() }.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) {
                    val contactId = c.getLong(c.getColumnIndexOrThrow(CC.Data.CONTACT_ID))
                    val rawId = c.getLong(c.getColumnIndexOrThrow(CC.Data.RAW_CONTACT_ID))
                    val account = accountByRawId[rawId]
                    val values = ArrayList<String>()
                    values.add(cell(c, CC.Data._ID))
                    values.add(contactId.toString())
                    values.add(rawId.toString())
                    values.add(contactNames[contactId] ?: "")
                    values.add(account?.name ?: "")
                    values.add(account?.type ?: "")
                    values.add(cell(c, CC.Data.MIMETYPE))
                    for (column in dataColumns) values.add(cell(c, column))
                    values.add(cell(c, CC.Data.IS_PRIMARY))
                    values.add(cell(c, CC.Data.IS_SUPER_PRIMARY))
                    values.add(cell(c, CC.Data.DATA_VERSION))
                    sb.append(values.joinToString(";") { q(it) }).append('\n')
                }
            } ?: throw IllegalStateException("Android не вернул поля контактов для группы записей")
        }
        if (sb.toString().substringAfter('\n').isBlank()) {
            throw IllegalStateException("Системная база не вернула ни одной строки данных контактов")
        }
        return sb.toString()
    }

    // ---------- XLSX (без внешних библиотек) ----------

    private fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").filter { it.code >= 32 || it == '\n' || it == '\t' }

    private fun colName(i: Int): String = ('A' + i).toString()

    fun toXlsx(list: List<Contact>): ByteArray {
        val maxPhones = (list.maxOfOrNull { it.phones.size } ?: 1).coerceIn(1, 20)
        val header = listOf("№", "Имя", "Источник аккаунта") + (1..maxPhones).map { "Телефон $it" }
        val sheet = StringBuilder()
        sheet.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sheet.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sheet.append("<cols><col min=\"1\" max=\"1\" width=\"6\" customWidth=\"1\"/>")
        sheet.append("<col min=\"2\" max=\"2\" width=\"32\" customWidth=\"1\"/>")
        sheet.append("<col min=\"3\" max=\"3\" width=\"38\" customWidth=\"1\"/>")
        sheet.append("<col min=\"4\" max=\"${3 + maxPhones}\" width=\"20\" customWidth=\"1\"/></cols><sheetData>")
        fun row(r: Int, cells: List<String>) {
            sheet.append("<row r=\"").append(r).append("\">")
            for ((i, v) in cells.withIndex()) {
                if (v.isEmpty()) continue
                sheet.append("<c r=\"").append(colName(i)).append(r).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(xml(v)).append("</t></is></c>")
            }
            sheet.append("</row>")
        }
        row(1, header)
        for ((i, c) in list.withIndex()) row(i + 2, listOf((i + 1).toString(), c.name, c.accountSources.joinToString("; ") { it.label }.ifBlank { "Не определён" }) + c.phones)
        sheet.append("</sheetData></worksheet>")

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(name: String, text: String) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                "</Types>")
            put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                "</Relationships>")
            put("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Контакты\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                "</Relationships>")
            put("xl/worksheets/sheet1.xml", sheet.toString())
        }
        return out.toByteArray()
    }

    // ---------- VCF / CSV ----------

    fun toVcf(list: List<Contact>): String {
        val sb = StringBuilder()
        for (c in list) {
            sb.append("BEGIN:VCARD\r\nVERSION:3.0\r\n")
            sb.append("FN:").append(c.name).append("\r\n")
            sb.append("N:").append(c.name).append(";;;;\r\n")
            for (p in c.phones) sb.append("TEL;TYPE=CELL:").append(p).append("\r\n")
            sb.append("END:VCARD\r\n")
        }
        return sb.toString()
    }

    fun parseVcf(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var name = ""
        var phone = ""
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("BEGIN:VCARD", true) -> { name = ""; phone = "" }
                line.startsWith("FN", true) && line.contains(":") -> name = line.substringAfter(":")
                line.startsWith("TEL", true) && line.contains(":") && phone.isEmpty() -> phone = line.substringAfter(":")
                line.startsWith("END:VCARD", true) -> if (name.isNotBlank()) out.add(name to phone)
            }
        }
        return out
    }

    fun toCsv(list: List<Contact>): String {
        val sb = StringBuilder("Имя;Телефоны\n")
        for (c in list) sb.append(q(c.name)).append(';').append(q(c.phones.joinToString(","))).append('\n')
        return sb.toString()
    }

    private fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    fun parseCsv(text: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for ((i, line) in text.lines().withIndex()) {
            if (i == 0 || line.isBlank()) continue
            val parts = line.split(';', limit = 2).map { it.trim().trim('"').replace("\"\"", "\"") }
            if (parts[0].isNotBlank()) out.add(parts[0] to (parts.getOrNull(1)?.substringBefore(",") ?: ""))
        }
        return out
    }
}
