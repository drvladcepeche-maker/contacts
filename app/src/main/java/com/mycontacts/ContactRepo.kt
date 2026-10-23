package com.mycontacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.provider.ContactsContract as CC
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName

data class Contact(val id: Long, val name: String, val phones: List<String>)

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
        return result
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
