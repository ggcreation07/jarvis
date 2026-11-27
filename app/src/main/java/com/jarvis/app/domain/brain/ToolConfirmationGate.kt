package com.jarvis.app.domain.brain

import com.jarvis.app.data.ai.ToolCallExecutionResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

enum class ConfirmationIntent {
    AFFIRM,
    CANCEL,
    AMBIGUOUS,
    UNRELATED
}

data class ActionBindingKey(
    val toolName: String,
    val canonicalArgs: Map<String, String>
)

data class PendingProtectedAction(
    val id: String,
    val binding: ActionBindingKey,
    val description: String,
    val originalArgs: Map<String, JsonElement>,
    val createdAtMs: Long,
    val expiresAtMs: Long
)

sealed interface ConfirmationTurnOutcome {
    data object None : ConfirmationTurnOutcome
    data class Cancelled(val actionDescription: String, val replyText: String) : ConfirmationTurnOutcome
    data class Expired(val actionDescription: String, val replyText: String) : ConfirmationTurnOutcome
    data class NeedsClarification(val actionDescription: String, val replyText: String) : ConfirmationTurnOutcome
    data class Executed(val pending: PendingProtectedAction, val result: ToolCallExecutionResult) : ConfirmationTurnOutcome
}

internal fun ToolCallExecutionResult.isConfirmationRequired(): Boolean =
    (payload["status"] as? JsonPrimitive)?.content == ToolConfirmationPolicy.STATUS_CONFIRMATION_REQUIRED

object ToolConfirmationPolicy {
    const val STATUS_CONFIRMATION_REQUIRED = "confirmation_required"
    const val DEFAULT_CONFIRMATION_TIMEOUT_MS = 45_000L

    private val highImpactPcPatterns = listOf(
        // Destructive file / storage operations
        Regex("\\b(delete|del|erase|wipe|format|shred|remove|unlink|rmdir|rm|purge|destroy|clear|empty)\\b"),
        // Power / session state changes
        Regex("\\b(shutdown|shut\\s+down|restart|reboot|power\\s*off|turn\\s+off|switch\\s+off|hibernate|sleep|log\\s*off|sign\\s*out|lock)\\b"),
        // Shell / admin / system modification / process termination
        Regex("\\b(powershell|cmd|command\\s+prompt|terminal|bash|regedit|registry|taskkill|kill|terminate|uninstall|install|diskpart|chkdsk|bcdedit|netsh|factory\\s+reset|reset|sudo|admin|elevat\\w*|chmod|chown)\\b"),
        // Security / credentials / external financial or outbound actions
        Regex("\\b(password|credential|token|firewall|defender|antivirus|disable|send\\s+e?mail|email|mail|post|publish|tweet|submit|purchase|buy|pay|checkout|transfer|bank)\\b"),
        // Hindi / Marathi / Hinglish destructive or power keywords
        Regex("(hatao|hata\\s+do|mitao|mita\\s+do|udao|uda\\s+do|saaf\\s+kar|khatam\\s+kar|band\\s+karo?|band\\s+kara|delete\\s+kar\\w*|format\\s+kar\\w*|restart\\s+kar\\w*|shutdown\\s+kar\\w*|हटाओ|हटा\\s+दो|मिटाओ|मिटा\\s+दो|उड़ाओ|बंद\\s+करो?|बंद\\s+करा|डिलीट\\s+कर\\w*|फॉर्मेट\\s+कर\\w*)")
    )

    private fun strArg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun rawStrArg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content.orEmpty()

    fun assessRisk(toolName: String, args: Map<String, JsonElement>): ActionRiskAssessment =
        ActionRiskPolicy.assess(toolName, args)

    fun requiresConfirmation(toolName: String, args: Map<String, JsonElement>): Boolean =
        ActionRiskPolicy.assess(toolName, args).requiresConfirmation

    fun isHighImpactPcTask(goal: String, kind: String): Boolean {
        val normalized = "${goal.trim()} ${kind.trim()}".lowercase(Locale.ROOT)
        if (normalized.isBlank()) return false
        return highImpactPcPatterns.any { it.containsMatchIn(normalized) }
    }

