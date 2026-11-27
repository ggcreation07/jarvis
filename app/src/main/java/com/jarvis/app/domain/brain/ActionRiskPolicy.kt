package com.jarvis.app.domain.brain

import com.jarvis.app.platform.SensitiveUiActionPolicy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

enum class ActionRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

data class ActionRiskAssessment(
    val level: ActionRiskLevel,
    val category: String,
    val reason: String,
    val requiresConfirmation: Boolean
)

/**
 * Centralized Phase 6E risk classification for tool calls and UI actions.
 * Decisions are based strictly on canonical tool names and arguments — never raw user phrasing.
 */
object ActionRiskPolicy {

    private val highRiskUiKeywords = listOf(
        Regex("\\b(delete|erase|wipe|format|factory\\s+reset|reset\\s+(?:phone|device|network)|clear\\s+(?:data|storage|all)|uninstall|force\\s+stop)\\b"),
        Regex("\\b(password|passcode|pin|credential|recovery|device\\s+admin|security|disable\\s+(?:play\\s+protect|security|protection|firewall|defender|antivirus|lock|2fa))\\b"),
        Regex("\\b(pay|payment|purchase|buy|checkout|transfer|bank|upi|donate|subscribe)\\b"),
        Regex("\\b(send|publish|post|mail|email|call|dial|share\\s+with)\\b"),
        Regex("\\b(allow|grant|permit|permission|authorize)\\b")
    )

    private fun strArg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    fun assess(toolName: String, args: Map<String, JsonElement>): ActionRiskAssessment {
        val normalized = toolName.trim()
        return when (normalized) {
            "get_time",
            "get_day",
            "get_weather",
            "get_news",
            "find_places",
            "get_directions",
            "read_webpage",
            "make_qr_code",
            "generate_image",
            "read_screen",
            "read_notifications",
            "read_clipboard",
            "list_directory",
            "read_file",
            "accessibility_navigate",
            "scroll_ui",
            "find_ui_element",
            "remember_fact",
            "manage_routine",
            "set_timer",
            "set_alarm",
            "navigate_to",
            "play_media",
            "wake_pc",
            "tv_control" -> ActionRiskAssessment(
                level = ActionRiskLevel.LOW,
                category = "read_navigation_or_benign",
                reason = "Read-only, navigation, or safe local helper action.",
                requiresConfirmation = false
            )

            "device_control" -> {
                val action = strArg(args, "action").lowercase(Locale.ROOT)
                when {
                    action == "dnd_on" -> ActionRiskAssessment(
                        level = ActionRiskLevel.MEDIUM,
                        category = "do_not_disturb",
                        reason = "Enabling Do Not Disturb silences incoming notifications and alerts.",
                        requiresConfirmation = true
                    )
                    action.contains("reset") ||
                        action.contains("wipe") ||
                        action.contains("format") ||
                        action.contains("delete") ||
                        action.contains("uninstall") ||
                        action.contains("disable_security") -> ActionRiskAssessment(
                        level = ActionRiskLevel.HIGH,
                        category = "destructive_device_action",
                        reason = "Destructive or security-impacting device action.",
                        requiresConfirmation = true
                    )
                    else -> ActionRiskAssessment(
                        level = ActionRiskLevel.LOW,
                        category = "safe_device_control",
                        reason = "Low-risk volume, flashlight, media, or Settings screen action.",
                        requiresConfirmation = false
                    )
                }
            }

            "open_application" -> {
                val name = strArg(args, "name").lowercase(Locale.ROOT)
                if (name.startsWith("uninstall ") || name.startsWith("delete ")) {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.HIGH,
                        category = "app_uninstall",
                        reason = "Requests app uninstallation or deletion.",
                        requiresConfirmation = true
                    )
                } else {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.LOW,
                        category = "open_app",
                        reason = "Opening an application in foreground is low risk.",
                        requiresConfirmation = false
                    )
                }
            }

            "uninstall_app", "uninstall_application" -> ActionRiskAssessment(
                level = ActionRiskLevel.HIGH,
                category = "app_uninstall",
                reason = "Uninstalling applications or clearing app data requires explicit confirmation.",
                requiresConfirmation = true
            )

            "delete_file" -> ActionRiskAssessment(
                level = ActionRiskLevel.HIGH,
                category = "file_deletion",
                reason = "Permanent file deletion requires explicit confirmation.",
                requiresConfirmation = true
            )

            "send_message", "compose_message", "make_call", "call_phone", "dial_number" -> ActionRiskAssessment(
                level = ActionRiskLevel.HIGH,
                category = "outbound_communication",
                reason = "Sending messages, placing calls, or sharing private info requires explicit confirmation.",
                requiresConfirmation = true
            )

