package com.mycontacts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONObject

class BraveAccessibilityCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BraveAccessibilityService.ACTION_COMMAND) return

        // The manifest requires WRITE_SECURE_SETTINGS from the sender. Shizuku's
        // shell UID has this permission; avoid relying on getSentFromUid(), which
        // can be unavailable for some broadcast delivery paths.
        val senderUid = if (Build.VERSION.SDK_INT >= 34) getSentFromUid() else -1
        Log.i(TAG, "Accepted accessibility command from uid=$senderUid")

        val result = try {
            val raw = intent.getStringExtra("payload") ?: "{}"
            require(raw.length <= 12000) { "Command payload is too large." }
            val payload = JSONObject(raw)
            BraveAccessibilityService.executeFromShell(context, payload)
        } catch (error: Exception) {
            JSONObject().put("ok", false).put("error", error.message ?: "Accessibility command failed.")
        }

        try {
            result.put("response_file", "Download/TermuxBridge/accessibility-result.json")
            BraveAccessibilityService.writeResponse(context, result)
            Log.i(TAG, "Accessibility response file written.")
        } catch (error: Exception) {
            Log.e(TAG, "Failed to write accessibility response file.", error)
        }
    }

    private companion object {
        const val TAG = "BraveAccessibilityReceiver"
    }
}
