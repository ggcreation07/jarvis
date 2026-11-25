package com.jarvis.app.domain.brain

sealed interface DirectActionMatch {
    data class ToolCall(val toolName: String, val args: Map<String, String>) : DirectActionMatch
    data class LocalAnswer(val text: String, val toolName: String) : DirectActionMatch
}

object DirectCommandMatcher {
    fun match(rawInput: String): DirectActionMatch? {
        val text = rawInput.trim()
        val lower = text.lowercase()
        if (text.isEmpty() || text.length > 90) return null

        // Time & date
        if (Regex("^(what('s| is) the )?(current )?time( right now)?\\??$").matches(lower)) {
            return DirectActionMatch.ToolCall("get_time", emptyMap())
        }
        if (Regex("^(what )?(day|date)( is it)?( today)?\\??$").matches(lower)) {
            return DirectActionMatch.ToolCall("get_day", emptyMap())
        }

        // Flashlight
        if (Regex("^(turn |switch )?on (the )?(flashlight|torch)$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "flashlight_on"))
        }
        if (Regex("^(turn |switch )?off (the )?(flashlight|torch)$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "flashlight_off"))
        }

        // Volume / mute
        if (Regex("^(turn |volume )?(up|louder|increase volume)$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "volume_up"))
        }
        if (Regex("^(turn |volume )?(down|quieter|lower volume|decrease volume)$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "volume_down"))
        }
        if (Regex("^mute( media| volume)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "mute"))
        }
        if (Regex("^unmute( media| volume)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "unmute"))
        }
        if (Regex("^(turn |switch )?on (do not disturb|dnd)$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "dnd_on"))
        }
        if (Regex("^(turn |switch )?off (do not disturb|dnd)$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "dnd_off"))
        }

        // Media transport
        if (Regex("^(pause|resume|play)( the)?( music| media)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "media_play_pause"))
        }
        if (Regex("^(next|skip)( song| track)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "media_next"))
        }
        if (Regex("^(previous|prev)( song| track)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "media_prev"))
        }

        // HUD / Interface direct commands
        if (Regex("^(switch to |enable |use )?iron ?man( mode| look| theme)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("customize_screen", mapOf("action" to "set_look", "value" to "ironman"))
        }
        if (Regex("^(switch to |enable |use )?modern( mode| look| theme)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("customize_screen", mapOf("action" to "set_look", "value" to "modern"))
        }
        if (Regex("^open (settings|memory|skills|capabilities|customize|power|remote)$").matches(lower)) {
            val target = lower.removePrefix("open ").trim()
            return DirectActionMatch.ToolCall("control_interface", mapOf("action" to "open_$target"))
        }
        if (Regex("^(clear|reset) (the )?chat$").matches(lower)) {
            return DirectActionMatch.ToolCall("control_interface", mapOf("action" to "clear_chat"))
        }
        if (Regex("^(reset) (the )?(hud|theme|screen|customization)$").matches(lower)) {
            return DirectActionMatch.ToolCall("customize_screen", mapOf("action" to "reset"))
        }

        // Direct device action commands (protected)
        val uninstallApp = Regex("^uninstall (?:app )?([a-z0-9 ._-]{2,35})$", RegexOption.IGNORE_CASE).find(text)
        if (uninstallApp != null) {
            val appName = uninstallApp.groupValues[1].trim()
            return DirectActionMatch.ToolCall("uninstall_app", mapOf("package" to appName))
        }
        val deleteFile = Regex("^delete file ([a-z0-9 ._/-]{2,50})$", RegexOption.IGNORE_CASE).find(text)
        if (deleteFile != null) {
            val fileName = deleteFile.groupValues[1].trim()
            return DirectActionMatch.ToolCall("delete_file", mapOf("path" to fileName))
        }
        val sendMsg = Regex("^send message to ([a-z0-9 +_-]{2,30}) saying (.+)$", RegexOption.IGNORE_CASE).find(text)
        if (sendMsg != null) {
            val to = sendMsg.groupValues[1].trim()
            val body = sendMsg.groupValues[2].trim()
            return DirectActionMatch.ToolCall("send_message", mapOf("to" to to, "body" to body))
        }

        // Quick Timer: "set a 5 minute timer" / "timer 30 seconds"
        val timerMin = Regex("^(?:set )?(?:a )?(\\d+)\\s*(?:min|minute|minutes)\\s*timer$").find(lower)
        if (timerMin != null) {
            val secs = (timerMin.groupValues[1].toIntOrNull() ?: 1) * 60
            return DirectActionMatch.ToolCall("set_timer", mapOf("seconds" to secs.toString()))
        }
        val timerSec = Regex("^(?:set )?(?:a )?(\\d+)\\s*(?:sec|second|seconds)\\s*timer$").find(lower)
        if (timerSec != null) {
            val secs = timerSec.groupValues[1].toIntOrNull() ?: 10
            return DirectActionMatch.ToolCall("set_timer", mapOf("seconds" to secs.toString()))
        }

        // Global Android navigation (Accessibility Service) - must precede generic openApp rule
        if (Regex("^(?:(?:go|navigate|press|tap)\\s+)?back(?:\\s+button)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("accessibility_navigate", mapOf("action" to "back"))
        }
        if (Regex("^(?:go(?:\\s+to(?:\\s+the)?)?\\s+home(?:\\s+screen)?|return\\s+home|(?:press|tap)\\s+home(?:\\s+button)?|home\\s+screen)$").matches(lower)) {
            return DirectActionMatch.ToolCall("accessibility_navigate", mapOf("action" to "home"))
        }
        if (Regex("^(?:(?:open|show|view)\\s+(?:the\\s+)?(?:recents|recent\\s+apps|app\\s+switcher)|(?:press|tap)\\s+recents(?:\\s+button)?)$").matches(lower)) {
            return DirectActionMatch.ToolCall("accessibility_navigate", mapOf("action" to "recents"))
        }
        if (Regex("^(?:(?:open|show|pull\\s+down|expand)\\s+(?:the\\s+)?(?:notifications?|notification\\s+(?:panel|shade|drawer|bar)))$").matches(lower)) {
            return DirectActionMatch.ToolCall("accessibility_navigate", mapOf("action" to "notifications"))
        }
        if (Regex("^(?:(?:open|show|pull\\s+down|expand)\\s+(?:the\\s+)?quick\\s+settings(?:\\s+(?:panel|shade|drawer))?)$").matches(lower)) {
            return DirectActionMatch.ToolCall("accessibility_navigate", mapOf("action" to "quick_settings"))
        }

        // Quick Open App: "open Spotify"
        val openApp = Regex("^open ([a-z0-9 ._-]{2,28})$", RegexOption.IGNORE_CASE).find(text)
        if (openApp != null) {
            val appName = openApp.groupValues[1].trim()
            if (appName.lowercase() !in setOf("chat", "settings", "memory", "skills", "capabilities", "customize", "power", "remote", "conversation", "recents", "notifications", "quick settings")) {
                return DirectActionMatch.ToolCall("open_application", mapOf("name" to appName))
            }
        }

        return null
    }
}