            "forget_fact" -> {
                val query = strArg(args, "text").lowercase(Locale.ROOT)
                val isBulkWipe = query == "everything" || query == "all" || query == "*" ||
                    query.startsWith("all ") || query.startsWith("every ") ||
                    query.contains("everything") || query.contains("all facts") ||
                    query.contains("all memory") || query.contains("wipe memory")
                if (isBulkWipe) {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.HIGH,
                        category = "memory_wipe",
                        reason = "Clearing all remembered facts requires explicit confirmation.",
                        requiresConfirmation = true
                    )
                } else {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.LOW,
                        category = "targeted_memory_update",
                        reason = "Removing a single targeted memory item.",
                        requiresConfirmation = false
                    )
                }
            }

            "manage_schedule" -> {
                val action = strArg(args, "action").ifBlank { "get" }.lowercase(Locale.ROOT)
                if (action == "clear" || action == "delete" || action == "remove") {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.HIGH,
                        category = "schedule_clear",
                        reason = "Clearing scheduled items requires explicit confirmation.",
                        requiresConfirmation = true
                    )
                } else {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.LOW,
                        category = "schedule_read_or_add",
                        reason = "Reading or adding a schedule item is low risk.",
                        requiresConfirmation = false
                    )
                }
            }

            "control_interface" -> {
                val action = strArg(args, "action").lowercase(Locale.ROOT)
                if (action == "clear_chat" || action == "delete_chat") {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.HIGH,
                        category = "chat_clear",
                        reason = "Clearing conversation history requires explicit confirmation.",
                        requiresConfirmation = true
                    )
                } else {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.LOW,
                        category = "ui_panel_navigation",
                        reason = "Opening local JARVIS UI panels is low risk.",
                        requiresConfirmation = false
                    )
                }
            }

            "customize_screen" -> {
                val action = strArg(args, "action").lowercase(Locale.ROOT)
                if (action == "reset") {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.MEDIUM,
                        category = "hud_reset",
                        reason = "Resetting HUD customization reverts saved visual preferences.",
                        requiresConfirmation = true
                    )
                } else {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.LOW,
                        category = "hud_customization",
                        reason = "Applying theme/accent customization is low risk.",
                        requiresConfirmation = false
                    )
                }
            }

            "pc_task" -> {
                val goal = strArg(args, "goal")
                val kind = strArg(args, "kind").ifBlank { "browser" }
                if (ToolConfirmationPolicy.isHighImpactPcTask(goal, kind)) {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.HIGH,
                        category = "remote_pc_high_impact",
                        reason = "High-impact remote PC command requires explicit confirmation.",
                        requiresConfirmation = true
                    )
                } else {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.LOW,
                        category = "remote_pc_benign",
                        reason = "Routine remote PC navigation/query task.",
                        requiresConfirmation = false
                    )
                }
            }

            "click_ui_element" -> {
                val pkg = strArg(args, "_bound_package").ifBlank { strArg(args, "expected_package") }
                val text = strArg(args, "text")
                val desc = strArg(args, "content_description")
                val hint = strArg(args, "hint")
                val viewId = strArg(args, "view_id")
                val resolved = strArg(args, "_resolved_label")
                val isSensitive = strArg(args, "_requires_confirmation").equals("true", ignoreCase = true) ||
                    SensitiveUiActionPolicy.isSensitiveText(
                        text,
                        desc,
                        hint,
                        viewId,
                        resolved,
                        packageName = pkg
                    )
                if (isSensitive) {
                    val combined = listOf(text, desc, hint, viewId, resolved)
                        .joinToString(" ") { SensitiveUiActionPolicy.normalizeForMatch(it) }
                    val isHigh = highRiskUiKeywords.any { it.containsMatchIn(combined) } ||
                        pkg.lowercase(Locale.ROOT) in setOf(
                            "android",
                            "com.android.packageinstaller",
                            "com.google.android.packageinstaller",
                            "com.android.permissioncontroller",
                            "com.google.android.permissioncontroller"
                        )
                    ActionRiskAssessment(
                        level = if (isHigh) ActionRiskLevel.HIGH else ActionRiskLevel.MEDIUM,
                        category = if (isHigh) "sensitive_ui_click" else "guarded_ui_click",
                        reason = "UI click target affects permissions, security, messaging, payments, or destructive state.",
                        requiresConfirmation = true
                    )
                } else {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.LOW,
                        category = "benign_ui_click",
                        reason = "Non-destructive UI element click.",
                        requiresConfirmation = false
                    )
                }
            }

            "type_text" -> {
                val pkg = strArg(args, "_bound_package").ifBlank { strArg(args, "expected_package") }
                val isSensitive = strArg(args, "_requires_confirmation").equals("true", ignoreCase = true) ||
                    SensitiveUiActionPolicy.isSensitiveInputText(
                        strArg(args, "target_text"),
                        strArg(args, "content_description"),
                        strArg(args, "hint"),
                        strArg(args, "view_id"),
                        strArg(args, "_resolved_label"),
                        packageName = pkg
                    )
                if (isSensitive) {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.HIGH,
                        category = "sensitive_ui_input",
                        reason = "Typing into outbound, payment, account, or system permission fields requires confirmation.",
                        requiresConfirmation = true
                    )
                } else {
                    ActionRiskAssessment(
                        level = ActionRiskLevel.LOW,
                        category = "benign_ui_input",
                        reason = "Typing into a regular search or non-sensitive input field.",
                        requiresConfirmation = false
                    )
                }
            }

            else -> ActionRiskAssessment(
                level = ActionRiskLevel.LOW,
                category = "general_tool",
                reason = "Standard tool action.",
                requiresConfirmation = false
            )
        }
    }
}
