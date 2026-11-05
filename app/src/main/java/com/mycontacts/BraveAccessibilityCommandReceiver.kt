package com.mycontacts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import org.json.JSONObject

class BraveAccessibilityCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BraveAccessibilityService.ACTION_COMMAND) return
        if (Build.VERSION.SDK_INT < 34 || getSentFromUid() != 2000) return

        val result = try {
            val raw = intent.getStringExtra("payload") ?: "{}"
            require(raw.length <= 12000) { "Command payload is too large." }
            val payload = JSONObject(raw)
            BraveAccessibilityService.executeFromShell(context, payload)
        } catch (error: Exception) {
            JSONObject().put("ok", false).put("error", error.message ?: "Accessibility command failed.")
        }

        try {
            BraveAccessibilityService.writeResponse(context, result)
        } catch (_: Exception) {
            // The shell caller will still receive the broadcast's completion status.
        }
    }
}
