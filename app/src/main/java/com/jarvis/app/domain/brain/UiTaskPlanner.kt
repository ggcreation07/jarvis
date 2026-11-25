package com.jarvis.app.domain.brain

import com.jarvis.app.platform.ScreenInteractionStatus
import com.jarvis.app.platform.ScrollDirection
import com.jarvis.app.platform.ScreenReaderEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Locale

enum class PlannedTaskAction(
    val toolName: String,
    val isUiChanging: Boolean
) {
    OPEN_APP("open_application", isUiChanging = true),
    READ_SCREEN("read_screen", isUiChanging = false),
    FIND_UI_ELEMENT("find_ui_element", isUiChanging = false),
    CLICK_UI_ELEMENT("click_ui_element", isUiChanging = true),
    TYPE_TEXT("type_text", isUiChanging = true),
    SCROLL_UI("scroll_ui", isUiChanging = true);

    companion object {
        fun fromNameOrNull(raw: String): PlannedTaskAction? = when (raw.trim().lowercase(Locale.ROOT)) {
            "open_app", "open_application", "open" -> OPEN_APP
            "read_screen" -> READ_SCREEN
            "find_ui_element", "find" -> FIND_UI_ELEMENT
            "click_ui_element", "click", "tap" -> CLICK_UI_ELEMENT
            "type_text", "type", "enter_text" -> TYPE_TEXT
            "scroll_ui", "scroll" -> SCROLL_UI
            else -> null
        }
    }
}

enum class TaskPlanErrorCode {
    EMPTY_PLAN,
    TOO_MANY_STEPS,
    UNSUPPORTED_ACTION,
    INVALID_ARGUMENTS,
    DUPLICATE_ACTION,
    UNTRUSTED_OR_FORBIDDEN_ACTION
}

data class RawTaskStep(
    val action: String,
    val args: Map<String, JsonElement> = emptyMap(),
    val description: String = "",
    val expectedPackage: String = ""
)

data class PlannedTaskStep(
    val index: Int,
    val action: PlannedTaskAction,
    val description: String,
    val args: Map<String, JsonElement>,
    val expectedPackage: String = "",
    val verifyAfterAction: Boolean = action.isUiChanging
) {
    fun signatureKey(): String {
        val canonical = args.entries
            .filterNot { it.key.startsWith("_") || it.key == "expected_package" || it.key == "expected_fingerprint" }
            .sortedBy { it.key }
            .joinToString("&") { (k, v) ->
                val raw = (v as? JsonPrimitive)?.content ?: v.toString()
                val normalized = if (action == PlannedTaskAction.TYPE_TEXT && k == "text") {
                    raw
                } else {
                    raw.trim().lowercase(Locale.ROOT)
                }
                "$k=$normalized"
            }
        return "${action.toolName}|$canonical"
    }
}

data class UiTaskPlan(
    val id: String,
    val objective: String,
    val steps: List<PlannedTaskStep>,
    val createdAtMs: Long,
    val expectedFinalPackage: String = "",
    val expectedFinalElement: String = ""
)

sealed interface TaskPlanValidationResult {
    data class Valid(val plan: UiTaskPlan) : TaskPlanValidationResult
    data class Invalid(
        val reason: String,
        val errorCode: TaskPlanErrorCode
    ) : TaskPlanValidationResult
}

sealed interface NaturalPlanParseResult {
    data class Planned(val plan: UiTaskPlan) : NaturalPlanParseResult
    data class Rejected(
        val reason: String,
        val errorCode: TaskPlanErrorCode
    ) : NaturalPlanParseResult
    data object NotApplicable : NaturalPlanParseResult
}

object UiTaskPlanner {
    const val MAX_PLANNED_STEPS = 10
    const val MAX_PLAN_CORRECTIONS = 2

    private val lenientJson = Json { ignoreUnknownKeys = true }

    private fun strArg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun rawStrArg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content.orEmpty()