    fun canonicalizeArgs(toolName: String, args: Map<String, JsonElement>): Map<String, String> {
        val normalizedTool = toolName.trim()
        return when (normalizedTool) {
            "forget_fact" -> mapOf(
                "text" to strArg(args, "text").replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
            )
            "manage_schedule" -> mapOf(
                "action" to strArg(args, "action").ifBlank { "get" }.lowercase(Locale.ROOT)
            )
            "control_interface" -> mapOf(
                "action" to strArg(args, "action").lowercase(Locale.ROOT)
            )
            "customize_screen" -> mapOf(
                "action" to strArg(args, "action").lowercase(Locale.ROOT)
            )
            "device_control" -> mapOf(
                "action" to strArg(args, "action").lowercase(Locale.ROOT)
            )
            "delete_file" -> mapOf(
                "path" to strArg(args, "path").ifBlank { strArg(args, "name") }.lowercase(Locale.ROOT)
            )
            "uninstall_app", "uninstall_application" -> mapOf(
                "package" to strArg(args, "package").ifBlank { strArg(args, "name") }.lowercase(Locale.ROOT)
            )
            "open_application" -> mapOf(
                "name" to strArg(args, "name").lowercase(Locale.ROOT)
            )
            "send_message", "compose_message" -> mapOf(
                "to" to strArg(args, "to").lowercase(Locale.ROOT),
                "body" to strArg(args, "body").ifBlank { strArg(args, "message") }.replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
            )
            "pc_task" -> mapOf(
                "goal" to strArg(args, "goal").replace(Regex("\\s+"), " ").lowercase(Locale.ROOT),
                "kind" to strArg(args, "kind").ifBlank { "browser" }.lowercase(Locale.ROOT)
            )
            "click_ui_element" -> mapOf(
                "action" to strArg(args, "_bound_action").ifBlank { "click" }.lowercase(Locale.ROOT),
                "package" to strArg(args, "_bound_package").ifBlank { strArg(args, "expected_package") }.lowercase(Locale.ROOT),
                "target_identity" to strArg(args, "_bound_fingerprint").ifBlank {
                    listOf(
                        strArg(args, "view_id"),
                        strArg(args, "text"),
                        strArg(args, "content_description"),
                        strArg(args, "hint")
                    ).joinToString("|")
                }.lowercase(Locale.ROOT),
                "text" to strArg(args, "text").lowercase(Locale.ROOT),
                "content_description" to strArg(args, "content_description").lowercase(Locale.ROOT),
                "view_id" to strArg(args, "view_id").lowercase(Locale.ROOT),
                "hint" to strArg(args, "hint").lowercase(Locale.ROOT),
                "class_name" to strArg(args, "class_name").lowercase(Locale.ROOT)
            )
            "type_text" -> mapOf(
                "action" to strArg(args, "_bound_action").ifBlank { "type_text" }.lowercase(Locale.ROOT),
                "package" to strArg(args, "_bound_package").ifBlank { strArg(args, "expected_package") }.lowercase(Locale.ROOT),
                "field_identity" to strArg(args, "_bound_fingerprint").ifBlank {
                    listOf(
                        strArg(args, "view_id"),
                        strArg(args, "target_text"),
                        strArg(args, "content_description"),
                        strArg(args, "hint")
                    ).joinToString("|")
                }.lowercase(Locale.ROOT),
                "text" to rawStrArg(args, "text"),
                "target_text" to strArg(args, "target_text").lowercase(Locale.ROOT),
                "content_description" to strArg(args, "content_description").lowercase(Locale.ROOT),
                "view_id" to strArg(args, "view_id").lowercase(Locale.ROOT),
                "hint" to strArg(args, "hint").lowercase(Locale.ROOT),
                "class_name" to strArg(args, "class_name").lowercase(Locale.ROOT)
            )
            else -> args.entries
                .sortedBy { it.key }
                .associate { (k, v) ->
                    k to ((v as? JsonPrimitive)?.content?.trim()?.lowercase(Locale.ROOT) ?: v.toString())
                }
        }
    }

