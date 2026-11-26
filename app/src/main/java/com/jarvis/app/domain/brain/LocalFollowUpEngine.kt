package com.jarvis.app.domain.brain

import com.jarvis.app.platform.DeviceActionId
import com.jarvis.app.platform.DeviceCapabilityRegistry
import com.jarvis.app.platform.ScreenInteractionStatus
import com.jarvis.app.platform.ScreenReadErrorCode
import com.jarvis.app.platform.ScreenReadResult
import com.jarvis.app.platform.ScreenReaderEngine
import com.jarvis.app.platform.ScreenSnapshot
import com.jarvis.app.platform.SensitiveUiActionPolicy
import com.jarvis.app.platform.UiElementQuery
import com.jarvis.app.platform.UiNodeSnapshot
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

sealed interface LocalFollowUpOutcome {
    data object NotApplicable : LocalFollowUpOutcome
    data class Clarification(val question: String) : LocalFollowUpOutcome
    data class Executed(
        val ok: Boolean,
        val toolName: String,
        val summary: String,
        val awaitingConfirmation: Boolean = false
    ) : LocalFollowUpOutcome
}

private enum class ContextualFollowUpKind {
    OPEN_REFERENCED,
    REOPEN_PREVIOUS_SCREEN,
    SCROLL_FOLLOW_UP,
    CLICK_REFERENCED,
    SEARCH_IN_REFERENCED
}

/**
 * Context-aware Phase 6C follow-up resolver.
 * Resolves Hindi, Marathi, English, and Hinglish references locally using
 * [LocalConversationContextManager], always refreshing [DeviceCapabilityRegistry]
 * and live accessibility hierarchies before executing any device or UI action.
 */
