package com.jarvis.app.domain.brain

import com.jarvis.app.platform.CapabilityAvailabilityStatus
import com.jarvis.app.platform.DeviceActionId
import com.jarvis.app.platform.DeviceCapabilityRegistry
import com.jarvis.app.platform.ScreenReaderEngine
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

sealed interface LocalCommandMatch {
    data class ToolCommand(
        val toolName: String,
        val args: Map<String, String>,
        val requiredCapability: DeviceActionId? = null
    ) : LocalCommandMatch

    data class RestrictedAction(
        val actionId: DeviceActionId,
        val settingsAction: String,
        val settingsLabel: String,
        val explanation: String
    ) : LocalCommandMatch
}

data class LocalExecutionResult(
    val handled: Boolean,
    val ok: Boolean,
    val toolName: String,
    val summary: String,
    val awaitingConfirmation: Boolean = false,
    val restrictedOrUnavailable: Boolean = false
)

class LocalDeviceCommandEngine(
    private val toolExecutor: ToolExecutor,
    private val capabilityRegistry: DeviceCapabilityRegistry? = null
) {

    fun match(rawInput: String): LocalCommandMatch? {
        val text = rawInput.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || text.length > 140) return null
        if (containsUntrustedMarker(text)) return null

        val cleaned = text.trimEnd('?', '.', '!', '।').trim()
        if (cleaned.isEmpty()) return null

        // Let UiTaskPlanner handle explicit multi-step workflows
        if (DirectCommandMatcher.looksMultiClause(cleaned)) return null

        val lower = cleaned.lowercase(Locale.ROOT)

        // 1. Restricted Android system toggles -> open safe Settings instead of pretending to toggle
        matchRestrictedSystemToggle(cleaned, lower)?.let { return it }

        // 2. Delegate deterministic device, settings, time/day, HUD & global navigation commands
        val direct = DirectCommandMatcher.match(cleaned)
        if (direct is DirectActionMatch.ToolCall) {
            return LocalCommandMatch.ToolCommand(
                toolName = direct.toolName,
                args = direct.args,
                requiredCapability = mapCapabilityForTool(direct.toolName, direct.args)
            )
        }

        // 3. Local Accessibility screen reading & UI interaction commands (English, Hinglish, Hindi, Marathi)
        matchReadScreen(cleaned, lower)?.let { return it }
        matchScrollUi(cleaned, lower)?.let { return it }
        matchTypeText(cleaned)?.let { return it }
        matchClickUiElement(cleaned)?.let { return it }
        matchFindUiElement(cleaned)?.let { return it }

        return null
    }

    suspend fun execute(
        rawInput: String,
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): LocalExecutionResult {
        val matched = match(rawInput)
            ?: return LocalExecutionResult(
                handled = false,
                ok = false,
                toolName = "",
                summary = ""
            )
        return executeMatch(matched, onSideEffect)
    }

    suspend fun executeMatch(
        match: LocalCommandMatch,
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): LocalExecutionResult {
        val snapshot = capabilityRegistry?.refresh()

        when (match) {
            is LocalCommandMatch.RestrictedAction -> {
                val settingsOutcome = toolExecutor.localDeviceController.openSettingsScreen(
                    action = match.settingsAction,
                    label = match.settingsLabel
                )
                val fullMessage = buildString {
                    append(match.explanation)
                    if (settingsOutcome.ok) {
                        append(" ")
                        append(settingsOutcome.summary)
                    } else {
                        append(" Could not open ${match.settingsLabel}: ${settingsOutcome.summary}")
                    }
                }
                toolExecutor.logLocalActionAudit("device_control", fullMessage, false)
                return LocalExecutionResult(
                    handled = true,
                    ok = false,
                    toolName = "device_control",
                    summary = fullMessage,
                    restrictedOrUnavailable = true
                )
            }

            is LocalCommandMatch.ToolCommand -> {
                // Fail cleanly when a hardware or fixed-system capability is known to be unavailable
                if (snapshot != null && match.requiredCapability != null) {
                    val capStatus = snapshot.statusFor(match.requiredCapability)
                    if (capStatus != null && !capStatus.isAvailable) {
                        val shouldShortCircuit = when (match.requiredCapability) {
                            DeviceActionId.FLASHLIGHT ->
                                capStatus.status == CapabilityAvailabilityStatus.UNSUPPORTED_HARDWARE ||
                                    capStatus.status == CapabilityAvailabilityStatus.UNAVAILABLE
                            DeviceActionId.VOLUME_CONTROL ->
                                capStatus.status == CapabilityAvailabilityStatus.RESTRICTED_SYSTEM_ACTION ||
                                    capStatus.status == CapabilityAvailabilityStatus.UNAVAILABLE
                            else -> false
                        }
                        if (shouldShortCircuit) {
                            val unavailableMsg = capStatus.reason
                            toolExecutor.logLocalActionAudit(match.toolName, unavailableMsg, false)
                            return LocalExecutionResult(
                                handled = true,
                                ok = false,
                                toolName = match.toolName,
                                summary = unavailableMsg,
                                restrictedOrUnavailable = true
                            )
                        }
                    }
                }

                val jsonArgs = match.args.mapValues { JsonPrimitive(it.value) }
                val res = toolExecutor.execute(match.toolName, jsonArgs, onSideEffect)
                val awaitingConfirmation = res.isConfirmationRequired()
                return LocalExecutionResult(
                    handled = true,
                    ok = res.ok,
                    toolName = res.toolName,
                    summary = res.summary,
                    awaitingConfirmation = awaitingConfirmation,
                    restrictedOrUnavailable = !res.ok && !awaitingConfirmation
                )
            }
        }
    }

    private fun mapCapabilityForTool(toolName: String, args: Map<String, String>): DeviceActionId? = when (toolName) {
        "device_control" -> when (args["action"]) {
            "flashlight_on", "flashlight_off" -> DeviceActionId.FLASHLIGHT
            "volume_up", "volume_down", "mute", "unmute" -> DeviceActionId.VOLUME_CONTROL
            "dnd_on", "dnd_off" -> DeviceActionId.DO_NOT_DISTURB
            "media_play_pause", "media_next", "media_prev" -> DeviceActionId.MEDIA_CONTROL
            else -> DeviceActionId.ANDROID_SETTINGS
        }
        "open_application" -> DeviceActionId.OPEN_APP
        "set_timer", "set_alarm" -> DeviceActionId.ALARM_TIMER
        "accessibility_navigate" -> when (args["action"]) {
            "back" -> DeviceActionId.NAVIGATION_BACK
            "home" -> DeviceActionId.NAVIGATION_HOME
            "recents" -> DeviceActionId.NAVIGATION_RECENTS
            "notifications" -> DeviceActionId.NAVIGATION_NOTIFICATIONS
            "quick_settings" -> DeviceActionId.NAVIGATION_QUICK_SETTINGS
            else -> null
        }
        "read_screen" -> DeviceActionId.SCREEN_READ
        "read_notifications" -> DeviceActionId.READ_NOTIFICATIONS
        "find_ui_element" -> DeviceActionId.UI_FIND
        "click_ui_element" -> DeviceActionId.UI_CLICK
        "type_text" -> DeviceActionId.UI_TYPE_TEXT
        "scroll_ui" -> DeviceActionId.UI_SCROLL
        else -> null
    }

    private fun matchRestrictedSystemToggle(cleaned: String, lower: String): LocalCommandMatch.RestrictedAction? {
        if (lower.contains("settings") || cleaned.contains("सेटिंग")) return null

        val isWifiToggle = Regex("^(?:turn |switch )?(?:on|off) (?:the )?wi-?fi$").matches(lower) ||
            Regex("^(?:enable|disable|toggle) wi-?fi$").matches(lower) ||
            Regex("^wi-?fi (?:on|off|chalu|band) (?:karo|kar|do)$").matches(lower) ||
            Regex("^(?:वाईफाई|वायफाय|वाई-फाई) (?:ऑन|ऑफ|चालू|बंद) (?:करो|कर|करें|करा)$").matches(cleaned)
        if (isWifiToggle) {
            return LocalCommandMatch.RestrictedAction(
                actionId = DeviceActionId.WIFI_DIRECT_TOGGLE,
                settingsAction = android.provider.Settings.ACTION_WIFI_SETTINGS,
                settingsLabel = "Wi-Fi Settings",
                explanation = "Android restricts apps from toggling Wi-Fi directly."
            )
        }

        val isBluetoothToggle = Regex("^(?:turn |switch )?(?:on|off) (?:the )?bluetooth$").matches(lower) ||
            Regex("^(?:enable|disable|toggle) bluetooth$").matches(lower) ||
            Regex("^bluetooth (?:on|off|chalu|band) (?:karo|kar|do)$").matches(lower) ||
            Regex("^ब्लूटूथ (?:ऑन|ऑफ|चालू|बंद) (?:करो|कर|करें|करा)$").matches(cleaned)
        if (isBluetoothToggle) {
            return LocalCommandMatch.RestrictedAction(
                actionId = DeviceActionId.BLUETOOTH_DIRECT_TOGGLE,
                settingsAction = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
                settingsLabel = "Bluetooth Settings",
                explanation = "Android requires user confirmation in system settings to toggle Bluetooth."
            )
        }

        val isMobileDataToggle = Regex("^(?:turn |switch )?(?:on|off) (?:the )?(?:mobile )?data$").matches(lower) ||
            Regex("^(?:enable|disable|toggle) (?:mobile )?data$").matches(lower) ||
            Regex("^(?:mobile )?data (?:on|off|chalu|band) (?:karo|kar|do)$").matches(lower) ||
            Regex("^(?:मोबाइल )?डेटा (?:ऑन|ऑफ|चालू|बंद) (?:करो|कर|करें|करा)$").matches(cleaned)
        if (isMobileDataToggle) {
            return LocalCommandMatch.RestrictedAction(
                actionId = DeviceActionId.MOBILE_DATA_TOGGLE,
                settingsAction = android.provider.Settings.ACTION_WIRELESS_SETTINGS,
                settingsLabel = "Network & Internet Settings",
                explanation = "Android restricts third-party apps from toggling mobile data directly."
            )
        }

        return null
    }

    private fun matchReadScreen(cleaned: String, lower: String): LocalCommandMatch.ToolCommand? {
        val matched = Regex("^(?:read|inspect|scan|check) (?:the )?(?:active |current |visible )?screen$").matches(lower) ||
            Regex("^what(?:'s| is) on (?:the )?(?:my )?screen$").matches(lower) ||
            Regex("^screen (?:padho|read karo|batao|dikhao)$").matches(lower) ||
            Regex("^screen (?:pe|par) kya hai$").matches(lower) ||
            Regex("^स्क्रीन (?:पढ़ो|पढ़ें|बताओ|दिखाओ|वाच|वाचा|सांग|दाखवा)$").matches(cleaned) ||
            Regex("^स्क्रीन(?: पर|वर) (?:क्या|काय) (?:है|आहे)$").matches(cleaned)
        if (!matched) return null
        return LocalCommandMatch.ToolCommand(
            toolName = "read_screen",
            args = emptyMap(),
            requiredCapability = DeviceActionId.SCREEN_READ
        )
    }

    private fun matchScrollUi(cleaned: String, lower: String): LocalCommandMatch.ToolCommand? {
        val eng = Regex("^scroll (up|down)(?: (?:on|in) (.+))?$").find(lower)
        if (eng != null) {
            val dir = eng.groupValues[1].uppercase(Locale.ROOT)
            val target = eng.groupValues[2].trim().trim('"', '\'')
            return buildScrollCommand(dir, target)
        }

        val hinglishDirFirst = Regex("^(upar|up|neeche|niche|down) scroll (?:karo|kar)$").find(lower)
        if (hinglishDirFirst != null) {
            val dir = if (hinglishDirFirst.groupValues[1] in setOf("upar", "up")) "UP" else "DOWN"
            return buildScrollCommand(dir, "")
        }

        val hinglishScrollFirst = Regex("^scroll (up|down|upar|neeche|niche) (?:karo|kar)$").find(lower)
        if (hinglishScrollFirst != null) {
            val dir = if (hinglishScrollFirst.groupValues[1] in setOf("upar", "up")) "UP" else "DOWN"
            return buildScrollCommand(dir, "")
        }

        return when {
            Regex("^(?:ऊपर|स्क्रॉल अप) (?:स्क्रॉल )?(?:करो|करें)$").matches(cleaned) ||
                Regex("^स्क्रॉल ऊपर करो$").matches(cleaned) ||
                Regex("^वर स्क्रोल (?:कर|करा)$").matches(cleaned) -> buildScrollCommand("UP", "")
            Regex("^(?:नीचे|स्क्रॉल डाउन) (?:स्क्रॉल )?(?:करो|करें)$").matches(cleaned) ||
                Regex("^स्क्रॉल नीचे करो$").matches(cleaned) ||
                Regex("^खाली स्क्रोल (?:कर|करा)$").matches(cleaned) -> buildScrollCommand("DOWN", "")
            else -> null
        }
    }

    private fun buildScrollCommand(dir: String, target: String): LocalCommandMatch.ToolCommand {
        val args = mutableMapOf("direction" to dir)
        if (target.isNotBlank()) {
            if (looksLikeViewId(target)) {
                args["view_id"] = target.removePrefix("id:").trim()
            } else {
                args["content_description"] = target
            }
        }
        return LocalCommandMatch.ToolCommand(
            toolName = "scroll_ui",
            args = args,
            requiredCapability = DeviceActionId.UI_SCROLL
        )
    }

    private fun matchTypeText(cleaned: String): LocalCommandMatch.ToolCommand? {
        // English quoted / unquoted
        val engQuoted = Regex(
            "^(?:type|enter|write|input) [\"']([^\"']+)[\"'] (?:in|into|on) (?:the )?(.+?)(?: field| box)?$",
            RegexOption.IGNORE_CASE
        ).find(cleaned)
        if (engQuoted != null) {
            return buildTypeCommand(engQuoted.groupValues[1], engQuoted.groupValues[2])
        }

        val engUnquoted = Regex(
            "^(?:type|enter|write|input) (.+?) (?:in|into) (?:the )?(.+?)(?: field| box)?$",
            RegexOption.IGNORE_CASE
        ).find(cleaned)
        if (engUnquoted != null) {
            return buildTypeCommand(engUnquoted.groupValues[1], engUnquoted.groupValues[2])
        }

        // Hinglish: "<field> mein 'text' type karo" or "'text' <field> mein likho"
        val hinglishFieldFirst = Regex(
            "^(.+?) (?:mein|me) [\"']([^\"']+)[\"'] (?:type|likho|enter) (?:karo|kar)?$",
            RegexOption.IGNORE_CASE
        ).find(cleaned)
        if (hinglishFieldFirst != null) {
            return buildTypeCommand(hinglishFieldFirst.groupValues[2], hinglishFieldFirst.groupValues[1])
        }

        val hinglishTextFirst = Regex(
            "^[\"']([^\"']+)[\"'] (.+?) (?:mein|me) (?:type|likho|enter) (?:karo|kar)?$",
            RegexOption.IGNORE_CASE
        ).find(cleaned)
        if (hinglishTextFirst != null) {
            return buildTypeCommand(hinglishTextFirst.groupValues[1], hinglishTextFirst.groupValues[2])
        }

        // Hindi: "<field> में 'text' टाइप करो / लिखो"
        val hindiFieldFirst = Regex(
            "^(.+?) में [\"']([^\"']+)[\"'] (?:टाइप|लिखो|एंटर)(?: करो| करें)?$"
        ).find(cleaned)
        if (hindiFieldFirst != null) {
            return buildTypeCommand(hindiFieldFirst.groupValues[2], hindiFieldFirst.groupValues[1])
        }

        // Marathi: "<field> मध्ये 'text' टाइप कर / करा / लिहा"
        val marathiFieldFirst = Regex(
            "^(.+?) मध्ये [\"']([^\"']+)[\"'] (?:टाइप|टाईप|लिही|लिहा)(?: कर| करा)?$"
        ).find(cleaned)
        if (marathiFieldFirst != null) {
            return buildTypeCommand(marathiFieldFirst.groupValues[2], marathiFieldFirst.groupValues[1])
        }

        return null
    }

    private fun buildTypeCommand(textVal: String, rawField: String): LocalCommandMatch.ToolCommand? {
        val fieldTarget = cleanSelector(rawField)
        if (textVal.isEmpty() || fieldTarget.isBlank()) return null
        val args = mutableMapOf("text" to textVal)
        if (looksLikeViewId(fieldTarget)) {
            args["view_id"] = fieldTarget.removePrefix("id:").trim()
        } else {
            args["hint"] = fieldTarget
        }
        return LocalCommandMatch.ToolCommand(
            toolName = "type_text",
            args = args,
            requiredCapability = DeviceActionId.UI_TYPE_TEXT
        )
    }

    private fun matchClickUiElement(cleaned: String): LocalCommandMatch.ToolCommand? {
        val rawTarget = Regex("^(?:click|tap|press|select) (?:on )?(?:the )?(.+)$", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(.+?) (?:par|pe) (?:click|tap) (?:karo|kar)$", RegexOption.IGNORE_CASE)
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(?:click|tap) (?:karo|kar) (.+)$", RegexOption.IGNORE_CASE)
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(.+?) (?:पर|पे) (?:क्लिक|टैप) (?:करो|कर|करें|दो)$")
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^क्लिक करो (.+)$")
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(.+?) वर (?:क्लिक|टॅप) (?:कर|करा)$")
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^क्लिक (?:कर|करा) (.+)$")
                .find(cleaned)?.groupValues?.get(1)
            ?: return null

        val target = cleanSelector(rawTarget)
        if (target.isBlank() || target.lowercase(Locale.ROOT) in setOf("back", "home", "recents")) {
            return null
        }
        val args = if (looksLikeViewId(target)) {
            mapOf("view_id" to target.removePrefix("id:").trim())
        } else {
            mapOf("text" to target)
        }
        return LocalCommandMatch.ToolCommand(
            toolName = "click_ui_element",
            args = args,
            requiredCapability = DeviceActionId.UI_CLICK
        )
    }

    private fun matchFindUiElement(cleaned: String): LocalCommandMatch.ToolCommand? {
        val rawTarget = Regex("^(?:find|locate) (?:ui )?(?:element |button |field )?(?:on screen )?(?:the )?(.+)$", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^screen (?:pe|par) (.+?) (?:dhundo|find karo|khojo)$", RegexOption.IGNORE_CASE)
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^स्क्रीन पर (.+?) (?:ढूंढो|खोजो|फाइन करो)$")
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^स्क्रीनवर (.+?) (?:शोध|शोधा)$")
                .find(cleaned)?.groupValues?.get(1)
            ?: return null

        val lowerRaw = rawTarget.lowercase(Locale.ROOT)
        // Avoid hijacking natural-language web/places queries like "find restaurants near me"
        if (lowerRaw.contains("near me") || lowerRaw.contains("nearby") || lowerRaw.contains("restaurants")) {
            return null
        }
        val target = cleanSelector(rawTarget)
        if (target.isBlank()) return null
        val args = if (looksLikeViewId(target)) {
            mapOf("view_id" to target.removePrefix("id:").trim())
        } else {
            mapOf("text" to target)
        }
        return LocalCommandMatch.ToolCommand(
            toolName = "find_ui_element",
            args = args,
            requiredCapability = DeviceActionId.UI_FIND
        )
    }

    private fun cleanSelector(raw: String): String = raw.trim()
        .removeSuffix(" button")
        .removeSuffix(" tab")
        .removeSuffix(" field")
        .removeSuffix(" box")
        .removeSuffix(" बटन")
        .removeSuffix(" बटण")
        .trim('"', '\'')
        .trim()

    private fun looksLikeViewId(target: String): Boolean =
        target.startsWith("id:", ignoreCase = true) ||
            target.contains(":id/") ||
            (!target.contains(' ') && target.contains('_'))

    private fun containsUntrustedMarker(text: String): Boolean =
        text.contains(ScreenReaderEngine.UNTRUSTED_HEADER, ignoreCase = true) ||
            text.contains(ScreenReaderEngine.UNTRUSTED_FOOTER, ignoreCase = true) ||
            Regex("\\[\\s*/?\\s*UNTRUSTED_SCREEN_CONTENT[^\\]]*\\]", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
}