    fun describeProtectedAction(toolName: String, args: Map<String, JsonElement>): String {
        val normalizedTool = toolName.trim()
        return when (normalizedTool) {
            "forget_fact" -> "delete all remembered facts from long-term memory"
            "manage_schedule" -> "clear all local items from today's schedule"
            "control_interface" -> "clear the current conversation history"
            "customize_screen" -> "reset HUD appearance and theme customization to defaults"
            "device_control" -> "enable Do Not Disturb mode and silence notifications"
            "delete_file" -> {
                val path = strArg(args, "path").ifBlank { strArg(args, "name") }.ifBlank { "the selected file" }
                "permanently delete the file \"$path\" from your granted folder"
            }
            "uninstall_app", "uninstall_application" -> {
                val target = strArg(args, "package").ifBlank { strArg(args, "name") }.ifBlank { "the application" }
                "prompt uninstallation of the application \"$target\""
            }
            "open_application" -> {
                val name = strArg(args, "name")
                "prompt uninstallation for \"$name\""
            }
            "send_message", "compose_message" -> {
                val to = strArg(args, "to").ifBlank { "recipient" }
                "prepare and send a message to $to"
            }
            "pc_task" -> {
                val goal = strArg(args, "goal").ifBlank { "remote command" }.take(100)
                val kind = strArg(args, "kind").ifBlank { "browser" }
                "run the high-impact PC task \"$goal\" ($kind mode) on your paired Windows PC"
            }
            "click_ui_element" -> {
                val label = strArg(args, "_resolved_label")
                    .ifBlank { strArg(args, "text") }
                    .ifBlank { strArg(args, "content_description") }
                    .ifBlank { strArg(args, "view_id") }
                    .ifBlank { "the selected UI element" }
                    .replace(Regex("[\\u0000-\\u001F\\u007F]+"), " ")
                    .take(80)
                val pkg = strArg(args, "_bound_package").ifBlank { strArg(args, "expected_package") }
                if (pkg.isNotBlank()) "click \"$label\" in $pkg" else "click \"$label\""
            }
            "type_text" -> {
                val label = strArg(args, "_resolved_label")
                    .ifBlank { strArg(args, "hint") }
                    .ifBlank { strArg(args, "content_description") }
                    .ifBlank { strArg(args, "view_id") }
                    .ifBlank { strArg(args, "target_text") }
                    .ifBlank { "the selected input field" }
                    .replace(Regex("[\\u0000-\\u001F\\u007F]+"), " ")
                    .take(80)
                val charCount = rawStrArg(args, "text").length
                val pkg = strArg(args, "_bound_package").ifBlank { strArg(args, "expected_package") }
                if (pkg.isNotBlank()) {
                    "enter text ($charCount chars) into \"$label\" in $pkg (without submitting)"
                } else {
                    "enter text ($charCount chars) into \"$label\" (without submitting)"
                }
            }
            else -> "execute $normalizedTool"
        }
    }

    fun buildConfirmationPrompt(description: String): String =
        "Confirmation required: This will $description. Please say 'yes' or 'confirm' to proceed, or 'cancel' to abort."

    fun buildCancelledReply(description: String, userInput: String): String {
        val devanagari = containsDevanagari(userInput)
        val marathi = devanagari && isLikelyMarathi(userInput)
        return when {
            marathi -> "रद्द केले. मी ही क्रिया केली नाही ($description)."
            devanagari -> "रद्द किया गया। मैंने यह कार्रवाई नहीं की ($description)।"
            else -> "Cancelled. I did not $description."
        }
    }

    fun buildExpiredReply(description: String, userInput: String): String {
        val devanagari = containsDevanagari(userInput)
        val marathi = devanagari && isLikelyMarathi(userInput)
        return when {
            marathi -> "पुष्टीची वेळ संपली आहे, त्यामुळे मी ही क्रिया केली नाही ($description). कृपया पुन्हा विनंती करा."
            devanagari -> "पुष्टि का समय समाप्त हो गया है, इसलिए मैंने यह कार्रवाई नहीं की ($description)। कृपया फिर से अनुरोध करें।"
            else -> "That confirmation expired, so I did not $description. Ask again if you still want to proceed."
        }
    }

