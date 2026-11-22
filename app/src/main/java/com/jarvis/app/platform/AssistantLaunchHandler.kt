package com.jarvis.app.platform

import android.content.Intent

data class AssistantLaunchEvent(
    val launchId: Long,
    val action: String,
    val timestampMs: Long,
    val voiceTriggered: Boolean = false,
    val entranceCompleted: Boolean = false
)

object AssistantLaunchIntentHelper {
    const val EXTRA_ASSIST_HANDLED = "com.jarvis.app.extra.ASSIST_LAUNCH_HANDLED"
    const val ACTION_VOICE_INTERACTION_SESSION = "com.jarvis.app.action.VOICE_INTERACTION_SESSION"
    const val DUPLICATE_LAUNCH_DEBOUNCE_MS = 350L

    fun isAssistantAction(action: String?): Boolean =
        action == Intent.ACTION_ASSIST ||
            action == Intent.ACTION_VOICE_COMMAND ||
            action == ACTION_VOICE_INTERACTION_SESSION

    fun shouldHandleAssistantIntent(
        intent: Intent?,
        savedInstanceStatePresent: Boolean
    ): Boolean {
        if (intent == null) return false
        if (!isAssistantAction(intent.action)) return false
        if (savedInstanceStatePresent) return false
        if (intent.getBooleanExtra(EXTRA_ASSIST_HANDLED, false)) return false
        return true
    }

    fun markHandled(intent: Intent?) {
        intent?.putExtra(EXTRA_ASSIST_HANDLED, true)
    }
}
