package com.jarvis.app.domain.brain

import android.provider.Settings
import com.jarvis.app.platform.CapabilityAvailabilityStatus
import com.jarvis.app.platform.DeviceActionId
import com.jarvis.app.platform.DeviceCapabilitiesSnapshot
import com.jarvis.app.platform.DeviceCapabilityRegistry
import com.jarvis.app.platform.ResolvedAppInfo
import com.jarvis.app.platform.ScreenReaderEngine
import com.jarvis.app.platform.ScrollDirection
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

enum class LocalTaskAction(
    val toolName: String,
    val isUiChanging: Boolean,
    val isIdempotent: Boolean,
    val defaultCapability: DeviceActionId?
) {
    OPEN_APP("open_application", isUiChanging = true, isIdempotent = false, DeviceActionId.OPEN_APP),
    WAIT_FOR_APP("wait_for_app", isUiChanging = false, isIdempotent = true, DeviceActionId.SCREEN_READ),
    VERIFY_FOREGROUND_APP("verify_foreground_app", isUiChanging = false, isIdempotent = true, DeviceActionId.SCREEN_READ),
    ADJUST_VOLUME("device_control", isUiChanging = false, isIdempotent = false, DeviceActionId.VOLUME_CONTROL),
    VERIFY_VOLUME("verify_volume", isUiChanging = false, isIdempotent = true, DeviceActionId.VOLUME_CONTROL),
    SET_FLASHLIGHT("device_control", isUiChanging = false, isIdempotent = true, DeviceActionId.FLASHLIGHT),
    VERIFY_FLASHLIGHT("verify_flashlight", isUiChanging = false, isIdempotent = true, DeviceActionId.FLASHLIGHT),
    SET_DND("device_control", isUiChanging = false, isIdempotent = true, DeviceActionId.DO_NOT_DISTURB),
    MEDIA_CONTROL("device_control", isUiChanging = false, isIdempotent = false, DeviceActionId.MEDIA_CONTROL),
    OPEN_SETTINGS("device_control", isUiChanging = true, isIdempotent = true, DeviceActionId.ANDROID_SETTINGS),
    NAVIGATE("accessibility_navigate", isUiChanging = true, isIdempotent = false, null),
    READ_SCREEN("read_screen", isUiChanging = false, isIdempotent = true, DeviceActionId.SCREEN_READ),
    FIND_UI_ELEMENT("find_ui_element", isUiChanging = false, isIdempotent = true, DeviceActionId.UI_FIND),
    CLICK_UI_ELEMENT("click_ui_element", isUiChanging = true, isIdempotent = false, DeviceActionId.UI_CLICK),
    TYPE_TEXT("type_text", isUiChanging = true, isIdempotent = false, DeviceActionId.UI_TYPE_TEXT),
    SCROLL_UI("scroll_ui", isUiChanging = true, isIdempotent = false, DeviceActionId.UI_SCROLL);

    fun toPlannedTaskAction(): PlannedTaskAction = when (this) {
        OPEN_APP -> PlannedTaskAction.OPEN_APP
        WAIT_FOR_APP, VERIFY_FOREGROUND_APP, READ_SCREEN -> PlannedTaskAction.READ_SCREEN
        FIND_UI_ELEMENT -> PlannedTaskAction.FIND_UI_ELEMENT
        CLICK_UI_ELEMENT, OPEN_SETTINGS, NAVIGATE -> PlannedTaskAction.CLICK_UI_ELEMENT
        TYPE_TEXT -> PlannedTaskAction.TYPE_TEXT
        SCROLL_UI -> PlannedTaskAction.SCROLL_UI
        ADJUST_VOLUME, VERIFY_VOLUME, SET_FLASHLIGHT, VERIFY_FLASHLIGHT, SET_DND, MEDIA_CONTROL ->
            PlannedTaskAction.READ_SCREEN
    }
}

data class LocalTaskStep(
    val index: Int,
    val action: LocalTaskAction,
    val description: String,
    val args: Map<String, String> = emptyMap(),
    val expectedPackage: String = "",
    val expectedAppLabel: String = "",
    val requiredCapability: DeviceActionId? = action.defaultCapability
) {
    fun signatureKey(): String {
        val canonical = args.entries
            .filterNot { it.key.startsWith("_") || it.key == "expected_package" || it.key == "expected_fingerprint" }
            .sortedBy { it.key }
            .joinToString("&") { (k, v) ->
                val normalized = if (action == LocalTaskAction.TYPE_TEXT && k == "text") {
                    v
                } else {
                    v.trim().lowercase(Locale.ROOT)
                }
                "$k=$normalized"
            }
        return "${action.name}|$canonical|pkg=${expectedPackage.lowercase(Locale.ROOT)}"
    }

    fun toPlannedTaskStep(): PlannedTaskStep = PlannedTaskStep(
        index = index,
        action = action.toPlannedTaskAction(),
        description = description,
        args = args.mapValues { JsonPrimitive(it.value) },
        expectedPackage = expectedPackage,
        verifyAfterAction = action.isUiChanging
    )
}

