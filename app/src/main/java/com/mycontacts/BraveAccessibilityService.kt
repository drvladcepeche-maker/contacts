package com.mycontacts

import android.accessibilityservice.AccessibilityService
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class BraveAccessibilityService : AccessibilityService() {
    companion object {
        const val TARGET_PACKAGE = "com.brave.browser"
        const val ACTION_COMMAND = "com.mycontacts.BRAVE_ACCESSIBILITY_COMMAND"
        @Volatile private var active: BraveAccessibilityService? = null

        fun executeFromShell(context: android.content.Context, payload: JSONObject): JSONObject {
            val service = active ?: return JSONObject().put("ok", false).put("error", "Accessibility service is not enabled.")
            return try {
                service.execute(payload)
            } catch (error: Exception) {
                JSONObject().put("ok", false).put("error", error.message ?: "Accessibility command failed.")
            }
        }

        fun writeResponse(context: android.content.Context, result: JSONObject): String {
            if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Shared Downloads response requires Android 10 or newer.")
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/TermuxBridge/"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "accessibility-result.json")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?"
            val existing = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection,
                arrayOf("accessibility-result.json", relativePath), null)
            val uri = existing.use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    ContentUris.withAppendedId(collection, id).also { resolver.update(it, values, null, null) }
                } else resolver.insert(collection, values)
            } ?: throw IllegalStateException("Could not create the accessibility response file.")
            resolver.openOutputStream(uri, "wt")?.use { it.write(result.toString(2).toByteArray(Charsets.UTF_8)) }
                ?: throw IllegalStateException("Could not write the accessibility response file.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return "Download/TermuxBridge/accessibility-result.json"
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    private fun execute(payload: JSONObject): JSONObject {
        val action = payload.optString("action")
        if (action == "open_url") {
            val raw = payload.optString("url")
            val uri = Uri.parse(raw)
            require(uri.scheme == "https" || uri.scheme == "http") { "Only HTTP(S) URLs can be opened." }
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(TARGET_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return JSONObject().put("ok", true).put("action", action).put("url", raw)
        }
        if (action == "back") {
            val ok = performGlobalAction(GLOBAL_ACTION_BACK)
            return JSONObject().put("ok", ok).put("action", action)
        }

        val root = rootInActiveWindow ?: throw IllegalStateException("No active accessibility window. Open Brave and retry.")
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName != TARGET_PACKAGE) {
            throw IllegalStateException("Active window is not Brave (active package: $packageName).")
        }

        return when (action) {
            "snapshot" -> JSONObject().put("ok", true).put("action", action).put("package", packageName)
                .put("tree", snapshot(root))
            "click" -> {
                val node = findNode(root, payload) ?: throw IllegalArgumentException("Matching element was not found.")
                val target = clickableParent(node) ?: node
                JSONObject().put("ok", target.performAction(AccessibilityNodeInfo.ACTION_CLICK)).put("action", action)
            }
            "input" -> {
                val node = findNode(root, payload) ?: throw IllegalArgumentException("Matching input was not found.")
                val text = payload.optString("value")
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                JSONObject().put("ok", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)).put("action", action)
            }
            "scroll" -> {
                val node = findNode(root, payload) ?: root
                val forward = payload.optString("direction", "down") != "up"
                val actionId = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                JSONObject().put("ok", node.performAction(actionId)).put("action", action)
            }
            else -> throw IllegalArgumentException("Unsupported accessibility action.")
        }
    }

    private fun clickableParent(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = start
        repeat(6) {
            if (node == null) return null
            if (node!!.isClickable) return node
            node = node!!.parent
        }
        return null
    }

    private fun findNode(root: AccessibilityNodeInfo, selector: JSONObject): AccessibilityNodeInfo? {
        val viewId = selector.optString("view_id")
        val text = selector.optString("text")
        val description = selector.optString("description")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 1000) {
            val node = queue.removeFirst()
            visited++
            val matches = (viewId.isNotBlank() && node.viewIdResourceName == viewId) ||
                (text.isNotBlank() && node.text?.toString()?.contains(text, ignoreCase = true) == true) ||
                (description.isNotBlank() && node.contentDescription?.toString()?.contains(description, ignoreCase = true) == true)
            if (matches) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    private fun snapshot(root: AccessibilityNodeInfo): JSONArray {
        val result = JSONArray()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < 350) {
            val (node, depth) = queue.removeFirst()
            visited++
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            result.put(JSONObject()
                .put("depth", depth)
                .put("class", node.className?.toString().orEmpty())
                .put("text", node.text?.toString()?.take(500) ?: "")
                .put("description", node.contentDescription?.toString()?.take(500) ?: "")
                .put("view_id", node.viewIdResourceName ?: "")
                .put("clickable", node.isClickable)
                .put("editable", node.isEditable)
                .put("scrollable", node.isScrollable)
                .put("enabled", node.isEnabled)
                .put("bounds", JSONObject().put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom)))
            if (depth < 20) for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it to depth + 1) }
        }
        return result
    }
}
