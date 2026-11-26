package com.jarvis.app.domain.brain

import com.jarvis.app.platform.FindUiElementsResult
import com.jarvis.app.platform.ScreenInteractionStatus
import com.jarvis.app.platform.ScreenReaderEngine
import com.jarvis.app.platform.ScreenSnapshot
import com.jarvis.app.platform.SensitiveUiActionPolicy
import com.jarvis.app.platform.UiElementQuery
import com.jarvis.app.platform.UiNodeSnapshot
import java.util.Locale

data class AppReference(
    val packageName: String,
    val appLabel: String,
    val settingsAction: String = "",
    val settingsLabel: String = "",
    val recordedAtMs: Long
) {
    val isSettingsTarget: Boolean
        get() = settingsAction.isNotBlank()

    fun displayName(): String = when {
        settingsLabel.isNotBlank() -> settingsLabel
        appLabel.isNotBlank() -> appLabel
        else -> packageName
    }
}

data class SelectedUiElementRef(
    val label: String,
    val text: String = "",
    val viewId: String = "",
    val contentDescription: String = "",
    val hint: String = "",
    val fingerprint: String = "",
    val packageName: String = "",
    val isSensitive: Boolean = false,
    val isClickable: Boolean = true,
    val isEditable: Boolean = false,
    val selectedAtMs: Long
) {
    fun toUiQuery(expectedPackageOverride: String = packageName): UiElementQuery = UiElementQuery(
        text = text.ifBlank { if (viewId.isBlank() && contentDescription.isBlank() && hint.isBlank()) label else "" },
        contentDescription = contentDescription,
        viewId = viewId,
        hint = hint,
        expectedPackage = expectedPackageOverride.ifBlank { packageName },
        expectedFingerprint = fingerprint,
        clickableOnly = false
    )
}

data class ScreenSnapshotRef(
    val packageName: String,
    val capturedAtMs: Long,
    val nodeCount: Int,
    val scrollableCount: Int,
    val editableCount: Int,
    val isLoading: Boolean,
    val isSystemDialog: Boolean,
    val isPermissionScreen: Boolean,
    val safeActionableCandidates: List<SelectedUiElementRef>,
    val safeEditableCandidates: List<SelectedUiElementRef>
)

data class RecentLocalCommandEntry(
    val commandSummary: String,
    val toolName: String,
    val packageName: String = "",
    val appLabel: String = "",
    val targetLabel: String = "",
    val ok: Boolean,
    val statusLabel: String,
    val timestampMs: Long
)

data class ActiveTaskSummaryRef(
    val taskId: String,
    val state: TaskLifecycleState,
    val objective: String,
    val completedStepsCount: Int,
    val currentStepIndex: Int?,
    val remainingStepsCount: Int,
    val lastVerifiedPackage: String
)

enum class ClarificationKind {
    OPEN_APP_TARGET,
    REOPEN_PREVIOUS_SCREEN,
    CLICK_ELEMENT_TARGET,
    SCROLL_DIRECTION,
    SEARCH_QUERY,
    SEARCH_FIELD_TARGET
}

data class PendingLocalClarification(
    val kind: ClarificationKind,
    val question: String,
    val candidateLabels: List<String> = emptyList(),
    val candidateApps: List<AppReference> = emptyList(),
    val candidateElements: List<SelectedUiElementRef> = emptyList(),
    val targetApp: AppReference? = null,
    val pendingQueryText: String = "",
    val createdAtMs: Long,
    val expiresAtMs: Long
)

data class LocalConversationContextSnapshot(
    val currentForegroundApp: AppReference? = null,
    val previousForegroundApp: AppReference? = null,
    val lastReferencedApp: AppReference? = null,
    val candidateApps: List<AppReference> = emptyList(),
    val activeTask: ActiveTaskSummaryRef? = null,
    val lastSelectedElement: SelectedUiElementRef? = null,
    val ambiguousElementCandidates: List<SelectedUiElementRef> = emptyList(),
    val lastScrollDirection: String? = null,
    val lastScreenRef: ScreenSnapshotRef? = null,
    val recentCommands: List<RecentLocalCommandEntry> = emptyList(),
    val pendingClarification: PendingLocalClarification? = null,
    val pendingConfirmationId: String? = null,
    val pendingConfirmationDescription: String? = null,
    val activeLanguage: UserLanguage = UserLanguage.ENGLISH,
    val updatedAtMs: Long = 0L,
    val expiresAtMs: Long = 0L
)