    fun validatePlan(
        objective: String,
        rawSteps: List<RawTaskStep>,
        id: String = "plan_${System.currentTimeMillis()}",
        nowMs: Long = System.currentTimeMillis(),
        expectedFinalPackage: String = "",
        expectedFinalElement: String = ""
    ): TaskPlanValidationResult {
        val cleanObjective = objective.trim().ifBlank { "Multi-step UI task" }
        if (containsUntrustedMarker(cleanObjective)) {
            return TaskPlanValidationResult.Invalid(
                reason = "Rejected plan because it contains untrusted screen-content delimiters.",
                errorCode = TaskPlanErrorCode.UNTRUSTED_OR_FORBIDDEN_ACTION
            )
        }
        if (rawSteps.isEmpty()) {
            return TaskPlanValidationResult.Invalid(
                reason = "Task plan must include at least 1 step.",
                errorCode = TaskPlanErrorCode.EMPTY_PLAN
            )
        }
        if (rawSteps.size > MAX_PLANNED_STEPS) {
            return TaskPlanValidationResult.Invalid(
                reason = "Planned task has ${rawSteps.size} steps, exceeding the maximum limit of $MAX_PLANNED_STEPS steps.",
                errorCode = TaskPlanErrorCode.TOO_MANY_STEPS
            )
        }

        val validatedSteps = mutableListOf<PlannedTaskStep>()
        val signatureCounts = mutableMapOf<String, Int>()

        rawSteps.forEachIndexed { idx, raw ->
            when (val stepRes = validateSingleStep(idx + 1, raw)) {
                is StepValidation.Invalid -> return TaskPlanValidationResult.Invalid(stepRes.reason, stepRes.code)
                is StepValidation.Valid -> {
                    val step = stepRes.step
                    val sig = step.signatureKey()
                    if (validatedSteps.isNotEmpty() && validatedSteps.last().signatureKey() == sig) {
                        return TaskPlanValidationResult.Invalid(
                            reason = "Duplicate consecutive action prevented at step ${step.index}: ${step.description}.",
                            errorCode = TaskPlanErrorCode.DUPLICATE_ACTION
                        )
                    }
                    val count = (signatureCounts[sig] ?: 0) + 1
                    signatureCounts[sig] = count
                    if (step.action != PlannedTaskAction.SCROLL_UI && count > 2) {
                        return TaskPlanValidationResult.Invalid(
                            reason = "Looping/duplicate action prevented at step ${step.index}: ${step.description}.",
                            errorCode = TaskPlanErrorCode.DUPLICATE_ACTION
                        )
                    }
                    validatedSteps.add(step)
                    if (validatedSteps.size >= 4) {
                        val s = validatedSteps.map { it.signatureKey() }
                        val n = s.size
                        if (s[n - 1] == s[n - 3] && s[n - 2] == s[n - 4]) {
                            return TaskPlanValidationResult.Invalid(
                                reason = "Oscillating duplicate loop prevented at step ${step.index}.",
                                errorCode = TaskPlanErrorCode.DUPLICATE_ACTION
                            )
                        }
                    }
                }
            }
        }

        return TaskPlanValidationResult.Valid(
            UiTaskPlan(
                id = id,
                objective = cleanObjective,
                steps = validatedSteps,
                createdAtMs = nowMs,
                expectedFinalPackage = expectedFinalPackage,
                expectedFinalElement = expectedFinalElement
            )
        )
    }

    internal sealed interface StepValidation {
        data class Valid(val step: PlannedTaskStep) : StepValidation
        data class Invalid(val reason: String, val code: TaskPlanErrorCode) : StepValidation
    }