data class LocalDeviceTaskPlan(
    val id: String,
    val objective: String,
    val steps: List<LocalTaskStep>,
    val createdAtMs: Long,
    val expectedFinalPackage: String = "",
    val expectedFinalElement: String = ""
) {
    fun toUiTaskPlan(): UiTaskPlan = UiTaskPlan(
        id = id,
        objective = objective,
        steps = steps.map { it.toPlannedTaskStep() },
        createdAtMs = createdAtMs,
        expectedFinalPackage = expectedFinalPackage,
        expectedFinalElement = expectedFinalElement
    )
}

sealed interface LocalTaskPlanResult {
    data class Planned(val plan: LocalDeviceTaskPlan) : LocalTaskPlanResult
    data class ClarificationNeeded(val question: String, val reason: String) : LocalTaskPlanResult
    data class CapabilityBlocked(
        val capability: DeviceActionId,
        val reason: String,
        val settingsAction: String? = null
    ) : LocalTaskPlanResult
    data class Rejected(val reason: String, val errorCode: TaskPlanErrorCode) : LocalTaskPlanResult
    data object NotApplicable : LocalTaskPlanResult
}

class LocalDeviceTaskPlanner(
    private val capabilityRegistry: DeviceCapabilityRegistry? = null,
    private val appResolver: (String) -> ResolvedAppInfo? = { null }
) {
    companion object {
        const val MAX_LOCAL_TASK_STEPS = UiTaskPlanner.MAX_PLANNED_STEPS
        const val MAX_LOCAL_RECOVERY_ATTEMPTS = UiTaskPlanner.MAX_PLAN_CORRECTIONS
    }

    private data class DraftLocalStep(
        val action: LocalTaskAction,
        val description: String,
        val args: Map<String, String> = emptyMap(),
        val expectedPackage: String = "",
        val expectedAppLabel: String = "",
        val requiredCapability: DeviceActionId? = action.defaultCapability
    )

    private sealed interface ClauseParse {
        data class Step(val draft: DraftLocalStep) : ClauseParse
        data class Clarification(val question: String, val reason: String) : ClauseParse
        data class Restricted(
            val capability: DeviceActionId,
            val reason: String,
            val settingsAction: String
        ) : ClauseParse
        data class Forbidden(val reason: String) : ClauseParse
        data object Unrecognized : ClauseParse
    }

    fun planFromUserRequest(
        rawInput: String,
        id: String = "local_task_${System.currentTimeMillis()}",
        nowMs: Long = System.currentTimeMillis()
    ): LocalTaskPlanResult {
        val trimmed = rawInput.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return LocalTaskPlanResult.NotApplicable

        if (containsUntrustedMarker(trimmed)) {
            return LocalTaskPlanResult.Rejected(
                reason = "Rejected input containing untrusted screen-content delimiters.",
                errorCode = TaskPlanErrorCode.UNTRUSTED_OR_FORBIDDEN_ACTION
            )
        }

        // Check standalone or multi-step ambiguity before falling back to AI
        detectStandaloneAmbiguity(trimmed)?.let { return it }

        val clauses = splitMultiStepClauses(trimmed)
        if (clauses.isEmpty()) return LocalTaskPlanResult.NotApplicable

        val drafts = mutableListOf<DraftLocalStep>()
        var lastAppTarget: ResolvedAppInfo? = null
        var lastAppQuery = ""
        var lastUiTarget = ""
        var lastDeviceAction: LocalTaskAction? = null
        var lastFlashExpectedOn: Boolean? = null

        for (rawClause in clauses) {
            when (
                val parsed = parseClause(
                    clause = rawClause,
                    lastAppTarget = lastAppTarget,
                    lastAppQuery = lastAppQuery,
                    lastUiTarget = lastUiTarget,
                    lastDeviceAction = lastDeviceAction,
                    lastFlashExpectedOn = lastFlashExpectedOn
                )
            ) {
                is ClauseParse.Clarification -> {
                    return LocalTaskPlanResult.ClarificationNeeded(parsed.question, parsed.reason)
                }
                is ClauseParse.Restricted -> {
                    if (clauses.size < 2) {
                        return LocalTaskPlanResult.NotApplicable
                    }
                    return LocalTaskPlanResult.CapabilityBlocked(
                        capability = parsed.capability,
                        reason = parsed.reason,
                        settingsAction = parsed.settingsAction
                    )
                }
                is ClauseParse.Forbidden -> {
                    return LocalTaskPlanResult.Rejected(
                        reason = parsed.reason,
                        errorCode = TaskPlanErrorCode.UNTRUSTED_OR_FORBIDDEN_ACTION
                    )
                }
                ClauseParse.Unrecognized -> {
                    return LocalTaskPlanResult.NotApplicable
                }
                is ClauseParse.Step -> {
                    val step = parsed.draft
                    when (step.action) {
                        LocalTaskAction.OPEN_APP -> {
                            val appName = step.args["name"].orEmpty()
                            lastAppQuery = appName
                            lastAppTarget = step.expectedPackage.takeIf { it.isNotBlank() }?.let {
                                ResolvedAppInfo(it, step.expectedAppLabel.ifBlank { appName })
                            }
                            lastDeviceAction = step.action
                        }
                        LocalTaskAction.FIND_UI_ELEMENT -> {
                            lastUiTarget = step.args["text"]
                                ?: step.args["view_id"]
                                ?: step.args["content_description"]
                                ?: step.args["hint"]
                                ?: lastUiTarget
                            lastDeviceAction = step.action
                        }
                        LocalTaskAction.SET_FLASHLIGHT -> {
                            lastFlashExpectedOn = step.args["action"] == "flashlight_on"
                            lastDeviceAction = step.action
                        }
                        else -> {
                            lastDeviceAction = step.action
                        }
                    }
                    drafts.add(step)
                }
            }
        }

        // Allow single-step explicit "open app X and verify" (splits into 2+ drafts) or multi-step plans (>= 2 drafts),
        // or standalone explicit wait/verify/close/switch commands; let single basic commands stay fast in LocalDeviceCommandEngine
        // unless they explicitly expand or need capability gating.
        if (drafts.size < 2 && clauses.size < 2 && !isCloseOrSwitchCommand(trimmed)) {
            return LocalTaskPlanResult.NotApplicable
        }

        if (drafts.size > MAX_LOCAL_TASK_STEPS) {
            return LocalTaskPlanResult.Rejected(
                reason = "Local automation task has ${drafts.size} steps, exceeding the maximum limit of $MAX_LOCAL_TASK_STEPS steps.",
                errorCode = TaskPlanErrorCode.TOO_MANY_STEPS
            )
        }

        val steps = drafts.mapIndexed { idx, d ->
            LocalTaskStep(
                index = idx + 1,
                action = d.action,
                description = d.description,
                args = d.args,
                expectedPackage = d.expectedPackage,
                expectedAppLabel = d.expectedAppLabel,
                requiredCapability = d.requiredCapability
            )
        }

        // Prevent duplicate consecutive steps and oscillation loops
        val signatureCounts = mutableMapOf<String, Int>()
        for (i in steps.indices) {
            val step = steps[i]
            val sig = step.signatureKey()
            if (i > 0 && steps[i - 1].signatureKey() == sig) {
                return LocalTaskPlanResult.Rejected(
                    reason = "Duplicate consecutive action prevented at step ${step.index}: ${step.description}.",
                    errorCode = TaskPlanErrorCode.DUPLICATE_ACTION
                )
            }
            val count = (signatureCounts[sig] ?: 0) + 1
            signatureCounts[sig] = count
            if (step.action != LocalTaskAction.SCROLL_UI && count > 2) {
                return LocalTaskPlanResult.Rejected(
                    reason = "Duplicate/looping action prevented at step ${step.index}: ${step.description}.",
                    errorCode = TaskPlanErrorCode.DUPLICATE_ACTION
                )
            }
            if (i >= 3) {
                val s0 = steps[i - 3].signatureKey()
                val s1 = steps[i - 2].signatureKey()
                val s2 = steps[i - 1].signatureKey()
                if (sig == s1 && s2 == s0) {
                    return LocalTaskPlanResult.Rejected(
                        reason = "Oscillating duplicate action loop prevented at step ${step.index}.",
                        errorCode = TaskPlanErrorCode.DUPLICATE_ACTION
                    )
                }
            }
        }

        // Dynamic capability discovery via DeviceCapabilityRegistry (never assume capabilities exist)
        val snapshot: DeviceCapabilitiesSnapshot? = capabilityRegistry?.refresh()
        if (snapshot != null) {
            for (step in steps) {
                val capId = step.requiredCapability ?: continue
                val status = snapshot.statusFor(capId)
                if (status != null && !status.isAvailable) {
                    return LocalTaskPlanResult.CapabilityBlocked(
                        capability = capId,
                        reason = "Cannot plan step ${step.index} (${step.description}): ${status.reason}",
                        settingsAction = status.settingsAction
                    )
                }
            }
        }

        var expectedFinalPkg = ""
        for (step in steps) {
            when (step.action) {
                LocalTaskAction.OPEN_APP -> expectedFinalPkg = step.expectedPackage
                LocalTaskAction.OPEN_SETTINGS -> expectedFinalPkg = ""
                LocalTaskAction.NAVIGATE -> {
                    val nav = step.args["action"].orEmpty()
                    if (nav == "home" || nav == "recents") expectedFinalPkg = ""
                }
                LocalTaskAction.WAIT_FOR_APP, LocalTaskAction.VERIFY_FOREGROUND_APP -> {
                    if (step.expectedPackage.isNotBlank()) expectedFinalPkg = step.expectedPackage
                }
                else -> Unit
            }
        }
        val expectedFinalEl = steps.lastOrNull()?.takeIf { it.action == LocalTaskAction.FIND_UI_ELEMENT }?.let { st ->
            st.args["text"].orEmpty().ifBlank { st.args["view_id"].orEmpty() }
        }.orEmpty()

        return LocalTaskPlanResult.Planned(
            LocalDeviceTaskPlan(
                id = id,
                objective = trimmed,
                steps = steps,
                createdAtMs = nowMs,
                expectedFinalPackage = expectedFinalPkg,
                expectedFinalElement = expectedFinalEl
            )
        )
    }

    private fun isCloseOrSwitchCommand(text: String): Boolean {
        val cleaned = text.trimEnd('?', '.', '!', '।').trim().lowercase(Locale.ROOT)
        return Regex("^(?:close|exit|quit) (?:the )?(?:current )?app$").matches(cleaned) ||
            Regex("^switch (?:to |app to )?.+$").matches(cleaned) ||
            Regex("^(?:app band karo|ऐप बंद करो|अ‍ॅप बंद करा|अॅप बंद करा)$").matches(text.trim())
    }

    private fun detectStandaloneAmbiguity(raw: String): LocalTaskPlanResult.ClarificationNeeded? {
        val cleaned = raw.trimEnd('?', '.', '!', '।').trim()
        val lower = cleaned.lowercase(Locale.ROOT)
        return when {
            Regex("^(?:open|launch|start|switch(?: to)?) (?:the |an )?(?:app|application|it|that|this)$").matches(lower) ||
                Regex("^(?:switch|change) app$").matches(lower) ||
                Regex("^(?:app|ऐप|अ‍ॅप|अॅप) (?:kholo|उघड|उघडा|खोलो|बदल|बदलो)$").matches(cleaned) ->
                LocalTaskPlanResult.ClarificationNeeded(
                    question = "Which app would you like me to open or switch to?",
                    reason = "Ambiguous target app."
                )

            Regex("^(?:click|tap|press|select)(?: on)? (?:the )?(?:button|tab|item|option|it|that|this)$").matches(lower) ||
                Regex("^(?:बटन|बटण) (?:दबाओ|दाबा|क्लिक करो|क्लिक करा)$").matches(cleaned) ->
                LocalTaskPlanResult.ClarificationNeeded(
                    question = "Which button or UI element should I click?",
                    reason = "Ambiguous UI click target."
                )

            Regex("^(?:find|locate)(?: the)? (?:element|button|field|item|it|that)$").matches(lower) ->
                LocalTaskPlanResult.ClarificationNeeded(
                    question = "Which element should I find on the screen?",
                    reason = "Ambiguous UI search target."
                )

            Regex("^(?:type|enter|write|input)(?: text| something)?(?: in(?:to)? (?:the )?(?:field|box|it))?$").matches(lower) ->
                LocalTaskPlanResult.ClarificationNeeded(
                    question = "What text should I type, and into which field?",
                    reason = "Missing text or target field."
                )

            Regex("^(?:scroll|स्क्रॉल करो|स्क्रोल करा)$").matches(lower) ||
                Regex("^(?:scroll|स्क्रॉल करो|स्क्रोल करा)$").matches(cleaned) ->
                LocalTaskPlanResult.ClarificationNeeded(
                    question = "Should I scroll up or scroll down?",
                    reason = "Ambiguous scroll direction."
                )

            Regex("^(?:open|change|adjust) (?:a |the )?(?:setting|settings for something)$").matches(lower) ->
                LocalTaskPlanResult.ClarificationNeeded(
                    question = "Which Settings screen would you like to open (for example Wi-Fi, Bluetooth, Display, Sound, Battery, or Accessibility)?",
                    reason = "Ambiguous settings target."
                )

            Regex("^(?:change|adjust|set) (?:the )?volume$").matches(lower) ||
                Regex("^(?:flashlight|torch|टॉर्च|फ्लैशलाइट|फ्लॅशलाइट)$").matches(cleaned) ->
                LocalTaskPlanResult.ClarificationNeeded(
                    question = "Would you like me to turn it up/on or down/off?",
                    reason = "Ambiguous device action state."
                )

            else -> null
        }
    }

    private fun splitMultiStepClauses(input: String): List<String> {
        val delimiterRegex = Regex(
            "(?:\\s*;\\s*|\\s*,\\s*(?:and\\s+)?then\\s+|\\s+and\\s+then\\s+|\\s+then\\s+|\\s*,\\s+and\\s+|\\s+and\\s+|\\s+aur\\s+phir\\s+|\\s+aur\\s+|\\s+phir\\s+|\\s+fir\\s+|\\s+और\\s+फिर\\s+|\\s+उसके\\s+बाद\\s+|\\s+और\\s+|\\s+फिर\\s+|\\s+आणि\\s+नंतर\\s+|\\s+त्यानंतर\\s+|\\s+आणि\\s+|\\s+नंतर\\s+|\\s*,\\s*)",
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
                val piece = current.toString().trim().trimEnd('.', '।', '!')
                if (piece.isNotBlank()) clauses.add(piece)
                current.setLength(0)
                i = match.range.last + 1
            } else {
                current.append(ch)
                i++
            }
        }
        val tail = current.toString().trim().trimEnd('.', '।', '!')
        if (tail.isNotBlank()) clauses.add(tail)
        return clauses
    }

    private fun parseClause(
        clause: String,
        lastAppTarget: ResolvedAppInfo?,
        lastAppQuery: String,
        lastUiTarget: String,
        lastDeviceAction: LocalTaskAction?,
        lastFlashExpectedOn: Boolean?
    ): ClauseParse {
        val cleaned = clause.trim().trimEnd('.', '?', '!', '।').trim()
        if (cleaned.isEmpty()) return ClauseParse.Unrecognized
        val lower = cleaned.lowercase(Locale.ROOT)

        // Block autonomous destructive/outbound/financial actions in local automation chains
        if (Regex("^(?:uninstall|delete file|delete|send message|text |call |dial |pay|purchase|buy)\\b.*", RegexOption.IGNORE_CASE)
                .matches(lower)
        ) {
            return ClauseParse.Forbidden(
                "Autonomous multi-step plans cannot execute destructive, messaging, calling, or payment actions silently."
            )
        }

        // Ambiguous target within a multi-step sequence
        detectStandaloneAmbiguity(cleaned)?.let { amb ->
            // Allow "click it" / "tap it" / "click on it" ONLY when preceding step just found a UI element
            val isClickPronoun = Regex("^(?:click|tap|press|select)(?: on)? (?:it|that|that element)$").matches(lower)
            if (isClickPronoun && lastUiTarget.isNotBlank()) {
                val args = if (looksLikeViewId(lastUiTarget)) {
                    mapOf("view_id" to lastUiTarget.removePrefix("id:").trim())
                } else {
                    mapOf("text" to lastUiTarget)
                }
                return ClauseParse.Step(
                    DraftLocalStep(
                        action = LocalTaskAction.CLICK_UI_ELEMENT,
                        description = "Click \"$lastUiTarget\"",
                        args = args
                    )
                )
            }
            return ClauseParse.Clarification(amb.question, amb.reason)
        }

        // Hindi / Marathi pronoun click referring to element found in previous step: "उस पर क्लिक करो", "त्यावर क्लिक कर"
        if (lastUiTarget.isNotBlank() &&
            (Regex("^(?:उस|उसे|इस|इसे) (?:पर )?(?:क्लिक|टैप) (?:करो|करें|दो)$").matches(cleaned) ||
                Regex("^(?:त्यावर|यावर) (?:क्लिक|टॅप) (?:कर|करा)$").matches(cleaned) ||
                Regex("^(?:us|use|uspe|us par) (?:click|tap) (?:karo|kar)$").matches(lower))
        ) {
            val args = if (looksLikeViewId(lastUiTarget)) {
                mapOf("view_id" to lastUiTarget.removePrefix("id:").trim())
            } else {
                mapOf("text" to lastUiTarget)
            }
            return ClauseParse.Step(
                DraftLocalStep(
                    action = LocalTaskAction.CLICK_UI_ELEMENT,
                    description = "Click \"$lastUiTarget\"",
                    args = args
                )
            )
        }

        // Restricted system toggles -> never fake toggle; stop & explain
        if (!lower.contains("settings") && !cleaned.contains("सेटिंग")) {
            if (Regex("^(?:turn |switch )?(?:on|off) (?:the )?wi-?fi$").matches(lower) ||
                Regex("^wi-?fi (?:on|off|chalu|band) (?:karo|kar|do)$").matches(lower) ||
                Regex("^(?:वाईफाई|वायफाय|वाई-फाई) (?:ऑन|ऑफ|चालू|बंद) (?:करो|कर|करें|करा)$").matches(cleaned)
            ) {
                return ClauseParse.Restricted(
                    DeviceActionId.WIFI_DIRECT_TOGGLE,
                    "Android restricts third-party apps from toggling Wi-Fi silently; please use Wi-Fi Settings.",
                    Settings.ACTION_WIFI_SETTINGS
                )
            }
            if (Regex("^(?:turn |switch )?(?:on|off) (?:the )?bluetooth$").matches(lower) ||
                Regex("^bluetooth (?:on|off|chalu|band) (?:karo|kar|do)$").matches(lower) ||
                Regex("^ब्लूटूथ (?:ऑन|ऑफ|चालू|बंद) (?:करो|कर|करें|करा)$").matches(cleaned)
            ) {
                return ClauseParse.Restricted(
                    DeviceActionId.BLUETOOTH_DIRECT_TOGGLE,
                    "Android requires user interaction in Bluetooth Settings to change Bluetooth state.",
                    Settings.ACTION_BLUETOOTH_SETTINGS
                )
            }
            if (Regex("^(?:turn |switch )?(?:on|off) (?:the )?(?:mobile )?data$").matches(lower) ||
                Regex("^(?:mobile )?data (?:on|off|chalu|band) (?:karo|kar|do)$").matches(lower) ||
                Regex("^(?:मोबाइल )?डेटा (?:ऑन|ऑफ|चालू|बंद) (?:करो|कर|करें|करा)$").matches(cleaned)
            ) {
                return ClauseParse.Restricted(
                    DeviceActionId.MOBILE_DATA_TOGGLE,
                    "Android restricts direct Mobile Data toggling; please use Network & Internet Settings.",
                    Settings.ACTION_WIRELESS_SETTINGS
                )
            }
        }

        // 1. Wait for app to load clause
        if (Regex("^wait(?: for (?:it|the app|app|screen) to load| to load| until loaded)?$").matches(lower) ||
            Regex("^(?:load hone do|intezar karo|wait karo|लोड होने (?:का इंतज़ार करो|दो)|लोड होऊ दे|वाट बघ|थांब)$").matches(lower) ||
            Regex("^(?:लोड होने का इंतज़ार करो|लोड होने दो|लोड होऊ दे|वाट बघ|थोडा वेळ थांब)$").matches(cleaned)
        ) {
            val expectedPkg = lastAppTarget?.packageName.orEmpty()
            val label = lastAppTarget?.label?.ifBlank { lastAppQuery } ?: lastAppQuery.ifBlank { "app" }
            return ClauseParse.Step(
                DraftLocalStep(
                    action = LocalTaskAction.WAIT_FOR_APP,
                    description = "Wait for $label to load",
                    args = buildMap {
                        if (expectedPkg.isNotBlank()) put("expected_package", expectedPkg)
                        if (label.isNotBlank()) put("app_name", label)
                    },
                    expectedPackage = expectedPkg,
                    expectedAppLabel = label
                )
            )
        }

        // 2. Verify previous action / app foreground / volume / flashlight clause
        if (Regex("^(?:verify|confirm|check)(?: (?:the )?(?:app is (?:in(?: the)? )?foreground|it is (?:in(?: the)? )?foreground|it (?:is )?opened|it loaded|foreground(?: app)?|actual volume|current volume|volume|actual state|state|flashlight(?: is (?:on|off)| state)?|torch(?: is (?:on|off)| state)?|it|that))?$").matches(lower) ||
            Regex("^(?:चेक करो|वेरिफाई करो|जांचो|तपासा|तपास कर|वॉल्यूम चेक करो|फ्लैशलाइट चेक करो)$").matches(cleaned) ||
            Regex("^(?:check karo|verify karo|check kar|verify kar)$").matches(lower)
        ) {
            return when {
                lower.contains("volume") || cleaned.contains("वॉल्यूम") || lastDeviceAction == LocalTaskAction.ADJUST_VOLUME ->
                    ClauseParse.Step(
                        DraftLocalStep(
                            action = LocalTaskAction.VERIFY_VOLUME,
                            description = "Verify current media volume"
                        )
                    )
                lower.contains("flash") || lower.contains("torch") || cleaned.contains("फ्लैशलाइट") || cleaned.contains("टॉर्च") || lastDeviceAction == LocalTaskAction.SET_FLASHLIGHT ->
                    ClauseParse.Step(
                        DraftLocalStep(
                            action = LocalTaskAction.VERIFY_FLASHLIGHT,
                            description = "Verify flashlight state",
                            args = mapOf("expected_on" to (lastFlashExpectedOn != false).toString())
                        )
                    )
                else -> {
                    val expectedPkg = lastAppTarget?.packageName.orEmpty()
                    val label = lastAppTarget?.label?.ifBlank { lastAppQuery } ?: lastAppQuery.ifBlank { "app" }
                    ClauseParse.Step(
                        DraftLocalStep(
                            action = LocalTaskAction.VERIFY_FOREGROUND_APP,
                            description = "Verify $label is in the foreground",
                            args = buildMap {
                                if (expectedPkg.isNotBlank()) put("expected_package", expectedPkg)
                                if (label.isNotBlank()) put("app_name", label)
                            },
                            expectedPackage = expectedPkg,
                            expectedAppLabel = label
                        )
                    )
                }
            }
        }

        // 3. Close app -> safe Home navigation
        if (Regex("^(?:close|exit|quit) (?:the )?(?:current )?app$").matches(lower) ||
            Regex("^(?:app band karo|ऐप बंद करो|अ‍ॅप बंद करा|अॅप बंद करा)$").matches(cleaned)
        ) {
            return ClauseParse.Step(
                DraftLocalStep(
                    action = LocalTaskAction.NAVIGATE,
                    description = "Navigate Home to leave current app",
                    args = mapOf("action" to "home"),
                    requiredCapability = DeviceActionId.NAVIGATION_HOME
                )
            )
        }

        // 4. Generic "open settings" -> system_settings
        if (Regex("^(?:open|show|go to) (?:the )?settings$").matches(lower) ||
            Regex("^settings (?:kholo|open karo|खोलो|उघड|उघडा)$").matches(cleaned) ||
            Regex("^सेटिंग्स खोलो$").matches(cleaned) ||
            Regex("^सेटिंग्ज उघडा$").matches(cleaned)
        ) {
            return ClauseParse.Step(
                DraftLocalStep(
                    action = LocalTaskAction.OPEN_SETTINGS,
                    description = "Open Android System Settings",
                    args = mapOf("action" to "system_settings")
                )
            )
        }

        // 5. Switch to <app>
        val switchMatch = Regex("^switch (?:app )?(?:to )?(.+)$", RegexOption.IGNORE_CASE).find(cleaned)
            ?: Regex("^(.+?) (?:par|pe) switch (?:karo|kar)$", RegexOption.IGNORE_CASE).find(cleaned)
            ?: Regex("^(.+?) पर स्विच (?:करो|करें)$").find(cleaned)
            ?: Regex("^(.+?) वर स्विच (?:कर|करा)$").find(cleaned)
        if (switchMatch != null) {
            val rawTarget = DirectCommandMatcher.normalizeAppTarget(switchMatch.groupValues[1])
            if (rawTarget.isBlank() || rawTarget.lowercase(Locale.ROOT) in setOf("app", "application", "it")) {
                return ClauseParse.Clarification("Which app should I switch to?", "Ambiguous switch target.")
            }
            val resolved = appResolver(rawTarget)
            return ClauseParse.Step(
                DraftLocalStep(
                    action = LocalTaskAction.OPEN_APP,
                    description = "Switch to ${resolved?.label ?: rawTarget}",
                    args = mapOf("name" to rawTarget),
                    expectedPackage = resolved?.packageName.orEmpty(),
                    expectedAppLabel = resolved?.label ?: rawTarget
                )
            )
        }

        // 6. Delegate single-step deterministic actions via DirectCommandMatcher (volume, flashlight, settings screens, open app, nav)
        val direct = DirectCommandMatcher.match(cleaned)
        if (direct is DirectActionMatch.ToolCall) {
            return mapDirectToolCallToDraft(direct)
        }

        // 7. Read visible screen (English, Hinglish, Hindi, Marathi)
        if (Regex("^(?:read|inspect|scan|check) (?:the )?(?:active |current |visible )?(?:screen|content|visible content)$").matches(lower) ||
            Regex("^screen (?:padho|read karo|batao|dikhao)$").matches(lower) ||
            Regex("^स्क्रीन (?:पढ़ो|पढ़ें|बताओ|दिखाओ|वाच|वाचा|सांग|दाखवा)$").matches(cleaned)
        ) {
            return ClauseParse.Step(
                DraftLocalStep(
                    action = LocalTaskAction.READ_SCREEN,
                    description = "Read visible screen"
                )
            )
        }

        // 8. Scroll UP / DOWN (English, Hinglish, Hindi, Marathi)
        val scrollStep = parseScrollClause(cleaned, lower)
        if (scrollStep != null) return ClauseParse.Step(scrollStep)

        // 9. Type text into permitted field
        val typeStep = parseTypeTextClause(cleaned)
        if (typeStep != null) return ClauseParse.Step(typeStep)

        // 10. Click UI element
        val clickStep = parseClickClause(cleaned)
        if (clickStep != null) return ClauseParse.Step(clickStep)

        // 11. Find UI element
        val findStep = parseFindClause(cleaned)
        if (findStep != null) return ClauseParse.Step(findStep)

        return ClauseParse.Unrecognized
    }

    private fun mapDirectToolCallToDraft(direct: DirectActionMatch.ToolCall): ClauseParse {
        return when (direct.toolName) {
            "open_application" -> {
                val appName = direct.args["name"].orEmpty()
                val resolved = appResolver(appName)
                ClauseParse.Step(
                    DraftLocalStep(
                        action = LocalTaskAction.OPEN_APP,
                        description = "Open ${resolved?.label ?: appName}",
                        args = direct.args,
                        expectedPackage = resolved?.packageName.orEmpty(),
                        expectedAppLabel = resolved?.label ?: appName
                    )
                )
            }
            "device_control" -> {
                val action = direct.args["action"].orEmpty()
                when (action) {
                    "volume_up", "volume_down", "mute", "unmute" -> ClauseParse.Step(
                        DraftLocalStep(
                            action = LocalTaskAction.ADJUST_VOLUME,
                            description = "Adjust media volume ($action)",
                            args = direct.args
                        )
                    )
                    "flashlight_on", "flashlight_off" -> ClauseParse.Step(
                        DraftLocalStep(
                            action = LocalTaskAction.SET_FLASHLIGHT,
                            description = if (action == "flashlight_on") "Turn flashlight on" else "Turn flashlight off",
                            args = direct.args
                        )
                    )
                    "dnd_on", "dnd_off" -> ClauseParse.Step(
                        DraftLocalStep(
                            action = LocalTaskAction.SET_DND,
                            description = if (action == "dnd_on") "Enable Do Not Disturb" else "Disable Do Not Disturb",
                            args = direct.args
                        )
                    )
                    "media_play_pause", "media_next", "media_prev" -> ClauseParse.Step(
                        DraftLocalStep(
                            action = LocalTaskAction.MEDIA_CONTROL,
                            description = "Media control ($action)",
                            args = direct.args
                        )
                    )
                    else -> ClauseParse.Step(
                        DraftLocalStep(
                            action = LocalTaskAction.OPEN_SETTINGS,
                            description = "Open ${action.replace('_', ' ')}",
                            args = direct.args
                        )
                    )
                }
            }
            "accessibility_navigate" -> {
                val nav = direct.args["action"].orEmpty()
                val cap = when (nav) {
                    "back" -> DeviceActionId.NAVIGATION_BACK
                    "home" -> DeviceActionId.NAVIGATION_HOME
                    "recents" -> DeviceActionId.NAVIGATION_RECENTS
                    "notifications" -> DeviceActionId.NAVIGATION_NOTIFICATIONS
                    "quick_settings" -> DeviceActionId.NAVIGATION_QUICK_SETTINGS
                    else -> null
                }
                ClauseParse.Step(
                    DraftLocalStep(
                        action = LocalTaskAction.NAVIGATE,
                        description = "Navigate: $nav",
                        args = direct.args,
                        requiredCapability = cap
                    )
                )
            }
            else -> ClauseParse.Unrecognized
        }
    }

    private fun parseScrollClause(cleaned: String, lower: String): DraftLocalStep? {
        val eng = Regex("^scroll (up|down)(?: (?:on|in) (.+))?$").find(lower)
        val dir = when {
            eng != null -> eng.groupValues[1].uppercase(Locale.ROOT)
            Regex("^(?:upar|up) scroll (?:karo|kar)$").matches(lower) ||
                Regex("^scroll (?:up|upar) (?:karo|kar)$").matches(lower) ||
                Regex("^(?:ऊपर|स्क्रॉल अप) (?:स्क्रॉल )?(?:करो|करें)$").matches(cleaned) ||
                Regex("^वर स्क्रोल (?:कर|करा)$").matches(cleaned) -> "UP"
            Regex("^(?:neeche|niche|down) scroll (?:karo|kar)$").matches(lower) ||
                Regex("^scroll (?:down|neeche|niche) (?:karo|kar)$").matches(lower) ||
                Regex("^(?:नीचे|स्क्रॉल डाउन) (?:स्क्रॉल )?(?:करो|करें)$").matches(cleaned) ||
                Regex("^खाली स्क्रोल (?:कर|करा)$").matches(cleaned) -> "DOWN"
            else -> return null
        }
        val validDir = ScrollDirection.parseOrNull(dir)?.name ?: return null
        val target = eng?.groupValues?.getOrNull(2)?.trim()?.trim('"', '\'').orEmpty()
        val args = mutableMapOf("direction" to validDir)
        if (target.isNotBlank()) {
            if (looksLikeViewId(target)) args["view_id"] = target.removePrefix("id:").trim()
            else args["content_description"] = target
        }
        return DraftLocalStep(
            action = LocalTaskAction.SCROLL_UI,
            description = "Scroll $validDir",
            args = args
        )
    }

    private fun parseTypeTextClause(cleaned: String): DraftLocalStep? {
        val engQuoted = Regex(
            "^(?:type|enter|write|input) [\"']([^\"']+)[\"'] (?:in|into|on) (?:the )?(.+?)(?: field| box)?$",
            RegexOption.IGNORE_CASE
        ).find(cleaned)
        val engUnquoted = if (engQuoted == null) {
            Regex(
                "^(?:type|enter|write|input) (.+?) (?:in|into) (?:the )?(.+?)(?: field| box)?$",
                RegexOption.IGNORE_CASE
            ).find(cleaned)
        } else null

        val m = engQuoted ?: engUnquoted
        val (textVal, rawField) = when {
            m != null -> m.groupValues[1] to m.groupValues[2]
            else -> {
                val hinglish = Regex("^(.+?) (?:mein|me) [\"']([^\"']+)[\"'] (?:type|likho|enter) (?:karo|kar)?$", RegexOption.IGNORE_CASE).find(cleaned)
                val hindi = Regex("^(.+?) में [\"']([^\"']+)[\"'] (?:टाइप|लिखो|एंटर)(?: करो| करें)?$").find(cleaned)
                val marathi = Regex("^(.+?) मध्ये [\"']([^\"']+)[\"'] (?:टाइप|टाईप|लिही|लिहा)(?: कर| करा)?$").find(cleaned)
                val matched = hinglish ?: hindi ?: marathi ?: return null
                matched.groupValues[2] to matched.groupValues[1]
            }
        }
        val fieldTarget = cleanSelector(rawField)
        if (textVal.isBlank() || fieldTarget.isBlank()) return null
        val args = mutableMapOf("text" to textVal)
        if (looksLikeViewId(fieldTarget)) args["view_id"] = fieldTarget.removePrefix("id:").trim()
        else args["hint"] = fieldTarget
        return DraftLocalStep(
            action = LocalTaskAction.TYPE_TEXT,
            description = "Enter text (${textVal.length} chars) into \"$fieldTarget\"",
            args = args
        )
    }

    private fun parseClickClause(cleaned: String): DraftLocalStep? {
        val rawTarget = Regex("^(?:click|tap|press|select) (?:on )?(?:the )?(.+)$", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(.+?) (?:par|pe) (?:click|tap) (?:karo|kar)$", RegexOption.IGNORE_CASE)
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(?:click|tap) (?:karo|kar) (.+)$", RegexOption.IGNORE_CASE)
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(.+?) (?:पर|पे) (?:क्लिक|टैप) (?:करो|कर|करें|दो)$")
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(.+?) वर (?:क्लिक|टॅप) (?:कर|करा)$")
                .find(cleaned)?.groupValues?.get(1)
            ?: return null

        val target = cleanSelector(rawTarget)
        if (target.isBlank() || target.lowercase(Locale.ROOT) in setOf("back", "home", "recents")) return null
        val args = if (looksLikeViewId(target)) mapOf("view_id" to target.removePrefix("id:").trim())
        else mapOf("text" to target)
        return DraftLocalStep(
            action = LocalTaskAction.CLICK_UI_ELEMENT,
            description = "Click \"$target\"",
            args = args
        )
    }

    private fun parseFindClause(cleaned: String): DraftLocalStep? {
        val rawTarget = Regex("^(?:find|locate) (?:a )?(?:visible )?(?:ui )?(?:element |button |field )?(?:on screen )?(?:the )?(.+)$", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(?:screen (?:pe|par) )?(.+?) (?:dhundo|find karo|khojo)$", RegexOption.IGNORE_CASE)
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(?:स्क्रीन पर )?(.+?) (?:ढूंढो|खोजो)$")
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(?:स्क्रीनवर )?(.+?) (?:शोध|शोधा)$")
                .find(cleaned)?.groupValues?.get(1)
            ?: return null

        val target = cleanSelector(rawTarget)
        if (target.isBlank()) return null
        val lower = target.lowercase(Locale.ROOT)
        if (lower.contains("near me") || lower.contains("nearby") || lower.contains("restaurants")) return null
        val args = if (looksLikeViewId(target)) mapOf("view_id" to target.removePrefix("id:").trim())
        else mapOf("text" to target)
        return DraftLocalStep(
            action = LocalTaskAction.FIND_UI_ELEMENT,
            description = "Find \"$target\"",
            args = args
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
