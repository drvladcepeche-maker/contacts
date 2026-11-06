package com.mycontacts

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

class BraveAccessibilityService : AccessibilityService() {
    companion object {
        const val TARGET_PACKAGE = "com.brave.browser"
        const val ACTION_COMMAND = "com.mycontacts.BRAVE_ACCESSIBILITY_COMMAND"
        private const val DEFAULT_WAIT_MS = 15000L
        private const val MAX_WAIT_MS = 30000L
        private const val WINDOW_POLL_MS = 250L
        private const val VERIFY_POLL_MS = 150L
        private const val VERIFY_MAX_MS = 1800L
        private const val VERIFY_STABLE_SAMPLES = 3
        @Volatile private var active: BraveAccessibilityService? = null

        fun enqueueFromShell(context: android.content.Context, payload: JSONObject) {
            val requestId = payload.optString("request_id")
            require(requestId.matches(Regex("^[a-fA-F0-9-]{36}$"))) { "A valid request_id is required." }
            val service = active
            if (service == null) {
                writeResponse(context, failure(requestId, "Accessibility service is not connected."))
                return
            }
            service.enqueue(payload)
        }

        fun failure(requestId: String, message: String) = JSONObject()
            .put("ok", false)
            .put("request_id", requestId)
            .put("error", message)

        fun writeResponse(context: android.content.Context, result: JSONObject): String {
            if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Shared Downloads response requires Android 10 or newer.")
            val requestId = result.optString("request_id")
            require(requestId.matches(Regex("^[a-fA-F0-9-]{36}$"))) { "Invalid response request_id." }
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/TermuxBridge/"
            val fileName = "accessibility-result-" + requestId + ".json"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Could not create the accessibility response file.")
            try {
                resolver.openOutputStream(uri, "w")?.use {
                    it.write(result.toString(2).toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Could not write the accessibility response file.")
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }, null, null)
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
            return "Download/TermuxBridge/" + fileName
        }
    }

    private data class PendingCommand(
        val payload: JSONObject,
        val requestId: String,
        val deadline: Long,
        val timeout: Runnable
    )

    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<PendingCommand>()
    private var verificationInProgress = false
    private var verificationRequestId = ""
    private var verificationRunnable: Runnable? = null
    private val pollPending = object : Runnable {
        override fun run() {
            processPendingIfReady()
            if (pending.isNotEmpty()) handler.postDelayed(this, WINDOW_POLL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        processPendingIfReady()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != TARGET_PACKAGE) return
        processPendingIfReady()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        handler.removeCallbacks(pollPending)
        verificationRunnable?.let(handler::removeCallbacks)
        verificationRunnable = null
        if (verificationInProgress && verificationRequestId.isNotBlank()) {
            respond(failure(verificationRequestId, "Accessibility service was disconnected during UI verification."))
        }
        verificationRequestId = ""
        verificationInProgress = false
        while (pending.isNotEmpty()) {
            val command = pending.removeFirst()
            handler.removeCallbacks(command.timeout)
            respond(failure(command.requestId, "Accessibility service was disconnected before the command completed."))
        }
        super.onDestroy()
    }

    private fun enqueue(payload: JSONObject) {
        val action = payload.optString("action")
        require(action in setOf("snapshot", "click", "input", "scroll", "back", "open_url")) {
            "Unsupported Brave accessibility action."
        }
        val requestId = payload.getString("request_id")
        val waitMs = payload.optLong("wait_ms", DEFAULT_WAIT_MS).coerceIn(1000L, MAX_WAIT_MS)
        val timeout = Runnable { expire(requestId) }
        val command = PendingCommand(JSONObject(payload.toString()), requestId,
            android.os.SystemClock.uptimeMillis() + waitMs, timeout)
        pending.addLast(command)
        handler.postDelayed(timeout, waitMs)

        try {
            if (action == "open_url") {
                val raw = payload.optString("url")
                val uri = Uri.parse(raw)
                require(uri.scheme == "https" || uri.scheme == "http") { "Only HTTP(S) URLs can be opened." }
                startActivity(Intent(Intent.ACTION_VIEW, uri)
                    .setPackage(TARGET_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                // Shell commands arrive while Termux is foreground. Bring Brave's task
                // forward so the accessibility service can obtain its active window.
                val launchIntent = packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
                    ?: throw IllegalStateException("Brave is not installed or has no launch activity.")
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(launchIntent)
            }
        } catch (error: Exception) {
            removePending(requestId)
            respond(failure(requestId, error.message ?: "Could not launch Brave."))
            return
        }
        handler.removeCallbacks(pollPending)
        handler.post(pollPending)
    }

    private fun expire(requestId: String) {
        val command = pending.firstOrNull { it.requestId == requestId } ?: return
        pending.remove(command)
        val currentPackage = rootInActiveWindow?.packageName?.toString().orEmpty().ifBlank { "none" }
        val appWindows = applicationWindowPackages().joinToString(", ").ifBlank { "none" }
        respond(failure(requestId,
            "Timed out waiting for Brave's application window (active package: $currentPackage; application windows: $appWindows)."))
        if (pending.isNotEmpty()) handler.post(pollPending)
    }

    private fun removePending(requestId: String) {
        val command = pending.firstOrNull { it.requestId == requestId } ?: return
        pending.remove(command)
        handler.removeCallbacks(command.timeout)
    }

    private fun processPendingIfReady() {
        if (pending.isEmpty() || verificationInProgress) return
        val root = findBraveWindowRoot() ?: return
        val packageName = root.packageName?.toString().orEmpty()
        if (packageName != TARGET_PACKAGE) return

        // Execute one command per event/root snapshot; later commands will be
        // picked up by the next accessibility event or by the next enqueue.
        val command = pending.removeFirst()
        handler.removeCallbacks(command.timeout)
        try {
            val action = command.payload.optString("action")
            val beforeFingerprint = if (action in setOf("click", "input", "scroll", "back")) {
                treeFingerprint(root)
            } else ""
            val verificationPayload = JSONObject(command.payload.toString())
            if (action == "input") {
                val originalNode = findNode(root, command.payload)
                val originalViewId = originalNode?.viewIdResourceName.orEmpty()
                if (originalViewId.isNotBlank()) verificationPayload.put("view_id", originalViewId)
            }
            val result = executeAgainstRoot(command.payload, root)
                .put("request_id", command.requestId)
            if (result.optBoolean("ok") && action in setOf("click", "input", "scroll", "back")) {
                verifyAfterAction(verificationPayload, result, beforeFingerprint, command.requestId)
            } else {
                respond(result)
                if (pending.isNotEmpty()) handler.postDelayed(pollPending, 150L)
            }
        } catch (error: Exception) {
            respond(failure(command.requestId, error.message ?: "Accessibility command failed."))
            if (pending.isNotEmpty()) handler.postDelayed(pollPending, 150L)
        }
    }

    /**
     * Accessibility actions are asynchronous: ACTION_CLICK/ACTION_SET_TEXT returning true
     * only means Android accepted the request. Re-read the Brave window until its tree
     * stabilizes, then report whether the requested state change is observable.
     */
    private fun verifyAfterAction(payload: JSONObject, result: JSONObject, beforeFingerprint: String, requestId: String) {
        verificationInProgress = true
        verificationRequestId = requestId
        val startedAt = android.os.SystemClock.uptimeMillis()
        var lastFingerprint = ""
        var stableSamples = 0
        var latestRootAvailable = false

        val verifier = object : Runnable {
            override fun run() {
                val now = android.os.SystemClock.uptimeMillis()
                val root = findBraveWindowRoot()
                val fingerprint = root?.let(::treeFingerprint).orEmpty()
                latestRootAvailable = root != null

                if (fingerprint.isNotBlank() && fingerprint == lastFingerprint) {
                    stableSamples++
                } else {
                    stableSamples = if (fingerprint.isNotBlank()) 1 else 0
                    lastFingerprint = fingerprint
                }

                val elapsed = now - startedAt
                val settled = stableSamples >= VERIFY_STABLE_SAMPLES
                if (!settled && elapsed < VERIFY_MAX_MS) {
                    handler.postDelayed(this, VERIFY_POLL_MS)
                    return
                }

                val action = payload.optString("action")
                val stateChanged = fingerprint.isNotBlank() && fingerprint != beforeFingerprint
                result.put("verification", when {
                    !latestRootAvailable -> "window_unavailable"
                    settled && stateChanged -> "state_changed"
                    settled -> "no_change_detected"
                    stateChanged -> "changed_but_not_settled"
                    else -> "no_change_detected"
                })
                result.put("state_changed", stateChanged)
                result.put("settled", settled)
                result.put("verification_elapsed_ms", elapsed)
                if (action == "input") {
                    val currentNode = root?.let { findNode(it, payload) }
                    val expected = payload.optString("value")
                    val actual = currentNode?.text?.toString()
                    val valuePresent = actual == expected
                    result.put("value_present", valuePresent)
                    result.put("verified", valuePresent)
                } else {
                    result.put("verified", settled && stateChanged)
                }

                verificationRunnable = null
                verificationRequestId = ""
                verificationInProgress = false
                respond(result)
                if (pending.isNotEmpty()) handler.post(pollPending)
            }
        }
        verificationRunnable = verifier
        handler.postDelayed(verifier, VERIFY_POLL_MS)
    }

    private fun treeFingerprint(root: AccessibilityNodeInfo): String =
        snapshot(root).toString()

    private fun findBraveWindowRoot(): AccessibilityNodeInfo? {
        val applicationWindows = try {
            windows.orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        } catch (_: Exception) {
            emptyList()
        }

        // Match Opposable's foreground selection order across ALL app windows.
        // Do not choose a background Brave window while Termux or another app is focused.
        val foregroundWindow = applicationWindows.firstOrNull { it.isFocused }
            ?: applicationWindows.maxByOrNull { it.layer }
            ?: applicationWindows.firstOrNull { it.isActive }
        if (foregroundWindow != null) {
            val foregroundRoot = foregroundWindow.root
            if (foregroundRoot != null) {
                return foregroundRoot.takeIf { it.packageName?.toString() == TARGET_PACKAGE }
            }
        }

        return rootInActiveWindow?.takeIf { it.packageName?.toString() == TARGET_PACKAGE }
    }

    private fun applicationWindowPackages(): List<String> = try {
        windows.orEmpty()
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root?.packageName?.toString() }
            .distinct()
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun respond(result: JSONObject) {
        try {
            writeResponse(applicationContext, result)
        } catch (error: Exception) {
            android.util.Log.e("BraveAccessibilityService", "Could not write command response.", error)
        }
    }

    private fun executeAgainstRoot(payload: JSONObject, root: AccessibilityNodeInfo): JSONObject {
        val action = payload.optString("action")
        val packageName = root.packageName?.toString().orEmpty()
        check(packageName == TARGET_PACKAGE) { "Active window is not Brave (active package: $packageName)." }

        if (action == "back") {
            val ok = performGlobalAction(GLOBAL_ACTION_BACK)
            return JSONObject().put("ok", ok).put("action", action)
        }

        return when (action) {
            "open_url" -> JSONObject().put("ok", true).put("action", action).put("url", payload.optString("url"))
            "snapshot" -> JSONObject().put("ok", true).put("action", action)
                .put("package", packageName).put("tree", snapshot(root))
            "click" -> {
                val node = findNode(root, payload) ?: throw IllegalArgumentException("Matching element was not found.")
                val target = clickableParent(node) ?: node
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                JSONObject().put("ok", clicked).put("action", action)
            }
            "input" -> {
                val node = findNode(root, payload) ?: throw IllegalArgumentException("Matching input was not found.")
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, payload.optString("value"))
                }
                val changed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                JSONObject().put("ok", changed).put("action", action)
            }
            "scroll" -> {
                val node = findNode(root, payload) ?: root
                val forward = payload.optString("direction", "down") != "up"
                val actionId = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                JSONObject().put("ok", node.performAction(actionId)).put("action", action)
            }
            else -> throw IllegalArgumentException("Unsupported Brave accessibility action.")
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
        while (queue.isNotEmpty() && visited < 100) {
            val (node, depth) = queue.removeFirst()
            visited++
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            result.put(JSONObject()
                .put("depth", depth)
                .put("class", node.className?.toString().orEmpty())
                .put("text", node.text?.toString()?.take(180) ?: "")
                .put("description", node.contentDescription?.toString()?.take(180) ?: "")
                .put("view_id", node.viewIdResourceName ?: "")
                .put("clickable", node.isClickable)
                .put("editable", node.isEditable)
                .put("scrollable", node.isScrollable)
                .put("enabled", node.isEnabled)
                .put("bounds", JSONObject().put("left", bounds.left).put("top", bounds.top)
                    .put("right", bounds.right).put("bottom", bounds.bottom)))
            if (depth < 20) for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it to depth + 1) }
            }
        }
        return result
    }
}