    fun buildClarificationPrompt(description: String, userInput: String): String {
        val devanagari = containsDevanagari(userInput)
        val marathi = devanagari && isLikelyMarathi(userInput)
        return when {
            marathi -> "कृपया स्पष्ट सांगा: '$description' पुढे सुरू ठेवण्यासाठी 'हो करा' म्हणा किंवा 'नाही' / 'रद्द करा' म्हणा."
            devanagari -> "कृपया स्पष्ट पुष्टि करें: '$description' के लिए 'हाँ कर दो' कहें या 'नहीं' / 'रद्द करो' कहें।"
            else -> "Please confirm clearly: say 'yes' or 'confirm' to $description, or 'cancel' to abort."
        }
    }

    private fun containsDevanagari(text: String): Boolean =
        text.any { it in '\u0900'..'\u097F' }

    private fun isLikelyMarathi(text: String): Boolean =
        listOf("हो", "होय", "करा", "नाही", "नको", "थांब", "रद्द करा", "सगळं").any { text.contains(it) }
}

object ConfirmationClassifier {
    private val affirmPhrases = listOf(
        "go ahead", "do it", "yes please", "yes confirm", "yes proceed", "yes go ahead", "yes do it",
        "ok confirm", "okay confirm", "ok proceed", "okay proceed", "ok go ahead", "okay go ahead",
        "ok do it", "okay do it", "haan ji", "ji haan", "kar do", "karo", "kar de", "kar dijiye",
        "haan kar do", "han kar do", "haan karo", "ok kar do", "theek hai kar do", "thik hai kar do",
        "ho kara", "hoy kara", "nakki kara", "ok kara", "aage badho", "pudhe ja",
        "हाँ जी", "जी हाँ", "कर दो", "कर दीजिए", "हाँ कर दो", "हां कर दो", "ठीक है कर दो",
        "हो करा", "होय करा", "नक्की करा", "ठीक आहे करा", "पुढे जा", "आगे बढ़ो"
    )

    private val affirmTokens = setOf(
        "yes", "yeah", "yep", "yup", "confirm", "confirmed", "proceed", "sure", "approve", "approved",
        "continue", "haan", "han", "haanji", "hoy", "ho", "nakki",
        "हाँ", "हां", "हो", "होय", "नक्की", "कन्फर्म"
    )

    private val cancelPhrases = listOf(
        "do not", "never mind", "forget it", "leave it", "no thanks", "no thank you",
        "mat kar", "mat karo", "mat kara", "rehne do", "rahne do", "cancel kar", "cancel karo", "cancel kara",
        "radd kar", "radd karo", "radd kara", "rahu de", "sodun dya",
        "रहने दो", "मत कर", "मत करो", "रद्द कर", "रद्द कर दो", "रद्द करो", "रद्द करा",
        "राहू दे", "राहु दे", "सोडून द्या", "राहू द्या", "नाही नको", "नाही करू"
    )

    private val cancelTokens = setOf(
        "no", "nope", "nah", "cancel", "abort", "stop", "nevermind", "dont", "skip",
        "nahi", "naahi", "naa", "nako", "nakko", "radd", "chhodo", "chodo", "mat",
        "नहीं", "नही", "ना", "नाही", "नको", "रद्द", "थांबवा", "छोड़ो", "मत"
    )

    private val ambiguousPhrases = listOf(
        "not sure", "let me think", "hold on", "one second", "one minute", "give me a sec",
        "pata nahi", "maloom nahi", "baad mein", "baad me", "soch ke", "dekhna padega",
        "ek second", "ek minute", "पता नहीं", "मालूम नहीं", "बाद में", "विचार करतो",
        "माहित नाही", "माहीत नाही"
    )

    private val ambiguousTokens = setOf(
        "maybe", "unsure", "perhaps", "probably", "hmm", "hmmm", "hmmmm", "um", "umm", "uh", "uhh",
        "wait", "later", "depends", "shayad", "shayed", "ruko", "rukho", "thamb", "thamba", "thambo",
        "kadachit", "baghu", "शायद", "रुको", "थांब", "थांबा", "थांबो", "कदाचित", "बघू", "नंतर"
    )

    private val contrastTokens = setOf(
        "but", "however", "though", "par", "lekin", "magar", "pan",
        "पण", "पर", "लेकिन", "मगर"
    )