class LocalFollowUpEngine(
    private val toolExecutor: ToolExecutor,
    private val capabilityRegistry: DeviceCapabilityRegistry? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val contextManager: LocalConversationContextManager
        get() = toolExecutor.conversationContextManager

    suspend fun tryHandle(
        rawInput: String,
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): LocalFollowUpOutcome {
        val trimmed = rawInput.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return LocalFollowUpOutcome.NotApplicable
        if (containsUntrustedMarker(trimmed)) {
            return LocalFollowUpOutcome.Executed(
                ok = false,
                toolName = "context_followup",
                summary = "Rejected follow-up input containing untrusted screen-content delimiters."
            )
        }

        contextManager.pruneExpired()
        val cleaned = trimmed.trimEnd('?', '.', '!', '।').trim()
        if (cleaned.isEmpty()) return LocalFollowUpOutcome.NotApplicable

        // Never intercept multi-step task chains unless resolving an explicit pending clarification
        val pendingClar = contextManager.snapshot().pendingClarification
        if (pendingClar != null) {
            resolvePendingClarification(cleaned, pendingClar, onSideEffect)?.let { return it }
        }

        if (DirectCommandMatcher.looksMultiClause(cleaned)) {
            return LocalFollowUpOutcome.NotApplicable
        }

        val lower = cleaned.lowercase(Locale.ROOT)
        val matchedIntent = classifyContextualFollowUp(cleaned, lower) ?: return LocalFollowUpOutcome.NotApplicable

        return when (matchedIntent.first) {
            ContextualFollowUpKind.OPEN_REFERENCED -> handleOpenReferenced(onSideEffect)
            ContextualFollowUpKind.REOPEN_PREVIOUS_SCREEN -> handleReopenPreviousScreen(onSideEffect)
            ContextualFollowUpKind.SCROLL_FOLLOW_UP -> handleScrollFollowUp(onSideEffect)
            ContextualFollowUpKind.CLICK_REFERENCED -> handleClickReferenced(onSideEffect)
            ContextualFollowUpKind.SEARCH_IN_REFERENCED -> handleSearchInReferenced(matchedIntent.second, onSideEffect)
        }
    }

    private fun classifyContextualFollowUp(
        cleaned: String,
        lower: String
    ): Pair<ContextualFollowUpKind, String>? {
        // 1. Reopen previous screen / return to prior app
        if (Regex("^(?:wapas|vaapas) (?:wahi|vahi|us|usi) (?:screen|app)(?: pe| par| mein| me)? (?:kholo|open karo|jao|chalo)$").matches(lower) ||
            Regex("^(?:reopen|open) (?:that|the previous|previous|same) (?:screen|app)$").matches(lower) ||
            Regex("^(?:go back|return) to (?:that|the previous|previous) (?:app|screen)$").matches(lower) ||
            Regex("^(?:वापस )?(?:वही|उसी) (?:स्क्रीन|ऐप)(?: पर| में)? (?:खोलो|जाओ|चलो)$").matches(cleaned) ||
            Regex("^(?:पुन्हा |परत )?(?:तीच|ती|त्याच) (?:स्क्रीन|अ‍ॅप|अॅप)(?:वर|मध्ये)? (?:उघड|उघडा|जा|जावा)$").matches(cleaned)
        ) {
            return ContextualFollowUpKind.REOPEN_PREVIOUS_SCREEN to ""
        }

        // 2. Open referenced target ("Usko kholo")
        if (Regex("^(?:usko|use|isko|ise) (?:kholo|khol do|open karo|launch karo|chalao)$").matches(lower) ||
            Regex("^(?:open|launch|start) (?:it|that|that app|the same app)$").matches(lower) ||
            Regex("^(?:उसे|उसको|इसे|इसको|उसी को) (?:खोलो|खोल दो|ओपन करो|चालू करो)$").matches(cleaned) ||
            Regex("^(?:तो अ‍ॅप |तो अॅप |त्याला |ते )(?:उघड|उघडा|सुरू करा)$").matches(cleaned)
        ) {
            return ContextualFollowUpKind.OPEN_REFERENCED to ""
        }

        // 3. Click referenced element ("Wahi click karo")
        if (Regex("^(?:wahi|vahi|wohi|usko|uspe|us par|is par|ispe) (?:click|tap|press|dabao)(?: karo| kar| do)?$").matches(lower) ||
            Regex("^(?:click|tap|press) (?:on )?(?:it|that|that button|that element|the same|same button|same)$").matches(lower) ||
            Regex("^(?:वही|उसी|उस|इस|उसी बटन)(?: पर| पे)? (?:क्लिक|टैप|प्रेस) (?:करो|कर|करें|दो)$").matches(cleaned) ||
            Regex("^(?:त्याच|त्या|तेच|त्या बटणा)(?:वर)? (?:क्लिक|टॅप) (?:कर|करा)$").matches(cleaned)
        ) {
            return ContextualFollowUpKind.CLICK_REFERENCED to ""
        }

        // 4. Scroll follow-up ("Ab scroll karo")
        if (Regex("^(?:ab|aur|thoda aur|phir|dobara|continue|keep) scroll(?:ing)?(?: karo| kar| again)?$").matches(lower) ||
            Regex("^scroll (?:again|karo|kar|more)$").matches(lower) ||
            Regex("^(?:अब|और|थोड़ा और|फिर से) स्क्रॉल (?:करो|करें|कर)$").matches(cleaned) ||
            Regex("^(?:आता|आणखी|पुन्हा|पुढे) स्क्रोल (?:कर|करा)$").matches(cleaned)
        ) {
            return ContextualFollowUpKind.SCROLL_FOLLOW_UP to ""
        }

        // 5. Search in referenced app ("Us app mein search karo" or "Us app mein 'foo' search karo")
        extractSearchInReferencedQuery(cleaned, lower)?.let { query ->
            return ContextualFollowUpKind.SEARCH_IN_REFERENCED to query
        }

        return null
    }

    private fun extractSearchInReferencedQuery(cleaned: String, lower: String): String? {
        // Without explicit query
        if (Regex("^(?:us|is|usi|same|that) app (?:mein|me|pe|par) search (?:karo|kar)$").matches(lower) ||
            Regex("^search (?:in|inside) (?:that app|this app|same app|it)$").matches(lower) ||
            Regex("^(?:उस|इस|उसी) (?:ऐप|एप्प) में सर्च (?:करो|करें)$").matches(cleaned) ||
            Regex("^(?:त्या|या|त्याच) (?:अ‍ॅप|अॅप)मध्ये (?:सर्च|शोध) (?:कर|करा)$").matches(cleaned)
        ) {
            return ""
        }

        // With explicit query
        val patterns = listOf(
            Regex("^(?:us|is|usi|that|this|same) app (?:mein|me) [\"']([^\"']+)[\"'] search (?:karo|kar)$", RegexOption.IGNORE_CASE),
            Regex("^(?:us|is|usi|that|this|same) app (?:mein|me) (.+?) search (?:karo|kar)$", RegexOption.IGNORE_CASE),
            Regex("^search (?:for )?[\"']([^\"']+)[\"'] (?:in|inside) (?:that app|this app|the app|it)$", RegexOption.IGNORE_CASE),
            Regex("^search (?:for )?(.+?) (?:in|inside) (?:that app|this app|the app|it)$", RegexOption.IGNORE_CASE),
            Regex("^(?:उस|इस|उसी) (?:ऐप|एप्प) में [\"']([^\"']+)[\"'] सर्च (?:करो|करें)$"),
            Regex("^(?:उस|इस|उसी) (?:ऐप|एप्प) में (.+?) सर्च (?:करो|करें)$"),
            Regex("^(?:त्या|या|त्याच) (?:अ‍ॅप|अॅप)मध्ये [\"']([^\"']+)[\"'] (?:सर्च|शोध) (?:कर|करा)$"),
            Regex("^(?:त्या|या|त्याच) (?:अ‍ॅप|अॅप)मध्ये (.+?) (?:सर्च|शोध) (?:कर|करा)$")
        )
        for (p in patterns) {
            val m = p.find(cleaned)
            if (m != null) {
                return m.groupValues[1].trim().trim('"', '\'')
            }
        }
        return null
    }

    private suspend fun resolvePendingClarification(
        cleaned: String,
        clar: PendingLocalClarification,
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): LocalFollowUpOutcome? {
        val lower = cleaned.lowercase(Locale.ROOT)
        if (lower in setOf("cancel", "never mind", "nevermind", "rehne do", "chhodo", "नको", "रहने दो")) {
            contextManager.clearPendingClarification()
            return LocalFollowUpOutcome.Executed(
                ok = true,
                toolName = "context_clarification",
                summary = "Cancelled pending clarification."
            )
        }

        // If user issued another recognizable command instead of answering the clarification,
        // clear the pending clarification and let normal routing handle it.
        if (classifyContextualFollowUp(cleaned, lower) != null ||
            DirectCommandMatcher.match(cleaned) != null ||
            DirectCommandMatcher.looksMultiClause(cleaned)
        ) {
            contextManager.clearPendingClarification()
            return null
        }

        return when (clar.kind) {
            ClarificationKind.OPEN_APP_TARGET,
            ClarificationKind.REOPEN_PREVIOUS_SCREEN -> {
                val chosenApp = matchCandidateApp(cleaned, lower, clar.candidateApps) ?: return null
                contextManager.clearPendingClarification()
                executeOpenAppOrSettings(chosenApp, onSideEffect)
            }
            ClarificationKind.CLICK_ELEMENT_TARGET -> {
                val chosen = matchCandidateElement(cleaned, lower, clar.candidateElements) ?: return null
                contextManager.clearPendingClarification()
                executeClickWithFreshRevalidation(chosen, onSideEffect)
            }
            ClarificationKind.SCROLL_DIRECTION -> {
                val dir = when {
                    lower in setOf("up", "upar", "ऊपर", "वर", "scroll up") -> "UP"
                    lower in setOf("down", "neeche", "niche", "नीचे", "खाली", "scroll down") -> "DOWN"
                    else -> return null
                }
                contextManager.clearPendingClarification()
                executeScrollWithFreshCheck(dir, onSideEffect)
            }
            ClarificationKind.SEARCH_QUERY -> {
                val targetApp = clar.targetApp ?: return null
                if (cleaned.length > 80) return null
                contextManager.clearPendingClarification()
                executeSearchInApp(targetApp, cleaned.trim('"', '\''), onSideEffect)
            }
            ClarificationKind.SEARCH_FIELD_TARGET -> {
                val targetApp = clar.targetApp ?: return null
                val chosenField = matchCandidateElement(cleaned, lower, clar.candidateElements) ?: return null
                contextManager.clearPendingClarification()
                executeTypeIntoFieldWithVerification(targetApp, chosenField, clar.pendingQueryText, onSideEffect)
            }
        }
    }

    private fun matchCandidateApp(
        cleaned: String,
        lower: String,
        apps: List<AppReference>
    ): AppReference? {
        if (apps.isEmpty()) return null
        val index = cleaned.toIntOrNull()?.minus(1)
        if (index != null && index in apps.indices) return apps[index]
        val normalized = DirectCommandMatcher.normalizeAppTarget(cleaned).lowercase(Locale.ROOT)
        val exact = apps.filter {
            it.appLabel.equals(cleaned, ignoreCase = true) ||
                it.appLabel.lowercase(Locale.ROOT) == normalized ||
                it.settingsLabel.equals(cleaned, ignoreCase = true)
        }
        if (exact.size == 1) return exact.first()
        val partial = apps.filter {
            it.appLabel.lowercase(Locale.ROOT).contains(lower) ||
                it.settingsLabel.lowercase(Locale.ROOT).contains(lower)
        }
        return if (partial.size == 1) partial.first() else null
    }

    private fun matchCandidateElement(
        cleaned: String,
        lower: String,
        elements: List<SelectedUiElementRef>
    ): SelectedUiElementRef? {
        if (elements.isEmpty()) return null
        val index = cleaned.toIntOrNull()?.minus(1)
        if (index != null && index in elements.indices) return elements[index]
        val exact = elements.filter {
            it.label.equals(cleaned, ignoreCase = true) ||
                it.text.equals(cleaned, ignoreCase = true) ||
                it.hint.equals(cleaned, ignoreCase = true)
        }
        if (exact.size == 1) return exact.first()
        val partial = elements.filter {
            it.label.lowercase(Locale.ROOT).contains(lower) ||
                it.hint.lowercase(Locale.ROOT).contains(lower)
        }
        return if (partial.size == 1) partial.first() else null
    }

    private suspend fun handleOpenReferenced(
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): LocalFollowUpOutcome {
        val snap = contextManager.snapshot()
        // If multiple candidate apps exist without a single unique target, ask clarification
        if (snap.lastReferencedApp == null && snap.candidateApps.size > 1) {
            return askAppClarification(
                ClarificationKind.OPEN_APP_TARGET,
                snap.candidateApps,
                "Multiple apps are in context: ${snap.candidateApps.joinToString(", ") { it.displayName() }}. Which one should I open?"
            )
        }

        val targetApp = snap.lastReferencedApp ?: snap.previousForegroundApp
        if (targetApp != null) {
            return executeOpenAppOrSettings(targetApp, onSideEffect)
        }

        // If no app reference, check whether a single unique non-ambiguous live UI element was selected
        val selected = snap.lastSelectedElement
        if (selected != null) {
            return executeClickWithFreshRevalidation(selected, onSideEffect)
        }

        return LocalFollowUpOutcome.Clarification("Which app or item would you like me to open?")
    }

    private suspend fun handleReopenPreviousScreen(
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): LocalFollowUpOutcome {
        val snap = contextManager.snapshot()
        val curPkg = snap.currentForegroundApp?.packageName.orEmpty()
        val prev = snap.previousForegroundApp
        if (prev != null && !prev.packageName.equals(curPkg, ignoreCase = true)) {
            return executeOpenAppOrSettings(prev, onSideEffect)
        }
        val fallbackCandidates = snap.candidateApps.filter {
            !it.packageName.equals(curPkg, ignoreCase = true) || it.isSettingsTarget
        }
        if (fallbackCandidates.size == 1) {
            return executeOpenAppOrSettings(fallbackCandidates.first(), onSideEffect)
        }
        if (fallbackCandidates.size > 1) {
            return askAppClarification(
                ClarificationKind.REOPEN_PREVIOUS_SCREEN,
                fallbackCandidates,
                "Which previous screen should I reopen: ${fallbackCandidates.joinToString(", ") { it.displayName() }}?"
            )
        }
        val lastNonJarvis = snap.lastReferencedApp?.takeIf {
            !it.packageName.equals(ScreenReaderEngine.JARVIS_APP_PACKAGE, ignoreCase = true)
        }
        if (lastNonJarvis != null) {
            return executeOpenAppOrSettings(lastNonJarvis, onSideEffect)
        }
        return LocalFollowUpOutcome.Clarification(
            "There is no recent previous screen in context. Which app or screen would you like me to open?"
        )
    }

    private suspend fun executeOpenAppOrSettings(
        target: AppReference,
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): LocalFollowUpOutcome {
        val caps = capabilityRegistry?.refresh()
        if (target.isSettingsTarget) {
            if (caps != null && !caps.isActionAvailable(DeviceActionId.ANDROID_SETTINGS)) {
                val status = caps.statusFor(DeviceActionId.ANDROID_SETTINGS)
                return LocalFollowUpOutcome.Executed(
                    ok = false,
                    toolName = "device_control",
                    summary = status?.reason ?: "Android Settings intent action is unavailable."
                )
            }
            val res = toolExecutor.execute("device_control", mapOf("action" to JsonPrimitive(target.settingsAction)), onSideEffect)
            return LocalFollowUpOutcome.Executed(res.ok, res.toolName, res.summary)
        }

        if (caps != null && !caps.isActionAvailable(DeviceActionId.OPEN_APP)) {
            val status = caps.statusFor(DeviceActionId.OPEN_APP)
            return LocalFollowUpOutcome.Executed(
                ok = false,
                toolName = "open_application",
                summary = status?.reason ?: "Cannot open applications on this device."
            )
        }
        val appArg = target.appLabel.ifBlank { target.packageName }
        val res = toolExecutor.execute("open_application", mapOf("name" to JsonPrimitive(appArg)), onSideEffect)
        return LocalFollowUpOutcome.Executed(res.ok, res.toolName, res.summary)
    }

    private suspend fun handleScrollFollowUp(
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): LocalFollowUpOutcome {
        val caps = capabilityRegistry?.refresh()
        if (caps != null && !caps.isActionAvailable(DeviceActionId.UI_SCROLL)) {
            val reason = caps.statusFor(DeviceActionId.UI_SCROLL)?.reason
                ?: "Accessibility Phone Control is not connected."
            contextManager.onAccessibilityDisconnected()
            return LocalFollowUpOutcome.Executed(false, "scroll_ui", reason)
        }

        val priorContext = contextManager.snapshot()
        val expectedPkg = priorContext.lastScreenRef?.packageName
            ?: priorContext.currentForegroundApp?.takeIf {
                !it.packageName.equals(ScreenReaderEngine.JARVIS_APP_PACKAGE, ignoreCase = true) && !it.isSettingsTarget
            }?.packageName.orEmpty()

        val liveSnap = when (val read = toolExecutor.readScreenRaw()) {
            is ScreenReadResult.Error -> {
                if (read.code == ScreenReadErrorCode.SERVICE_NOT_CONNECTED ||
                    read.code == ScreenReadErrorCode.SERVICE_ENABLED_BUT_DISCONNECTED
                ) {
                    contextManager.onAccessibilityDisconnected()
                }
                return LocalFollowUpOutcome.Executed(false, "scroll_ui", read.message)
            }
            is ScreenReadResult.Success -> read.snapshot
        }

        if (expectedPkg.isNotBlank() && !liveSnap.packageName.equals(expectedPkg, ignoreCase = true)) {
            val now = nowMs()
            contextManager.setPendingClarification(
                PendingLocalClarification(
                    kind = ClarificationKind.SCROLL_DIRECTION,
                    question = "Foreground app changed to '${liveSnap.packageName}'. Should I scroll up or scroll down here?",
                    createdAtMs = now,
                    expiresAtMs = now + LocalConversationContextManager.DEFAULT_CLARIFICATION_TTL_MS
                )
            )
            return LocalFollowUpOutcome.Clarification(
                "Foreground app changed to '${liveSnap.packageName}'. Should I scroll up or scroll down here?"
            )
        }

        val scrollableNodes = liveSnap.nodes.filter { it.isScrollable }
        if (scrollableNodes.isEmpty()) {
            return LocalFollowUpOutcome.Executed(
                ok = false,
                toolName = "scroll_ui",
                summary = "No scrollable container is visible on the current screen (${liveSnap.packageName})."
            )
        }

        val direction = priorContext.lastScrollDirection
        if (direction.isNullOrBlank()) {
            val now = nowMs()
            contextManager.setPendingClarification(
                PendingLocalClarification(
                    kind = ClarificationKind.SCROLL_DIRECTION,
                    question = "Should I scroll up or scroll down?",
                    createdAtMs = now,
                    expiresAtMs = now + LocalConversationContextManager.DEFAULT_CLARIFICATION_TTL_MS
                )
            )
            return LocalFollowUpOutcome.Clarification("Should I scroll up or scroll down?")
        }

        return executeScrollWithFreshCheck(direction, onSideEffect, liveSnap)
    }

    private suspend fun executeScrollWithFreshCheck(
        direction: String,
        onSideEffect: suspend (ToolSideEffects) -> Unit,
        preSnapshot: ScreenSnapshot? = null
    ): LocalFollowUpOutcome {
        val caps = capabilityRegistry?.refresh()
        if (caps != null && !caps.isActionAvailable(DeviceActionId.UI_SCROLL)) {
            contextManager.onAccessibilityDisconnected()
            return LocalFollowUpOutcome.Executed(
                ok = false,
                toolName = "scroll_ui",
                summary = caps.statusFor(DeviceActionId.UI_SCROLL)?.reason ?: "Accessibility Phone Control is disconnected."
            )
        }
        val baseline = preSnapshot ?: when (val read = toolExecutor.readScreenRaw()) {
            is ScreenReadResult.Success -> read.snapshot
            is ScreenReadResult.Error -> return LocalFollowUpOutcome.Executed(false, "scroll_ui", read.message)
        }
        val res = toolExecutor.execute(
            "scroll_ui",
            buildMap {
                put("direction", JsonPrimitive(direction))
                if (baseline.packageName.isNotBlank()) {
                    put("expected_package", JsonPrimitive(baseline.packageName))
                }
            },
            onSideEffect
        )
        if (!res.ok) {
            return LocalFollowUpOutcome.Executed(false, "scroll_ui", res.summary)
        }
        val postRead = toolExecutor.readScreenRaw()
        val verified = postRead is ScreenReadResult.Success && hasScrollContentChanged(baseline, postRead.snapshot)
        return if (verified) {
            LocalFollowUpOutcome.Executed(true, "scroll_ui", "${res.summary} (verified screen scrolled $direction).")
        } else {
            LocalFollowUpOutcome.Executed(
                ok = false,
                toolName = "scroll_ui",
                summary = "Scroll verification failed: screen content did not change after scrolling $direction."
            )
        }
    }

    private suspend fun handleClickReferenced(
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): LocalFollowUpOutcome {
        val caps = capabilityRegistry?.refresh()
        if (caps != null && (!caps.isActionAvailable(DeviceActionId.SCREEN_READ) || !caps.isActionAvailable(DeviceActionId.UI_CLICK))) {
            contextManager.onAccessibilityDisconnected()
            val reason = caps.statusFor(DeviceActionId.UI_CLICK)?.reason
                ?: "Accessibility Phone Control is not connected."
            return LocalFollowUpOutcome.Executed(false, "click_ui_element", reason)
        }

        // Snapshot prior context BEFORE calling readScreenRaw(), because readScreenRaw() will detect any
        // unexpected foreground package change and clear stale element references!
        val prior = contextManager.snapshot()
        val priorSelected = prior.lastSelectedElement
        val priorAmbiguous = prior.ambiguousElementCandidates
        val priorScreenCandidates = prior.lastScreenRef?.safeActionableCandidates.orEmpty()

        val liveSnap = when (val read = toolExecutor.readScreenRaw()) {
            is ScreenReadResult.Error -> {
                if (read.code == ScreenReadErrorCode.SERVICE_NOT_CONNECTED ||
                    read.code == ScreenReadErrorCode.SERVICE_ENABLED_BUT_DISCONNECTED
                ) {
                    contextManager.onAccessibilityDisconnected()
                }
                return LocalFollowUpOutcome.Executed(false, "click_ui_element", read.message)
            }
            is ScreenReadResult.Success -> read.snapshot
        }

        // Reject stale element if foreground package changed since selection
        if (priorSelected != null &&
            priorSelected.packageName.isNotBlank() &&
            !liveSnap.packageName.equals(priorSelected.packageName, ignoreCase = true)
        ) {
            return LocalFollowUpOutcome.Executed(
                ok = false,
                toolName = "click_ui_element",
                summary = "Rejected stale element '${priorSelected.label}': foreground app changed from '${priorSelected.packageName}' to '${liveSnap.packageName}'."
            )
        }

        if (priorSelected != null) {
            return executeClickWithFreshRevalidation(priorSelected, onSideEffect, liveSnap)
        }

        // Check ambiguous candidates from prior find or current live screen
        val candidatePool = when {
            priorAmbiguous.isNotEmpty() -> priorAmbiguous
            priorScreenCandidates.isNotEmpty() -> priorScreenCandidates
            else -> contextManager.snapshot().lastScreenRef?.safeActionableCandidates.orEmpty()
        }

        if (candidatePool.size == 1 && candidatePool.first().packageName.equals(liveSnap.packageName, ignoreCase = true)) {
            return executeClickWithFreshRevalidation(candidatePool.first(), onSideEffect, liveSnap)
        }

        if (candidatePool.size > 1) {
            return askClickClarification(candidatePool)
        }

        return LocalFollowUpOutcome.Clarification("Which button or UI element should I click?")
    }

    private suspend fun executeClickWithFreshRevalidation(
        target: SelectedUiElementRef,
        onSideEffect: suspend (ToolSideEffects) -> Unit,
        preVerifiedSnapshot: ScreenSnapshot? = null
    ): LocalFollowUpOutcome {
        val caps = capabilityRegistry?.refresh()
        if (caps != null && (!caps.isActionAvailable(DeviceActionId.SCREEN_READ) || !caps.isActionAvailable(DeviceActionId.UI_CLICK))) {
            contextManager.onAccessibilityDisconnected()
            return LocalFollowUpOutcome.Executed(
                ok = false,
                toolName = "click_ui_element",
                summary = caps.statusFor(DeviceActionId.UI_CLICK)?.reason ?: "Accessibility Phone Control is disconnected."
            )
        }

        val liveSnap = preVerifiedSnapshot ?: when (val read = toolExecutor.readScreenRaw()) {
            is ScreenReadResult.Success -> read.snapshot
            is ScreenReadResult.Error -> return LocalFollowUpOutcome.Executed(false, "click_ui_element", read.message)
        }

        if (target.packageName.isNotBlank() &&
            !liveSnap.packageName.equals(target.packageName, ignoreCase = true)
        ) {
            return LocalFollowUpOutcome.Executed(
                ok = false,
                toolName = "click_ui_element",
                summary = "Rejected stale element '${target.label}': foreground app changed to '${liveSnap.packageName}'."
            )
        }

        // Revalidate target against fresh accessibility tree (never trust old snapshot blindly)
        val revalidated = toolExecutor.findUiElementsRaw(target.toUiQuery(liveSnap.packageName))
        when (revalidated.status) {
            ScreenInteractionStatus.SERVICE_DISCONNECTED -> {
                contextManager.onAccessibilityDisconnected()
                return LocalFollowUpOutcome.Executed(false, "click_ui_element", revalidated.message)
            }
            ScreenInteractionStatus.NOT_FOUND -> {
                return LocalFollowUpOutcome.Executed(
                    ok = false,
                    toolName = "click_ui_element",
                    summary = "Stale UI reference rejected: '${target.label}' is no longer present on '${liveSnap.packageName}'."
                )
            }
            ScreenInteractionStatus.AMBIGUOUS -> {
                val refs = revalidated.matches.take(LocalConversationContextManager.MAX_SCREEN_CANDIDATES).map { m ->
                    SelectedUiElementRef(
                        label = LocalConversationContextManager.sanitizeUntrustedLabel(m.displayLabel),
                        text = m.snapshot.text,
                        viewId = m.snapshot.viewId,
                        contentDescription = m.snapshot.contentDescription,
                        hint = m.snapshot.hintText,
                        fingerprint = m.fingerprint,
                        packageName = revalidated.packageName.ifBlank { liveSnap.packageName },
                        isSensitive = m.isSensitive,
                        isClickable = m.clickableResolved,
                        isEditable = m.snapshot.isEditable,
                        selectedAtMs = nowMs()
                    )
                }
                return askClickClarification(refs)
            }
            ScreenInteractionStatus.SUCCESS -> {
                if (revalidated.matches.size > 1) {
                    val refs = revalidated.matches.take(LocalConversationContextManager.MAX_SCREEN_CANDIDATES).map { m ->
                        SelectedUiElementRef(
                            label = LocalConversationContextManager.sanitizeUntrustedLabel(m.displayLabel),
                            text = m.snapshot.text,
                            viewId = m.snapshot.viewId,
                            contentDescription = m.snapshot.contentDescription,
                            hint = m.snapshot.hintText,
                            fingerprint = m.fingerprint,
                            packageName = revalidated.packageName.ifBlank { liveSnap.packageName },
                            isSensitive = m.isSensitive,
                            isClickable = m.clickableResolved,
                            isEditable = m.snapshot.isEditable,
                            selectedAtMs = nowMs()
                        )
                    }
                    return askClickClarification(refs)
                }
            }
            else -> {
                return LocalFollowUpOutcome.Executed(false, "click_ui_element", revalidated.message)
            }
        }

        val liveMatch = revalidated.matches.firstOrNull()
        val clickArgs = buildMap<String, JsonPrimitive> {
            val labelText = target.text.ifBlank { target.label }
            if (target.viewId.isNotBlank()) put("view_id", JsonPrimitive(target.viewId))
            else if (target.contentDescription.isNotBlank()) put("content_description", JsonPrimitive(target.contentDescription))
            else if (labelText.isNotBlank()) put("text", JsonPrimitive(labelText))
            put("expected_package", JsonPrimitive(liveSnap.packageName))
            val fp = liveMatch?.fingerprint?.ifBlank { target.fingerprint }.orEmpty()
            if (fp.isNotBlank()) put("expected_fingerprint", JsonPrimitive(fp))
        }

        val res = toolExecutor.execute("click_ui_element", clickArgs, onSideEffect)
        if (res.isConfirmationRequired()) {
            return LocalFollowUpOutcome.Executed(
                ok = false,
                toolName = "click_ui_element",
                summary = res.summary,
                awaitingConfirmation = true
            )
        }
        if (!res.ok) {
            return LocalFollowUpOutcome.Executed(false, "click_ui_element", res.summary)
        }

        val postRead = toolExecutor.readScreenRaw()
        val changed = postRead is ScreenReadResult.Success && hasScreenChanged(liveSnap, postRead.snapshot)
        return if (changed) {
            LocalFollowUpOutcome.Executed(true, "click_ui_element", "${res.summary} (verified screen change).")
        } else {
            LocalFollowUpOutcome.Executed(
                ok = false,
                toolName = "click_ui_element",
                summary = "Click verification failed: no screen or target change observed after clicking '${target.label}'."
            )
        }
    }

    private suspend fun handleSearchInReferenced(
        queryText: String,
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): LocalFollowUpOutcome {
        val snap = contextManager.snapshot()
        if (snap.lastReferencedApp == null && snap.candidateApps.size > 1) {
            return askAppClarification(
                ClarificationKind.OPEN_APP_TARGET,
                snap.candidateApps,
                "Which app should I search in: ${snap.candidateApps.joinToString(", ") { it.displayName() }}?"
            )
        }
        val targetApp = snap.lastReferencedApp
            ?: snap.currentForegroundApp?.takeIf { !it.packageName.equals(ScreenReaderEngine.JARVIS_APP_PACKAGE, ignoreCase = true) }
            ?: snap.previousForegroundApp

        if (targetApp == null) {
            return LocalFollowUpOutcome.Clarification("Which app should I search in, and what would you like to search for?")
        }

        if (queryText.isBlank()) {
            val now = nowMs()
            contextManager.setPendingClarification(
                PendingLocalClarification(
                    kind = ClarificationKind.SEARCH_QUERY,
                    question = "What would you like me to search for in ${targetApp.displayName()}?",
                    targetApp = targetApp,
                    createdAtMs = now,
                    expiresAtMs = now + LocalConversationContextManager.DEFAULT_CLARIFICATION_TTL_MS
                )
            )
            return LocalFollowUpOutcome.Clarification("What would you like me to search for in ${targetApp.displayName()}?")
        }

        return executeSearchInApp(targetApp, queryText, onSideEffect)
    }

    private suspend fun executeSearchInApp(
        targetApp: AppReference,
        queryText: String,
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): LocalFollowUpOutcome {
        val caps = capabilityRegistry?.refresh()
        if (caps != null) {
            for (req in listOf(DeviceActionId.SCREEN_READ, DeviceActionId.UI_TYPE_TEXT)) {
                if (!caps.isActionAvailable(req)) {
                    contextManager.onAccessibilityDisconnected()
                    return LocalFollowUpOutcome.Executed(
                        ok = false,
                        toolName = "type_text",
                        summary = caps.statusFor(req)?.reason ?: "Accessibility Phone Control is not connected."
                    )
                }
            }
        }

        // Launch target app if it is not already in foreground
        var liveSnap = (toolExecutor.readScreenRaw() as? ScreenReadResult.Success)?.snapshot
        if (liveSnap == null || !liveSnap.packageName.equals(targetApp.packageName, ignoreCase = true)) {
            val openRes = toolExecutor.execute(
                "open_application",
                mapOf("name" to JsonPrimitive(targetApp.appLabel.ifBlank { targetApp.packageName })),
                onSideEffect
            )
            if (!openRes.ok) {
                return LocalFollowUpOutcome.Executed(false, "open_application", openRes.summary)
            }
            liveSnap = when (val read = toolExecutor.readScreenRaw()) {
                is ScreenReadResult.Success -> read.snapshot
                is ScreenReadResult.Error -> return LocalFollowUpOutcome.Executed(false, "type_text", read.message)
            }
        }

        if (targetApp.packageName.isNotBlank() &&
            !liveSnap.packageName.equals(targetApp.packageName, ignoreCase = true)
        ) {
            return LocalFollowUpOutcome.Executed(
                ok = false,
                toolName = "type_text",
                summary = "Expected '${targetApp.displayName()}' (${targetApp.packageName}) in foreground, but active app is '${liveSnap.packageName}'."
            )
        }

        // Inspect editable fields strictly from fresh screen hierarchy
        val editableNodes = liveSnap.nodes.filter { node ->
            node.isEditable && !node.isPassword && !SensitiveUiActionPolicy.isBlockedCredentialField(node)
        }
        if (editableNodes.isEmpty()) {
            return LocalFollowUpOutcome.Executed(
                ok = false,
                toolName = "type_text",
                summary = "No non-sensitive search or input field found on '${liveSnap.packageName}'."
            )
        }

        val searchSpecific = editableNodes.filter { n ->
            n.hintText.contains("search", ignoreCase = true) ||
                n.viewId.contains("search", ignoreCase = true) ||
                n.contentDescription.contains("search", ignoreCase = true) ||
                n.text.contains("search", ignoreCase = true)
        }
        val chosenNode = when {
            searchSpecific.size == 1 -> searchSpecific.first()
            editableNodes.size == 1 -> editableNodes.first()
            else -> {
                val pool = (if (searchSpecific.size > 1) searchSpecific else editableNodes)
                    .take(LocalConversationContextManager.MAX_SCREEN_CANDIDATES)
                    .mapNotNull { node ->
                        val label = LocalConversationContextManager.sanitizeUntrustedLabel(
                            node.hintText.ifBlank { node.contentDescription }.ifBlank { node.viewId.substringAfterLast('/') }
                        )
                        if (label.isBlank() || label == "[CREDENTIAL_FIELD]") null
                        else SelectedUiElementRef(
                            label = label,
                            viewId = node.viewId,
                            contentDescription = node.contentDescription,
                            hint = node.hintText,
                            packageName = liveSnap.packageName,
                            isEditable = true,
                            selectedAtMs = nowMs()
                        )
                    }
                if (pool.size <= 1) {
                    return LocalFollowUpOutcome.Clarification("Multiple input fields are present in ${targetApp.displayName()}. Which field should I search in?")
                }
                val now = nowMs()
                val options = pool.joinToString(", ") { it.label }
                val q = "Multiple input fields are visible (${options}). Which field should I use?"
                contextManager.setPendingClarification(
                    PendingLocalClarification(
                        kind = ClarificationKind.SEARCH_FIELD_TARGET,
                        question = q,
                        candidateLabels = pool.map { it.label },
                        candidateElements = pool,
                        targetApp = targetApp,
                        pendingQueryText = queryText,
                        createdAtMs = now,
                        expiresAtMs = now + LocalConversationContextManager.DEFAULT_CLARIFICATION_TTL_MS
                    )
                )
                return LocalFollowUpOutcome.Clarification(q)
            }
        }

        val fieldRef = SelectedUiElementRef(
            label = chosenNode.hintText.ifBlank { chosenNode.contentDescription }.ifBlank { chosenNode.viewId.substringAfterLast('/') }.ifBlank { "Search" },
            viewId = chosenNode.viewId,
            contentDescription = chosenNode.contentDescription,
            hint = chosenNode.hintText,
            packageName = liveSnap.packageName,
            isEditable = true,
            selectedAtMs = nowMs()
        )
        return executeTypeIntoFieldWithVerification(targetApp, fieldRef, queryText, onSideEffect)
    }

    private suspend fun executeTypeIntoFieldWithVerification(
        targetApp: AppReference,
        field: SelectedUiElementRef,
        queryText: String,
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): LocalFollowUpOutcome {
        val args = buildMap {
            put("text", JsonPrimitive(queryText))
            if (field.viewId.isNotBlank()) put("view_id", JsonPrimitive(field.viewId))
            else if (field.hint.isNotBlank()) put("hint", JsonPrimitive(field.hint))
            else if (field.contentDescription.isNotBlank()) put("content_description", JsonPrimitive(field.contentDescription))
            put("expected_package", JsonPrimitive(targetApp.packageName))
        }
        val res = toolExecutor.execute("type_text", args, onSideEffect)
        if (res.isConfirmationRequired()) {
            return LocalFollowUpOutcome.Executed(false, "type_text", res.summary, awaitingConfirmation = true)
        }
        val verified = res.ok && (res.payload["verified"]?.jsonPrimitive?.booleanOrNull == true)
        return if (verified) {
            val safeEntered = if (SensitiveUiActionPolicy.isBlockedCredentialText(queryText)) "[REDACTED]" else "\"$queryText\""
            LocalFollowUpOutcome.Executed(true, "type_text", "Entered $safeEntered in ${targetApp.displayName()} (${res.summary}).")
        } else {
            LocalFollowUpOutcome.Executed(false, "type_text", res.summary)
        }
    }

    private fun askAppClarification(
        kind: ClarificationKind,
        apps: List<AppReference>,
        question: String
    ): LocalFollowUpOutcome.Clarification {
        val now = nowMs()
        contextManager.setPendingClarification(
            PendingLocalClarification(
                kind = kind,
                question = question,
                candidateLabels = apps.map { it.displayName() },
                candidateApps = apps,
                createdAtMs = now,
                expiresAtMs = now + LocalConversationContextManager.DEFAULT_CLARIFICATION_TTL_MS
            )
        )
        return LocalFollowUpOutcome.Clarification(question)
    }

    private fun askClickClarification(
        candidates: List<SelectedUiElementRef>
    ): LocalFollowUpOutcome.Clarification {
        val uniqueLabels = candidates.map { it.label }.filter { it.isNotBlank() }.distinct().take(4)
        val q = if (uniqueLabels.size >= 2) {
            "Multiple elements are on screen: ${uniqueLabels.joinToString(", ")}. Which one should I click?"
        } else {
            "Multiple matching elements were found. Which specific element should I click?"
        }
        val now = nowMs()
        contextManager.setPendingClarification(
            PendingLocalClarification(
                kind = ClarificationKind.CLICK_ELEMENT_TARGET,
                question = q,
                candidateLabels = uniqueLabels,
                candidateElements = candidates.take(LocalConversationContextManager.MAX_SCREEN_CANDIDATES),
                createdAtMs = now,
                expiresAtMs = now + LocalConversationContextManager.DEFAULT_CLARIFICATION_TTL_MS
            )
        )
        return LocalFollowUpOutcome.Clarification(q)
    }

    private fun hasScreenChanged(pre: ScreenSnapshot, post: ScreenSnapshot): Boolean {
        if (!pre.packageName.equals(post.packageName, ignoreCase = true)) return true
        if (pre.nodes.size != post.nodes.size) return true
        for (i in pre.nodes.indices) {
            val a = pre.nodes[i]
            val b = post.nodes[i]
            if (a.text != b.text || a.viewId != b.viewId || a.bounds != b.bounds || a.isFocused != b.isFocused) {
                return true
            }
        }
        return false
    }

    private fun hasScrollContentChanged(pre: ScreenSnapshot, post: ScreenSnapshot): Boolean {
        if (!pre.packageName.equals(post.packageName, ignoreCase = true)) return true
        if (pre.nodes.size != post.nodes.size) return true
        for (i in pre.nodes.indices) {
            val a = pre.nodes[i]
            val b = post.nodes[i]
            if (a.text != b.text || a.bounds != b.bounds || a.viewId != b.viewId) {
                return true
            }
        }
        return false
    }

    private fun containsUntrustedMarker(text: String): Boolean =
        text.contains(ScreenReaderEngine.UNTRUSTED_HEADER, ignoreCase = true) ||
            text.contains(ScreenReaderEngine.UNTRUSTED_FOOTER, ignoreCase = true) ||
            Regex("\\[\\s*/?\\s*UNTRUSTED_SCREEN_CONTENT[^\\]]*\\]", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
}
