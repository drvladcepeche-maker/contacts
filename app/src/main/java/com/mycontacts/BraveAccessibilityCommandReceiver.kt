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

        val senderUid = if (Build.VERSION.SDK_INT >= 34) getSentFromUid() else -1
        Log.i(TAG, "Accepted accessibility command from uid=$senderUid")

        var requestId = ""
        try {
            val raw = intent.getStringExtra("payload") ?: "{}"
            require(raw.length <= 12000) { "Command payload is too large." }
            val payload = JSONObject(raw)
            requestId = payload.optString("request_id")
            BraveAccessibilityService.enqueueFromShell(context, payload)
        } catch (error: Exception) {
            if (requestId.matches(Regex("^[a-fA-F0-9-]{36}$"))) {
                try {
                    BraveAccessibilityService.writeResponse(context,
                        BraveAccessibilityService.failure(requestId, error.message ?: "Accessibility command failed."))
                } catch (writeError: Exception) {
                    Log.e(TAG, "Failed to write accessibility error response.", writeError)
                }
            } else {
                Log.e(TAG, "Rejected accessibility command without a valid request ID.", error)
            }
        }
    }

    private companion object {
        const val TAG = "BraveAccessibilityReceiver"
    }
}