/**
 * Bounded, strictly in-memory conversation & device context store for Phase 6C.
 * Never persists to disk, never retains raw passwords/OTP/PIN/CVV content, and
 * treats all screen labels strictly as untrusted data.
 */
class LocalConversationContextManager(
    private val contextTtlMs: Long = DEFAULT_CONTEXT_TTL_MS,
    private val clarificationTtlMs: Long = DEFAULT_CLARIFICATION_TTL_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DEFAULT_CONTEXT_TTL_MS = 5 * 60_000L
        const val DEFAULT_CLARIFICATION_TTL_MS = 2 * 60_000L
        const val MAX_RECENT_COMMANDS = 10
        const val MAX_CANDIDATE_APPS = 5
        const val MAX_SCREEN_CANDIDATES = 8
        const val MAX_SAFE_LABEL_LENGTH = 60

        private val untrustedDelimiterRegex = Regex(
            "\\[\\s*/?\\s*UNTRUSTED_SCREEN_CONTENT[^\\]]*\\]",
            RegexOption.IGNORE_CASE
        )

        internal fun sanitizeUntrustedLabel(raw: String): String {
            if (raw.isBlank()) return ""
            val cleaned = raw
                .replace(untrustedDelimiterRegex, " ")
                .replace(Regex("[\\u0000-\\u001F\\u007F]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleaned.isEmpty()) return ""
            val redacted = TaskContextSanitizer.sanitizeSummaryText(cleaned)
            if (SensitiveUiActionPolicy.isBlockedCredentialText(redacted)) {
                return "[CREDENTIAL_FIELD]"
            }
            return if (redacted.length > MAX_SAFE_LABEL_LENGTH) {
                redacted.take(MAX_SAFE_LABEL_LENGTH).trim()
            } else {
                redacted
            }
        }
    }

    private val lock = Any()
    private var currentForegroundApp: AppReference? = null
    private var previousForegroundApp: AppReference? = null
    private var lastReferencedApp: AppReference? = null
    private val candidateApps = mutableListOf<AppReference>()
    private var activeTask: ActiveTaskSummaryRef? = null
    private var lastSelectedElement: SelectedUiElementRef? = null
    private val ambiguousElementCandidates = mutableListOf<SelectedUiElementRef>()
    private var lastScrollDirection: String? = null
    private var lastScreenRef: ScreenSnapshotRef? = null
    private val recentCommands = ArrayDeque<RecentLocalCommandEntry>()
    private var pendingClarification: PendingLocalClarification? = null
    private var pendingConfirmationId: String? = null
    private var pendingConfirmationDescription: String? = null
    private var activeLanguage: UserLanguage = UserLanguage.ENGLISH
    private var updatedAtMs: Long = 0L
    private var expiresAtMs: Long = 0L

    fun snapshot(): LocalConversationContextSnapshot = synchronized(lock) {
        val now = nowMs()
        pruneExpiredLocked(now)
        LocalConversationContextSnapshot(
            currentForegroundApp = currentForegroundApp,
            previousForegroundApp = previousForegroundApp,
            lastReferencedApp = lastReferencedApp,
            candidateApps = candidateApps.toList(),
            activeTask = activeTask,
            lastSelectedElement = lastSelectedElement,
            ambiguousElementCandidates = ambiguousElementCandidates.toList(),
            lastScrollDirection = lastScrollDirection,
            lastScreenRef = lastScreenRef,
            recentCommands = recentCommands.toList(),
            pendingClarification = pendingClarification,
            pendingConfirmationId = pendingConfirmationId,
            pendingConfirmationDescription = pendingConfirmationDescription,
            activeLanguage = activeLanguage,
            updatedAtMs = updatedAtMs,
            expiresAtMs = expiresAtMs
        )
    }

    fun recordDetectedLanguage(language: UserLanguage) = synchronized(lock) {
        activeLanguage = language
        touchLocked(nowMs())
    }

    fun pruneExpired(): Boolean = synchronized(lock) {
        pruneExpiredLocked(nowMs())
    }

    private fun pruneExpiredLocked(now: Long): Boolean {
        var expiredAny = false
        if (expiresAtMs > 0L && now > expiresAtMs) {
            currentForegroundApp = null
            previousForegroundApp = null
            lastReferencedApp = null
            candidateApps.clear()
            lastSelectedElement = null
            ambiguousElementCandidates.clear()
            lastScrollDirection = null
            lastScreenRef = null
            recentCommands.clear()
            pendingClarification = null
            updatedAtMs = 0L
            expiresAtMs = 0L
            expiredAny = true
        } else {
            pendingClarification?.let { clar ->
                if (now > clar.expiresAtMs) {
                    pendingClarification = null
                    expiredAny = true
                }
            }
        }
        return expiredAny
    }

    private fun touchLocked(now: Long) {
        updatedAtMs = now
        expiresAtMs = now + contextTtlMs
    }

    private fun isInternalOrLauncher(pkg: String): Boolean {
        val lower = pkg.lowercase(Locale.ROOT)
        return lower.isBlank() ||
            lower == ScreenReaderEngine.JARVIS_APP_PACKAGE ||
            lower.contains("launcher") ||
            lower == "com.android.systemui"
    }

    fun onForegroundAppObserved(
        packageName: String,
        appLabel: String = "",
        settingsAction: String = "",
        settingsLabel: String = ""
    ) = synchronized(lock) {
        val now = nowMs()
        pruneExpiredLocked(now)
        val cleanPkg = packageName.trim()
        if (cleanPkg.isBlank()) return@synchronized

        val safeLabel = sanitizeUntrustedLabel(appLabel).ifBlank { cleanPkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } }
        val newRef = AppReference(
            packageName = cleanPkg,
            appLabel = safeLabel,
            settingsAction = settingsAction,
            settingsLabel = sanitizeUntrustedLabel(settingsLabel),
            recordedAtMs = now
        )

        val current = currentForegroundApp
        if (current != null && !current.packageName.equals(cleanPkg, ignoreCase = true)) {
            if (!isInternalOrLauncher(current.packageName) || current.isSettingsTarget) {
                previousForegroundApp = current
            }
            // App changed -> invalidate old screen UI element selections and UI clarifications
            lastSelectedElement = null
            ambiguousElementCandidates.clear()
            lastScrollDirection = null
            if (lastScreenRef != null && !lastScreenRef!!.packageName.equals(cleanPkg, ignoreCase = true)) {
                lastScreenRef = null
            }
            val clar = pendingClarification
            if (clar != null && clar.kind in setOf(
                    ClarificationKind.CLICK_ELEMENT_TARGET,
                    ClarificationKind.SCROLL_DIRECTION,
                    ClarificationKind.SEARCH_FIELD_TARGET
                )
            ) {
                pendingClarification = null
            }
        }

        currentForegroundApp = newRef
        if (!isInternalOrLauncher(cleanPkg) || settingsAction.isNotBlank()) {
            lastReferencedApp = newRef
            upsertCandidateAppLocked(newRef)
        }
        touchLocked(now)
    }

    private fun upsertCandidateAppLocked(app: AppReference) {
        candidateApps.removeAll {
            it.packageName.equals(app.packageName, ignoreCase = true) &&
                it.settingsAction == app.settingsAction
        }
        candidateApps.add(0, app)
        while (candidateApps.size > MAX_CANDIDATE_APPS) {
            candidateApps.removeAt(candidateApps.lastIndex)
        }
    }

    fun recordAppReference(app: AppReference) = synchronized(lock) {
        val now = nowMs()
        pruneExpiredLocked(now)
        lastReferencedApp = app.copy(
            appLabel = sanitizeUntrustedLabel(app.appLabel),
            settingsLabel = sanitizeUntrustedLabel(app.settingsLabel),
            recordedAtMs = now
        )
        upsertCandidateAppLocked(lastReferencedApp!!)
        touchLocked(now)
    }

    fun recordAmbiguousAppCandidates(apps: List<AppReference>) = synchronized(lock) {
        val now = nowMs()
        pruneExpiredLocked(now)
        candidateApps.clear()
        apps.take(MAX_CANDIDATE_APPS).forEach { app ->
            candidateApps.add(
                app.copy(
                    appLabel = sanitizeUntrustedLabel(app.appLabel),
                    settingsLabel = sanitizeUntrustedLabel(app.settingsLabel),
                    recordedAtMs = now
                )
            )
        }
        if (apps.size == 1) {
            lastReferencedApp = candidateApps.first()
        } else {
            lastReferencedApp = null
        }
        touchLocked(now)
    }

    fun recordSettingsOpened(action: String, label: String) = synchronized(lock) {
        val now = nowMs()
        pruneExpiredLocked(now)
        val settingsRef = AppReference(
            packageName = "com.android.settings",
            appLabel = label.ifBlank { "Android Settings" },
            settingsAction = action,
            settingsLabel = sanitizeUntrustedLabel(label),
            recordedAtMs = now
        )
        currentForegroundApp?.let { prev ->
            if (!prev.isSettingsTarget && !isInternalOrLauncher(prev.packageName)) {
                previousForegroundApp = prev
            }
        }
        currentForegroundApp = settingsRef
        lastReferencedApp = settingsRef
        upsertCandidateAppLocked(settingsRef)
        lastSelectedElement = null
        ambiguousElementCandidates.clear()
        lastScrollDirection = null
        touchLocked(now)
    }

    fun recordScreenSnapshot(
        snapshot: ScreenSnapshot,
        resolvedAppLabel: String = ""
    ) {
        onForegroundAppObserved(snapshot.packageName, resolvedAppLabel)
        synchronized(lock) {
            val now = nowMs()
            pruneExpiredLocked(now)
            val actionable = mutableListOf<SelectedUiElementRef>()
            val editable = mutableListOf<SelectedUiElementRef>()
            var scrollCount = 0
            var editCount = 0

            for (node in snapshot.nodes) {
                if (node.isScrollable) scrollCount += 1
                val isCred = node.isPassword || SensitiveUiActionPolicy.isBlockedCredentialField(node)
                if (node.isEditable && !isCred) {
                    editCount += 1
                    if (editable.size < MAX_SCREEN_CANDIDATES) {
                        toSafeElementRef(node, snapshot.packageName, now)?.let { editable.add(it) }
                    }
                }
                if (!isCred && node.isClickable && actionable.size < MAX_SCREEN_CANDIDATES) {
                    toSafeElementRef(node, snapshot.packageName, now)?.let { actionable.add(it) }
                }
            }

            lastScreenRef = ScreenSnapshotRef(
                packageName = snapshot.packageName,
                capturedAtMs = snapshot.capturedAtMs,
                nodeCount = snapshot.nodes.size,
                scrollableCount = scrollCount,
                editableCount = editCount,
                isLoading = snapshot.isLoadingState(),
                isSystemDialog = snapshot.isSystemDialog(),
                isPermissionScreen = snapshot.isPermissionScreen(),
                safeActionableCandidates = actionable,
                safeEditableCandidates = editable
            )
            touchLocked(now)
        }
    }

    private fun toSafeElementRef(
        node: UiNodeSnapshot,
        pkg: String,
        now: Long
    ): SelectedUiElementRef? {
        if (node.isPassword || SensitiveUiActionPolicy.isBlockedCredentialField(node)) return null
        val safeText = if (node.isEditable) "" else sanitizeUntrustedLabel(node.text)
        val safeDesc = sanitizeUntrustedLabel(node.contentDescription)
        val safeHint = sanitizeUntrustedLabel(node.hintText)
        val safeViewId = sanitizeUntrustedLabel(node.viewId)
        val label = safeText.ifBlank { safeDesc }.ifBlank { safeHint }.ifBlank { safeViewId.substringAfterLast('/') }
        if (label.isBlank() || label == "[CREDENTIAL_FIELD]") return null
        return SelectedUiElementRef(
            label = label,
            text = safeText,
            viewId = safeViewId,
            contentDescription = safeDesc,
            hint = safeHint,
            fingerprint = "",
            packageName = pkg,
            isSensitive = SensitiveUiActionPolicy.isSensitiveCandidate(pkg, node),
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            selectedAtMs = now
        )
    }

    fun recordFoundElements(result: FindUiElementsResult) = synchronized(lock) {
        val now = nowMs()
        pruneExpiredLocked(now)
        if (result.packageName.isNotBlank()) {
            val cur = currentForegroundApp
            if (cur == null || !cur.packageName.equals(result.packageName, ignoreCase = true)) {
                currentForegroundApp = AppReference(
                    packageName = result.packageName,
                    appLabel = result.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                    recordedAtMs = now
                )
            }
        }
        ambiguousElementCandidates.clear()
        when {
            result.status == ScreenInteractionStatus.SUCCESS && result.matches.size == 1 -> {
                val match = result.matches.first()
                val node = match.snapshot
                val isCred = node.isPassword || SensitiveUiActionPolicy.isBlockedCredentialField(node)
                if (!isCred) {
                    val safeLabel = sanitizeUntrustedLabel(match.displayLabel)
                    val safeText = if (node.isEditable) "" else sanitizeUntrustedLabel(node.text)
                    val ref = SelectedUiElementRef(
                        label = safeLabel,
                        text = safeText,
                        viewId = sanitizeUntrustedLabel(node.viewId),
                        contentDescription = sanitizeUntrustedLabel(node.contentDescription),
                        hint = sanitizeUntrustedLabel(node.hintText),
                        fingerprint = match.fingerprint,
                        packageName = result.packageName.ifBlank { node.packageName },
                        isSensitive = match.isSensitive,
                        isClickable = match.clickableResolved,
                        isEditable = node.isEditable,
                        selectedAtMs = now
                    )
                    lastSelectedElement = ref
                } else {
                    lastSelectedElement = null
                }
            }
            result.matches.size > 1 -> {
                lastSelectedElement = null
                result.matches.take(MAX_SCREEN_CANDIDATES).forEach { match ->
                    val node = match.snapshot
                    val isCred = node.isPassword || SensitiveUiActionPolicy.isBlockedCredentialField(node)
                    if (!isCred) {
                        val safeLabel = sanitizeUntrustedLabel(match.displayLabel)
                        if (safeLabel.isNotBlank() && safeLabel != "[CREDENTIAL_FIELD]") {
                            ambiguousElementCandidates.add(
                                SelectedUiElementRef(
                                    label = safeLabel,
                                    text = if (node.isEditable) "" else sanitizeUntrustedLabel(node.text),
                                    viewId = sanitizeUntrustedLabel(node.viewId),
                                    contentDescription = sanitizeUntrustedLabel(node.contentDescription),
                                    hint = sanitizeUntrustedLabel(node.hintText),
                                    fingerprint = match.fingerprint,
                                    packageName = result.packageName.ifBlank { node.packageName },
                                    isSensitive = match.isSensitive,
                                    isClickable = match.clickableResolved,
                                    isEditable = node.isEditable,
                                    selectedAtMs = now
                                )
                            )
                        }
                    }
                }
            }
            else -> {
                // NOT_FOUND or error clears stale single element selection
                lastSelectedElement = null
            }
        }
        touchLocked(now)
    }

    fun recordScroll(direction: String, packageName: String = "") = synchronized(lock) {
        val now = nowMs()
        pruneExpiredLocked(now)
        if (packageName.isNotBlank()) {
            val cur = currentForegroundApp
            if (cur != null && !cur.packageName.equals(packageName, ignoreCase = true)) {
                previousForegroundApp = cur
                lastSelectedElement = null
            }
            currentForegroundApp = AppReference(
                packageName = packageName,
                appLabel = currentForegroundApp?.takeIf { it.packageName == packageName }?.appLabel
                    ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() },
                recordedAtMs = now
            )
        }
        lastScrollDirection = direction.trim().uppercase(Locale.ROOT)
        touchLocked(now)
    }

    fun recordCommandOutcome(
        commandSummary: String,
        toolName: String,
        packageName: String = "",
        appLabel: String = "",
        targetLabel: String = "",
        ok: Boolean,
        statusLabel: String
    ) = synchronized(lock) {
        val now = nowMs()
        pruneExpiredLocked(now)
        val safeSummary = TaskContextSanitizer.sanitizeSummaryText(
            commandSummary.replace(untrustedDelimiterRegex, " ")
        ).take(160)
        val safeTarget = sanitizeUntrustedLabel(targetLabel)
        val entry = RecentLocalCommandEntry(
            commandSummary = safeSummary,
            toolName = toolName,
            packageName = packageName,
            appLabel = sanitizeUntrustedLabel(appLabel),
            targetLabel = safeTarget,
            ok = ok,
            statusLabel = statusLabel,
            timestampMs = now
        )
        recentCommands.addFirst(entry)
        while (recentCommands.size > MAX_RECENT_COMMANDS) {
            recentCommands.removeLast()
        }
        if (toolName == "click_ui_element" && ok) {
            // Clicked element triggered UI transition; invalidate stale element ref until re-selected
            lastSelectedElement = null
            ambiguousElementCandidates.clear()
        }
        touchLocked(now)
    }

    fun setPendingClarification(clarification: PendingLocalClarification) = synchronized(lock) {
        val now = nowMs()
        pruneExpiredLocked(now)
        pendingClarification = clarification
        touchLocked(now)
    }

    fun clearPendingClarification() = synchronized(lock) {
        pendingClarification = null
    }

    fun syncTaskAndConfirmation(
        taskContextStore: TaskContextStore,
        pendingConfirmation: PendingProtectedAction?
    ) = synchronized(lock) {
        val now = nowMs()
        pruneExpiredLocked(now)
        val ctx = taskContextStore.getActiveContext()
        activeTask = ctx?.let {
            ActiveTaskSummaryRef(
                taskId = it.taskId,
                state = it.state,
                objective = TaskContextSanitizer.sanitizeSummaryText(it.originalRequest).take(120),
                completedStepsCount = it.completedSteps.size,
                currentStepIndex = it.currentStep?.index,
                remainingStepsCount = it.remainingSteps.size,
                lastVerifiedPackage = it.lastVerifiedPackage
            )
        }
        if (ctx != null && ctx.lastVerifiedPackage.isNotBlank()) {
            val cur = currentForegroundApp
            if (cur == null || !cur.packageName.equals(ctx.lastVerifiedPackage, ignoreCase = true)) {
                if (cur != null && (!isInternalOrLauncher(cur.packageName) || cur.isSettingsTarget)) {
                    previousForegroundApp = cur
                }
                currentForegroundApp = AppReference(
                    packageName = ctx.lastVerifiedPackage,
                    appLabel = ctx.lastVerifiedPackage.substringAfterLast('.').replaceFirstChar { c -> c.uppercase() },
                    recordedAtMs = now
                )
            }
        }
        pendingConfirmationId = pendingConfirmation?.id ?: ctx?.pendingConfirmationId
        pendingConfirmationDescription = pendingConfirmation?.description?.let {
            TaskContextSanitizer.sanitizeSummaryText(it)
        } ?: ctx?.pendingConfirmationDescription?.let {
            TaskContextSanitizer.sanitizeSummaryText(it)
        }
        if (ctx != null || pendingConfirmation != null) {
            touchLocked(now)
        }
    }

    fun onAccessibilityDisconnected() = synchronized(lock) {
        lastSelectedElement = null
        ambiguousElementCandidates.clear()
        lastScreenRef = null
        lastScrollDirection = null
        val clar = pendingClarification
        if (clar != null && clar.kind in setOf(
                ClarificationKind.CLICK_ELEMENT_TARGET,
                ClarificationKind.SCROLL_DIRECTION,
                ClarificationKind.SEARCH_FIELD_TARGET
            )
        ) {
            pendingClarification = null
        }
    }

    fun onTaskCancelledOrCleared() = synchronized(lock) {
        activeTask = null
        pendingConfirmationId = null
        pendingConfirmationDescription = null
        lastSelectedElement = null
        ambiguousElementCandidates.clear()
        pendingClarification = null
    }

    fun clear() = synchronized(lock) {
        currentForegroundApp = null
        previousForegroundApp = null
        lastReferencedApp = null
        candidateApps.clear()
        activeTask = null
        lastSelectedElement = null
        ambiguousElementCandidates.clear()
        lastScrollDirection = null
        lastScreenRef = null
        recentCommands.clear()
        pendingClarification = null
        pendingConfirmationId = null
        pendingConfirmationDescription = null
        updatedAtMs = 0L
        expiresAtMs = 0L
    }
}