    internal fun validateSingleStep(stepIndex: Int, raw: RawTaskStep): StepValidation {
        val action = PlannedTaskAction.fromNameOrNull(raw.action)
            ?: return StepValidation.Invalid(
                reason = "Unsupported action '${raw.action}' at step $stepIndex. Only open_app, read_screen, find_ui_element, click_ui_element, type_text, and scroll_ui are allowed.",
                code = TaskPlanErrorCode.UNSUPPORTED_ACTION
            )

        val cleanArgs = raw.args.filterKeys { !it.startsWith("_") }.toMutableMap()
        val expectedPkg = raw.expectedPackage.trim()
            .ifBlank { strArg(cleanArgs, "expected_package").ifBlank { strArg(cleanArgs, "package_name") } }
        if (containsUntrustedMarker(raw.description) ||
            containsUntrustedMarker(expectedPkg) ||
            cleanArgs.values.any { el -> (el as? JsonPrimitive)?.content?.let { containsUntrustedMarker(it) } == true }
        ) {
            return StepValidation.Invalid(
                reason = "Step $stepIndex rejected untrusted screen-content delimiter in step arguments.",
                code = TaskPlanErrorCode.UNTRUSTED_OR_FORBIDDEN_ACTION
            )
        }

        when (action) {
            PlannedTaskAction.OPEN_APP -> {
                val appName = strArg(cleanArgs, "name").ifBlank { strArg(cleanArgs, "app") }
                if (appName.isBlank()) {
                    return StepValidation.Invalid(
                        reason = "Step $stepIndex (open_application) requires a non-empty app name.",
                        code = TaskPlanErrorCode.INVALID_ARGUMENTS
                    )
                }
                val lower = appName.lowercase(Locale.ROOT)
                if (lower.startsWith("uninstall ") || lower.startsWith("delete ") || containsUntrustedMarker(appName)) {
                    return StepValidation.Invalid(
                        reason = "Forbidden or untrusted open_application target at step $stepIndex.",
                        code = TaskPlanErrorCode.UNTRUSTED_OR_FORBIDDEN_ACTION
                    )
                }
                cleanArgs["name"] = JsonPrimitive(appName)
            }
            PlannedTaskAction.READ_SCREEN -> {
                // No required arguments
            }
            PlannedTaskAction.FIND_UI_ELEMENT, PlannedTaskAction.CLICK_UI_ELEMENT -> {
                val hasSelector = listOf("text", "content_description", "view_id", "hint")
                    .any { strArg(cleanArgs, it).isNotBlank() }
                if (!hasSelector) {
                    return StepValidation.Invalid(
                        reason = "Step $stepIndex (${action.toolName}) requires at least one selector (text, content_description, view_id, or hint).",
                        code = TaskPlanErrorCode.INVALID_ARGUMENTS
                    )
                }
            }
            PlannedTaskAction.TYPE_TEXT -> {
                val textValue = rawStrArg(cleanArgs, "text")
                if (textValue.isBlank()) {
                    return StepValidation.Invalid(
                        reason = "Step $stepIndex (type_text) requires explicit non-empty text.",
                        code = TaskPlanErrorCode.INVALID_ARGUMENTS
                    )
                }
                if (containsUntrustedMarker(textValue)) {
                    return StepValidation.Invalid(
                        reason = "Step $stepIndex (type_text) rejected untrusted screen-content delimiter in text.",
                        code = TaskPlanErrorCode.UNTRUSTED_OR_FORBIDDEN_ACTION
                    )
                }
                val hasSelector = listOf("target_text", "content_description", "view_id", "hint")
                    .any { strArg(cleanArgs, it).isNotBlank() }
                if (!hasSelector) {
                    return StepValidation.Invalid(
                        reason = "Step $stepIndex (type_text) requires a field selector (view_id, hint, content_description, or target_text).",
                        code = TaskPlanErrorCode.INVALID_ARGUMENTS
                    )
                }
            }
            PlannedTaskAction.SCROLL_UI -> {
                val dir = ScrollDirection.parseOrNull(strArg(cleanArgs, "direction"))
                    ?: return StepValidation.Invalid(
                        reason = "Step $stepIndex (scroll_ui) requires direction 'UP' or 'DOWN' (status=${ScreenInteractionStatus.ACTION_FAILED.name}).",
                        code = TaskPlanErrorCode.INVALID_ARGUMENTS
                    )
                cleanArgs["direction"] = JsonPrimitive(dir.name)
            }
        }

        val desc = raw.description.trim().ifBlank { buildStepDescription(action, cleanArgs) }
        return StepValidation.Valid(
            PlannedTaskStep(
                index = stepIndex,
                action = action,
                description = desc,
                args = cleanArgs.toMap(),
                expectedPackage = expectedPkg,
                verifyAfterAction = action.isUiChanging
            )
        )
    }