    private val bareAckTokens = setOf(
        "ok", "okay", "k", "alright", "fine", "achha", "accha", "acchha", "theek", "thik",
        "hai", "ahe", "sahi", "bar", "bara", "bary", "ठीक", "अच्छा", "है", "आहे", "बरं", "बरोबर"
    )

    private val politeAndEchoTokens = setOf(
        "jarvis", "please", "pls", "sir", "ji", "ok", "okay", "alright", "fine", "bro", "yaar",
        "it", "that", "this", "the", "my", "our", "all", "everything", "action", "command", "task",
        "clear", "delete", "forget", "remove", "reset", "shutdown", "chat", "schedule", "agenda",
        "memory", "facts", "pc", "sab", "sub", "sagle", "kar", "do", "kara", "karo", "de", "dijiye",
        "theek", "thik", "hai", "ahe", "ahead", "go",
        "dnd", "disturb", "file", "uninstall", "message", "send", "app", "mode", "theme", "hud", "setting", "settings",
        "अरे", "कृपया", "जी", "ठीक", "है", "आहे", "सगळं", "सब", "कर", "दो", "करा", "करो", "दीजिए",
        "हटा", "हटाओ", "मिटाओ", "डिलीट", "चैट", "शेड्यूल", "मेमोरी", "फाइल", "मैसेज", "संदेश", "अॅप", "अनइंस्टॉल"
    )

    fun classify(rawInput: String): ConfirmationIntent {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ConfirmationIntent.AMBIGUOUS

        val normalized = trimmed
            .lowercase(Locale.ROOT)
            .replace("'", "")
            .replace("\u2019", "")
            .replace(Regex("[\\p{Punct}\\u0964\\u0965]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return ConfirmationIntent.AMBIGUOUS

        val tokens = normalized.split(" ").filter { it.isNotBlank() }

        val hasAmbiguous = tokens.any { it in ambiguousTokens } ||
            ambiguousPhrases.any { containsPhrase(normalized, it) }
        val hasContrast = tokens.any { it in contrastTokens }
        val hasCancel = tokens.any { it in cancelTokens } ||
            cancelPhrases.any { containsPhrase(normalized, it) }

        var withoutCancel = " $normalized "
        cancelPhrases.sortedByDescending { it.length }.forEach { phrase ->
            withoutCancel = withoutCancel.replace(" $phrase ", " ")
        }
        val withoutCancelNormalized = withoutCancel.replace(Regex("\\s+"), " ").trim()
        val withoutCancelTokens = withoutCancelNormalized.split(" ").filter { it.isNotBlank() }

        val hasAffirm = withoutCancelTokens.any { it in affirmTokens } ||
            affirmPhrases.any { containsPhrase(withoutCancelNormalized, it) }

        if (hasAffirm && (hasAmbiguous || hasCancel || hasContrast)) {
            return ConfirmationIntent.AMBIGUOUS
        }

        if (hasCancel) {
            return ConfirmationIntent.CANCEL
        }

        if (hasAmbiguous) {
            return ConfirmationIntent.AMBIGUOUS
        }

        if (hasAffirm) {
            val affirmPhraseTokens = affirmPhrases
                .filter { containsPhrase(withoutCancelNormalized, it) }
                .flatMap { it.split(" ") }
                .toSet()
            val leftoverTokens = tokens.filterNot { token ->
                token in affirmTokens || token in affirmPhraseTokens || token in politeAndEchoTokens
            }
            return if (leftoverTokens.isEmpty() && tokens.size <= 8) {
                ConfirmationIntent.AFFIRM
            } else {
                ConfirmationIntent.AMBIGUOUS
            }
        }

        val isBareAck = tokens.any { it in bareAckTokens } &&
            tokens.all { it in bareAckTokens || it in setOf("jarvis", "please", "sir", "ji") }
        if (isBareAck) {
            return ConfirmationIntent.AMBIGUOUS
        }

        return ConfirmationIntent.UNRELATED
    }

    private fun containsPhrase(normalizedText: String, phrase: String): Boolean {
        val paddedText = " $normalizedText "
        val paddedPhrase = " $phrase "
        return paddedText.contains(paddedPhrase)
    }
}