    fun planFromJson(
        objective: String,
        stepsElement: JsonElement?,
        id: String = "plan_${System.currentTimeMillis()}",
        nowMs: Long = System.currentTimeMillis()
    ): TaskPlanValidationResult {
        if (stepsElement == null) {
            return TaskPlanValidationResult.Invalid(
                reason = "Missing steps for task plan.",
                errorCode = TaskPlanErrorCode.EMPTY_PLAN
            )
        }
        val array: JsonArray = when (stepsElement) {
            is JsonArray -> stepsElement
            is JsonPrimitive -> {
                val raw = stepsElement.content.trim()
                if (raw.isBlank()) {
                    return TaskPlanValidationResult.Invalid(
                        reason = "Empty steps JSON string.",
                        errorCode = TaskPlanErrorCode.EMPTY_PLAN
                    )
                }
                try {
                    val parsed = lenientJson.parseToJsonElement(raw)
                    parsed as? JsonArray ?: return TaskPlanValidationResult.Invalid(
                        reason = "Task steps JSON must be an array of step objects.",
                        errorCode = TaskPlanErrorCode.INVALID_ARGUMENTS
                    )
                } catch (e: Exception) {
                    return TaskPlanValidationResult.Invalid(
                        reason = "Invalid task steps JSON: ${e.message ?: "parse error"}",
                        errorCode = TaskPlanErrorCode.INVALID_ARGUMENTS
                    )
                }
            }
            else -> return TaskPlanValidationResult.Invalid(
                reason = "Task steps must be a JSON array.",
                errorCode = TaskPlanErrorCode.INVALID_ARGUMENTS
            )
        }

        val rawSteps = mutableListOf<RawTaskStep>()
        for ((idx, item) in array.withIndex()) {
            val obj = try {
                item.jsonObject
            } catch (_: Exception) {
                return TaskPlanValidationResult.Invalid(
                    reason = "Step ${idx + 1} must be a JSON object.",
                    errorCode = TaskPlanErrorCode.INVALID_ARGUMENTS
                )
            }
            val action = (obj["action"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val description = (obj["description"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val expectedPkg = (obj["expected_package"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val argsObj = (obj["args"] as? JsonObject)
                ?: buildMap<String, JsonElement> {
                    obj.forEach { (k, v) ->
                        if (k !in setOf("action", "description", "expected_package")) {
                            put(k, v)
                        }
                    }
                }.let { JsonObject(it) }
            rawSteps.add(
                RawTaskStep(
                    action = action,
                    args = argsObj,
                    description = description,
                    expectedPackage = expectedPkg
                )
            )
        }

        return validatePlan(objective = objective, rawSteps = rawSteps, id = id, nowMs = nowMs)
    }

    fun planFromUserRequest(
        rawInput: String,
        id: String = "plan_${System.currentTimeMillis()}",
        nowMs: Long = System.currentTimeMillis()
    ): NaturalPlanParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return NaturalPlanParseResult.NotApplicable
        if (containsUntrustedMarker(trimmed)) {
            return NaturalPlanParseResult.Rejected(
                reason = "Rejected multi-step input containing untrusted screen-content delimiters.",
                errorCode = TaskPlanErrorCode.UNTRUSTED_OR_FORBIDDEN_ACTION
            )
        }

        val clauses = splitMultiStepClauses(trimmed)
        if (clauses.size < 2) return NaturalPlanParseResult.NotApplicable

        val parsedRawSteps = mutableListOf<RawTaskStep>()
        for (clause in clauses) {
            val step = parseSingleClause(clause) ?: return NaturalPlanParseResult.NotApplicable
            parsedRawSteps.add(step)
        }

        return when (val validated = validatePlan(objective = trimmed, rawSteps = parsedRawSteps, id = id, nowMs = nowMs)) {
            is TaskPlanValidationResult.Valid -> NaturalPlanParseResult.Planned(validated.plan)
            is TaskPlanValidationResult.Invalid -> NaturalPlanParseResult.Rejected(validated.reason, validated.errorCode)
        }
    }

    private fun splitMultiStepClauses(input: String): List<String> {
        val delimiterRegex = Regex(
            "(?:\\s*;\\s*|\\s*,\\s*(?:and\\s+)?then\\s+|\\s+and\\s+then\\s+|\\s+then\\s+|\\s*,\\s+and\\s+|\\s+and\\s+|\\s*,\\s*)",
            RegexOption.IGNORE_CASE
        )
        val clauses = mutableListOf<String>()
        val current = StringBuilder()
        var activeQuote: Char? = null
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (activeQuote != null) {
                current.append(ch)
                if (ch == activeQuote) {
                    activeQuote = null
                }
                i++
                continue
            }
            if (ch == '"' || ch == '\'') {
                activeQuote = ch
                current.append(ch)
                i++
                continue
            }
            val match = delimiterRegex.matchAt(input, i)
            if (match != null && match.value.isNotEmpty()) {
                val piece = current.toString().trim().trimEnd('.')
                if (piece.isNotBlank()) {
                    clauses.add(piece)
                }
                current.setLength(0)
                i = match.range.last + 1
            } else {
                current.append(ch)
                i++
            }
        }
        val tail = current.toString().trim().trimEnd('.')
        if (tail.isNotBlank()) {
            clauses.add(tail)
        }
        return clauses
    }

    private fun parseSingleClause(clause: String): RawTaskStep? {
        val lower = clause.lowercase(Locale.ROOT)

        // 1. read screen
        if (Regex("^(?:read|inspect|scan|check)\\s+(?:the\\s+)?(?:active\\s+)?screen$").matches(lower)) {
            return RawTaskStep(action = "read_screen", description = "Read the active screen")
        }

        // 2. scroll_ui (UP / DOWN only)
        val scrollMatch = Regex("^scroll\\s+(up|down)(?:\\s+(?:on|in)\\s+(.+))?$", RegexOption.IGNORE_CASE).find(clause)
        if (scrollMatch != null) {
            val dir = scrollMatch.groupValues[1].uppercase(Locale.ROOT)
            val target = scrollMatch.groupValues[2].trim().trim('"', '\'')
            val args = mutableMapOf<String, JsonElement>("direction" to JsonPrimitive(dir))
            if (target.isNotBlank()) {
                if (looksLikeViewId(target)) {
                    args["view_id"] = JsonPrimitive(target.removePrefix("id:").trim())
                } else {
                    args["content_description"] = JsonPrimitive(target)
                }
            }
            return RawTaskStep(action = "scroll_ui", args = args)
        }

        // 3. type_text: e.g. type "hello" into Search / enter hello in message_box
        val typeQuoted = Regex(
            "^(?:type|enter|write|input)\\s+[\"']([^\"']+)[\"']\\s+(?:in|into|on)\\s+(?:the\\s+)?(.+?)(?:\\s+field|\\s+box)?$",
            RegexOption.IGNORE_CASE
        ).find(clause)
        val typeUnquoted = if (typeQuoted == null) {
            Regex(
                "^(?:type|enter|write|input)\\s+(.+?)\\s+(?:in|into)\\s+(?:the\\s+)?(.+?)(?:\\s+field|\\s+box)?$",
                RegexOption.IGNORE_CASE
            ).find(clause)
        } else {
            null
        }
        val typeMatch = typeQuoted ?: typeUnquoted
        if (typeMatch != null) {
            val textVal = typeMatch.groupValues[1]
            val fieldTarget = typeMatch.groupValues[2].trim().trim('"', '\'')
            if (textVal.isNotBlank() && fieldTarget.isNotBlank()) {
                val args = mutableMapOf<String, JsonElement>("text" to JsonPrimitive(textVal))
                if (looksLikeViewId(fieldTarget)) {
                    args["view_id"] = JsonPrimitive(fieldTarget.removePrefix("id:").trim())
                } else {
                    args["hint"] = JsonPrimitive(fieldTarget)
                }
                return RawTaskStep(action = "type_text", args = args)
            }
        }

        // 4. click_ui_element
        val clickMatch = Regex("^(?:click|tap|press|select)\\s+(?:on\\s+)?(?:the\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(clause)
        if (clickMatch != null) {
            val rawTarget = clickMatch.groupValues[1].trim()
                .removeSuffix(" button")
                .removeSuffix(" tab")
                .trim('"', '\'')
            if (rawTarget.isNotBlank() && rawTarget.lowercase(Locale.ROOT) !in setOf("back", "home", "recents")) {
                val args = if (looksLikeViewId(rawTarget)) {
                    mapOf("view_id" to JsonPrimitive(rawTarget.removePrefix("id:").trim()))
                } else {
                    mapOf("text" to JsonPrimitive(rawTarget))
                }
                return RawTaskStep(action = "click_ui_element", args = args)
            }
        }

        // 5. find_ui_element
        val findMatch = Regex("^(?:find|locate)\\s+(?:the\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(clause)
        if (findMatch != null) {
            val rawTarget = findMatch.groupValues[1].trim().trim('"', '\'')
            if (rawTarget.isNotBlank()) {
                val args = if (looksLikeViewId(rawTarget)) {
                    mapOf("view_id" to JsonPrimitive(rawTarget.removePrefix("id:").trim()))
                } else {
                    mapOf("text" to JsonPrimitive(rawTarget))
                }
                return RawTaskStep(action = "find_ui_element", args = args)
            }
        }

        // 6. open_application
        val openMatch = Regex("^(?:open|launch)\\s+(?:the\\s+)?([a-z0-9 ._\\-]{2,40})$", RegexOption.IGNORE_CASE).find(clause)
        if (openMatch != null) {
            val appName = openMatch.groupValues[1].trim().removeSuffix(" app").trim()
            if (appName.isNotBlank() &&
                appName.lowercase(Locale.ROOT) !in setOf("chat", "memory", "skills", "capabilities", "customize", "power", "remote", "recents", "notifications", "quick settings")
            ) {
                return RawTaskStep(action = "open_application", args = mapOf("name" to JsonPrimitive(appName)))
            }
        }

        return null
    }

    private fun looksLikeViewId(target: String): Boolean =
        target.startsWith("id:", ignoreCase = true) ||
            target.contains(":id/") ||
            (!target.contains(' ') && target.contains('_'))

    private val untrustedMarkerRegex = Regex(
        "\\[\\s*/?\\s*UNTRUSTED_SCREEN_CONTENT[^\\]]*\\]",
        RegexOption.IGNORE_CASE
    )

    private fun containsUntrustedMarker(text: String): Boolean =
        text.contains(ScreenReaderEngine.UNTRUSTED_HEADER, ignoreCase = true) ||
            text.contains(ScreenReaderEngine.UNTRUSTED_FOOTER, ignoreCase = true) ||
            untrustedMarkerRegex.containsMatchIn(text)

    internal fun buildStepDescription(action: PlannedTaskAction, args: Map<String, JsonElement>): String = when (action) {
        PlannedTaskAction.OPEN_APP -> "Open ${strArg(args, "name")}"
        PlannedTaskAction.READ_SCREEN -> "Read current screen"
        PlannedTaskAction.FIND_UI_ELEMENT -> {
            val target = strArg(args, "text").ifBlank { strArg(args, "view_id") }
                .ifBlank { strArg(args, "content_description") }.ifBlank { strArg(args, "hint") }
            "Find \"$target\""
        }
        PlannedTaskAction.CLICK_UI_ELEMENT -> {
            val target = strArg(args, "text").ifBlank { strArg(args, "view_id") }
                .ifBlank { strArg(args, "content_description") }.ifBlank { strArg(args, "hint") }
            "Click \"$target\""
        }
        PlannedTaskAction.TYPE_TEXT -> {
            val field = strArg(args, "hint").ifBlank { strArg(args, "view_id") }
                .ifBlank { strArg(args, "content_description") }.ifBlank { strArg(args, "target_text") }
            val len = rawStrArg(args, "text").length
            "Enter text ($len chars) into \"$field\""
        }
        PlannedTaskAction.SCROLL_UI -> {
            val dir = strArg(args, "direction").ifBlank { "DOWN" }
            "Scroll $dir"
        }
    }
}
