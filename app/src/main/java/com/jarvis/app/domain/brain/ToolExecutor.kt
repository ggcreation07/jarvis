package com.jarvis.app.domain.brain

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.ai.ToolCallExecutionResult
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.network.JarvisHttpTools
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.domain.model.RoutineItem
import com.jarvis.app.platform.ClickPrecheckOutcome
import com.jarvis.app.platform.ClickUiElementResult
import com.jarvis.app.platform.DeviceCapabilityRegistry
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.FindUiElementsResult
import com.jarvis.app.platform.JarvisAccessibilityService
import com.jarvis.app.platform.JarvisNotificationListener
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.RoutineAlarms
import com.jarvis.app.platform.ScreenInteractionStatus
import com.jarvis.app.platform.ScreenReadErrorCode
import com.jarvis.app.platform.ScreenReadResult
import com.jarvis.app.platform.ScreenReaderEngine
import com.jarvis.app.platform.ScrollUiResult
import com.jarvis.app.platform.TvControlClient
import com.jarvis.app.platform.TypeTextPrecheckOutcome
import com.jarvis.app.platform.TypeTextResult
import com.jarvis.app.platform.UiElementQuery
import com.jarvis.app.platform.WakeOnLan
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ToolSideEffects(
    val openOverlay: OverlayType? = null,
    val clearChat: Boolean = false,
    val generatedBitmap: Bitmap? = null,
    val qrBitmap: Bitmap? = null,
    val qrText: String = "",
    val runRoutinePrompt: String? = null
)

interface AccessibilityNavigationBridge {
    fun isSettingsEnabled(): Boolean
    fun isServiceConnected(): Boolean
    fun performAction(action: String): Boolean?
}

interface AccessibilityScreenReaderBridge {
    suspend fun readScreen(): ScreenReadResult
}

interface AccessibilityInteractionBridge {
    suspend fun findElements(query: UiElementQuery): FindUiElementsResult
    suspend fun inspectForClick(query: UiElementQuery): ClickPrecheckOutcome
    suspend fun clickElement(query: UiElementQuery): ClickUiElementResult
    suspend fun inspectForTypeText(query: UiElementQuery, textToEnter: String): TypeTextPrecheckOutcome
    suspend fun typeText(query: UiElementQuery, textToEnter: String): TypeTextResult
    suspend fun scrollContainer(query: UiElementQuery, direction: String): ScrollUiResult
}

class ToolExecutor(
    private val repository: JarvisRepository,
    private val configStore: ConfigStore,
    private val httpTools: JarvisHttpTools,
    private val telemetry: DeviceTelemetry,
    private val deviceController: DeviceController,
    private val firebaseAiService: FirebaseAiService,
    private val remotePcClient: RemotePcClient,
    private val tvControlClient: TvControlClient,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val confirmationTimeoutMs: Long = ToolConfirmationPolicy.DEFAULT_CONFIRMATION_TIMEOUT_MS,
    private val appContext: Context? = null,
    private val accessibilityBridge: AccessibilityNavigationBridge? = null,
    private val screenReaderBridge: AccessibilityScreenReaderBridge? = null,
    private val interactionBridge: AccessibilityInteractionBridge? = null,
    private val capabilityRegistry: DeviceCapabilityRegistry? = null
) {
    internal val localDeviceController: DeviceController get() = deviceController

    internal suspend fun logLocalActionAudit(toolName: String, summary: String, ok: Boolean) {
        repository.logAction(toolName, summary, ok)
    }

    private fun navBridge(): AccessibilityNavigationBridge = accessibilityBridge ?: object : AccessibilityNavigationBridge {
        override fun isSettingsEnabled(): Boolean =
            appContext?.let { JarvisAccessibilityService.isEnabled(it) } ?: false

        override fun isServiceConnected(): Boolean =
            JarvisAccessibilityService.isConnectedNow()

        override fun performAction(action: String): Boolean? {
            val service = JarvisAccessibilityService.instance
            if (service == null || !JarvisAccessibilityService.isConnected) return false
            return service.performNavigationAction(action)
        }
    }

    /**
     * Phase 2 internal screen-reading API (not exposed to Firebase AI tool declarations yet).
     */
    internal suspend fun readScreen(): ToolCallExecutionResult {
        val result = readScreenRaw()
        val callResult = when (result) {
            is ScreenReadResult.Success -> {
                val payloadText = result.snapshot.toUntrustedDataBlock()
                val hasAccessibleText = result.snapshot.hasReadableText()
                val readableCount = result.snapshot.readableTextCount()
                val summaryText = if (hasAccessibleText) {
                    payloadText
                } else {
                    "Screen attached (${result.snapshot.packageName}), but this window exposes no accessible text (${result.snapshot.nodes.size} UI node(s), none with readable text).\n$payloadText"
                }
                ToolCallExecutionResult(
                    toolName = "read_screen",
                    ok = true,
                    summary = summaryText,
                    payload = buildJsonObject {
                        put("status", "ok")
                        put("trust", result.snapshot.trustLevel)
                        put("untrusted", true)
                        put("package_name", result.snapshot.packageName)
                        put("node_count", result.snapshot.nodes.size)
                        put("has_accessible_text", hasAccessibleText)
                        put("readable_text_count", readableCount)
                        put("max_depth_reached", result.snapshot.maxDepthReached)
                        put("truncated", result.snapshot.truncated)
                        put("data", payloadText)
                    }
                )
            }
            is ScreenReadResult.Error -> {
                ToolCallExecutionResult(
                    toolName = "read_screen",
                    ok = false,
                    summary = result.message,
                    payload = buildJsonObject {
                        put("status", "failed")
                        put("error_code", result.code.name)
                        put("trust", ScreenReaderEngine.UNTRUSTED_TRUST_LABEL)
                        put("untrusted", true)
                    }
                )
            }
        }
        val auditSummary = when (result) {
            is ScreenReadResult.Success ->
                "Read external screen (${result.snapshot.packageName}, ${result.snapshot.nodes.size} nodes)"
            is ScreenReadResult.Error -> result.message
        }
        conversationContextManager.recordCommandOutcome(
            commandSummary = auditSummary,
            toolName = callResult.toolName,
            packageName = (result as? ScreenReadResult.Success)?.snapshot?.packageName.orEmpty(),
            ok = callResult.ok,
            statusLabel = if (callResult.ok) "OK" else "FAILED"
        )
        conversationContextManager.syncTaskAndConfirmation(taskContextStore, getPendingConfirmation())
        repository.logAction(callResult.toolName, auditSummary, callResult.ok)
        return callResult
    }

    internal suspend fun readScreenRaw(): ScreenReadResult {
        val bridge = screenReaderBridge
        if (bridge != null) {
            val res = bridge.readScreen()
            when (res) {
                is ScreenReadResult.Success -> conversationContextManager.recordScreenSnapshot(res.snapshot)
                is ScreenReadResult.Error -> {
                    if (res.code == ScreenReadErrorCode.SERVICE_NOT_CONNECTED ||
                        res.code == ScreenReadErrorCode.SERVICE_ENABLED_BUT_DISCONNECTED
                    ) {
                        conversationContextManager.onAccessibilityDisconnected()
                    }
                }
            }
            return res
        }
        val service = JarvisAccessibilityService.instance
        if (service == null || !JarvisAccessibilityService.isConnected) {
            conversationContextManager.onAccessibilityDisconnected()
            val enabled = appContext?.let { JarvisAccessibilityService.isEnabled(it) } ?: false
            return if (enabled) {
                ScreenReadResult.Error(
                    code = ScreenReadErrorCode.SERVICE_ENABLED_BUT_DISCONNECTED,
                    message = "Accessibility Phone Control is enabled in Android Settings, but the service is disconnected. Reconnect JARVIS Phone Control in Accessibility Settings."
                )
            } else {
                ScreenReadResult.Error(
                    code = ScreenReadErrorCode.SERVICE_NOT_CONNECTED,
                    message = "Accessibility Phone Control isn't connected yet. Enable JARVIS Phone Control in Android Accessibility Settings."
                )
            }
        }
        val res = service.readScreen()
        when (res) {
            is ScreenReadResult.Success -> conversationContextManager.recordScreenSnapshot(res.snapshot)
            is ScreenReadResult.Error -> {
                if (res.code == ScreenReadErrorCode.SERVICE_NOT_CONNECTED ||
                    res.code == ScreenReadErrorCode.SERVICE_ENABLED_BUT_DISCONNECTED
                ) {
                    conversationContextManager.onAccessibilityDisconnected()
                }
            }
        }
        return res
    }

    private fun parseUiQuery(args: Map<String, JsonElement>): UiElementQuery = UiElementQuery(
        text = strArg(args, "text"),
        contentDescription = strArg(args, "content_description"),
        viewId = strArg(args, "view_id"),
        hint = strArg(args, "hint"),
        className = strArg(args, "class_name"),
        expectedPackage = strArg(args, "expected_package").ifBlank { strArg(args, "package_name") },
        expectedFingerprint = strArg(args, "expected_fingerprint"),
        clickableOnly = (args["clickable_only"] as? JsonPrimitive)?.booleanOrNull ?: false
    )

    private fun parseTypeTextQuery(args: Map<String, JsonElement>): UiElementQuery = UiElementQuery(
        text = strArg(args, "target_text"),
        contentDescription = strArg(args, "content_description"),
        viewId = strArg(args, "view_id"),
        hint = strArg(args, "hint"),
        className = strArg(args, "class_name"),
        expectedPackage = strArg(args, "expected_package").ifBlank { strArg(args, "package_name") },
        expectedFingerprint = strArg(args, "expected_fingerprint"),
        clickableOnly = false
    )

    internal suspend fun findUiElementsRaw(query: UiElementQuery): FindUiElementsResult {
        interactionBridge?.let {
            val found = it.findElements(query)
            if (found.status == ScreenInteractionStatus.SERVICE_DISCONNECTED) {
                conversationContextManager.onAccessibilityDisconnected()
            } else {
                conversationContextManager.recordFoundElements(found)
            }
            return found
        }
        val service = JarvisAccessibilityService.instance
        if (service == null || !JarvisAccessibilityService.isConnected) {
            conversationContextManager.onAccessibilityDisconnected()
            val enabled = appContext?.let { JarvisAccessibilityService.isEnabled(it) } ?: false
            val msg = if (enabled) {
                "Accessibility Phone Control is enabled in Android Settings, but the service is disconnected. Reconnect JARVIS Phone Control in Accessibility Settings."
            } else {
                "Accessibility Phone Control isn't connected yet. Enable JARVIS Phone Control in Android Accessibility Settings."
            }
            return FindUiElementsResult(ScreenInteractionStatus.SERVICE_DISCONNECTED, msg)
        }
        val found = service.findUiElements(query)
        if (found.status == ScreenInteractionStatus.SERVICE_DISCONNECTED) {
            conversationContextManager.onAccessibilityDisconnected()
        } else {
            conversationContextManager.recordFoundElements(found)
        }
        return found
    }

    private suspend fun inspectClickCandidateRaw(query: UiElementQuery): ClickPrecheckOutcome {
        interactionBridge?.let { return it.inspectForClick(query) }
        val service = JarvisAccessibilityService.instance
        if (service == null || !JarvisAccessibilityService.isConnected) {
            val enabled = appContext?.let { JarvisAccessibilityService.isEnabled(it) } ?: false
            val msg = if (enabled) {
                "Accessibility Phone Control is enabled in Android Settings, but the service is disconnected. Reconnect JARVIS Phone Control in Accessibility Settings."
            } else {
                "Accessibility Phone Control isn't connected yet. Enable JARVIS Phone Control in Android Accessibility Settings."
            }
            return ClickPrecheckOutcome.Failed(ScreenInteractionStatus.SERVICE_DISCONNECTED, msg)
        }
        return service.inspectClickCandidate(query)
    }

    private suspend fun clickUiElementRaw(query: UiElementQuery): ClickUiElementResult {
        interactionBridge?.let { return it.clickElement(query) }
        val service = JarvisAccessibilityService.instance
        if (service == null || !JarvisAccessibilityService.isConnected) {
            val enabled = appContext?.let { JarvisAccessibilityService.isEnabled(it) } ?: false
            val msg = if (enabled) {
                "Accessibility Phone Control is enabled in Android Settings, but the service is disconnected. Reconnect JARVIS Phone Control in Accessibility Settings."
            } else {
                "Accessibility Phone Control isn't connected yet. Enable JARVIS Phone Control in Android Accessibility Settings."
            }
            return ClickUiElementResult(ScreenInteractionStatus.SERVICE_DISCONNECTED, msg)
        }
        return service.clickUiElement(query)
    }

    private suspend fun inspectTypeTextCandidateRaw(
        query: UiElementQuery,
        textToEnter: String
    ): TypeTextPrecheckOutcome {
        interactionBridge?.let { return it.inspectForTypeText(query, textToEnter) }
        val service = JarvisAccessibilityService.instance
        if (service == null || !JarvisAccessibilityService.isConnected) {
            val enabled = appContext?.let { JarvisAccessibilityService.isEnabled(it) } ?: false
            val msg = if (enabled) {
                "Accessibility Phone Control is enabled in Android Settings, but the service is disconnected. Reconnect JARVIS Phone Control in Accessibility Settings."
            } else {
                "Accessibility Phone Control isn't connected yet. Enable JARVIS Phone Control in Android Accessibility Settings."
            }
            return TypeTextPrecheckOutcome.Failed(ScreenInteractionStatus.SERVICE_DISCONNECTED, msg)
        }
        return service.inspectTypeTextCandidate(query, textToEnter)
    }

    private suspend fun typeTextRaw(query: UiElementQuery, textToEnter: String): TypeTextResult {
        interactionBridge?.let { return it.typeText(query, textToEnter) }
        val service = JarvisAccessibilityService.instance
        if (service == null || !JarvisAccessibilityService.isConnected) {
            val enabled = appContext?.let { JarvisAccessibilityService.isEnabled(it) } ?: false
            val msg = if (enabled) {
                "Accessibility Phone Control is enabled in Android Settings, but the service is disconnected. Reconnect JARVIS Phone Control in Accessibility Settings."
            } else {
                "Accessibility Phone Control isn't connected yet. Enable JARVIS Phone Control in Android Accessibility Settings."
            }
            return TypeTextResult(ScreenInteractionStatus.SERVICE_DISCONNECTED, msg)
        }
        return service.typeTextInUiElement(query, textToEnter)
    }

    private suspend fun scrollContainerRaw(query: UiElementQuery, direction: String): ScrollUiResult {
        interactionBridge?.let { return it.scrollContainer(query, direction) }
        val service = JarvisAccessibilityService.instance
        if (service == null || !JarvisAccessibilityService.isConnected) {
            val enabled = appContext?.let { JarvisAccessibilityService.isEnabled(it) } ?: false
            val msg = if (enabled) {
                "Accessibility Phone Control is enabled in Android Settings, but the service is disconnected. Reconnect JARVIS Phone Control in Accessibility Settings."
            } else {
                "Accessibility Phone Control isn't connected yet. Enable JARVIS Phone Control in Android Accessibility Settings."
            }
            return ScrollUiResult(ScreenInteractionStatus.SERVICE_DISCONNECTED, msg)
        }
        return service.scrollUiContainer(query, direction)
    }

    private fun screenInteractionFailure(
        toolName: String,
        status: ScreenInteractionStatus,
        message: String,
        packageName: String = ""
    ) = ToolCallExecutionResult(
        toolName = toolName,
        ok = false,
        summary = message,
        payload = buildJsonObject {
            put("status", "failed")
            put("result_code", status.name)
            put("trust", ScreenReaderEngine.UNTRUSTED_TRUST_LABEL)
            put("untrusted", true)
            if (packageName.isNotBlank()) put("package_name", packageName)
        }
    )
    private class ConfirmedGrant(
        val binding: ActionBindingKey,
        val expiresAtMs: Long
    ) {
        var consumed: Boolean = false
    }

    private val confirmationLock = Any()
    private var pendingConfirmation: PendingProtectedAction? = null
    private var nextConfirmationSeq: Long = 1L

    private fun pruneExpiredLocked(now: Long) {
        pendingConfirmation?.let {
            if (now > it.expiresAtMs) pendingConfirmation = null
        }
    }

    val uiTaskExecutionEngine: UiTaskExecutionEngine by lazy { UiTaskExecutionEngine(this) }

    val localDeviceTaskExecutor: LocalDeviceTaskExecutor by lazy {
        LocalDeviceTaskExecutor(
            toolExecutor = this,
            capabilityRegistryProvider = { capabilityRegistry },
            nowMs = nowMs
        )
    }

    val taskContextStore: TaskContextStore get() = uiTaskExecutionEngine.taskContextStore

    val conversationContextManager: LocalConversationContextManager by lazy {
        LocalConversationContextManager(nowMs = nowMs)
    }

    init {
        taskContextStore.onContextChanged = {
            conversationContextManager.syncTaskAndConfirmation(taskContextStore, getPendingConfirmation())
        }
    }

    fun pauseActiveUiTask(): Boolean {
        val paused = localDeviceTaskExecutor.pauseActiveTask() || uiTaskExecutionEngine.pauseActiveTask()
        conversationContextManager.syncTaskAndConfirmation(taskContextStore, getPendingConfirmation())
        return paused
    }

    suspend fun resumeActiveUiTask(
        onProgress: suspend (TaskProgressEvent) -> Unit = {}
    ): TaskExecutionReport {
        val report = if (localDeviceTaskExecutor.hasPausedTask()) {
            localDeviceTaskExecutor.resumeActiveTask(onProgress)
        } else {
            uiTaskExecutionEngine.resumeActiveTask(onProgress)
        }
        conversationContextManager.syncTaskAndConfirmation(taskContextStore, getPendingConfirmation())
        return report
    }

    fun cancelActiveUiTask(): Boolean {
        val localCancelled = localDeviceTaskExecutor.cancelActiveTask()
        val uiCancelled = uiTaskExecutionEngine.cancelActiveTask()
        conversationContextManager.onTaskCancelledOrCleared()
        return localCancelled || uiCancelled
    }

    suspend fun resumePendingTaskAfterConfirmation(
        pendingConfirmationId: String,
        confirmedResult: ToolCallExecutionResult,
        onProgress: suspend (TaskProgressEvent) -> Unit = {}
    ): TaskExecutionReport? {
        val rep = localDeviceTaskExecutor.resumeIfPending(pendingConfirmationId, confirmedResult, onProgress)
            ?: uiTaskExecutionEngine.resumeIfPending(pendingConfirmationId, confirmedResult, onProgress)
        conversationContextManager.syncTaskAndConfirmation(taskContextStore, getPendingConfirmation())
        return rep
    }

    fun getPendingConfirmation(): PendingProtectedAction? = synchronized(confirmationLock) {
        pendingConfirmation?.takeIf { nowMs() <= it.expiresAtMs }
    }

    fun clearPendingConfirmation() {
        synchronized(confirmationLock) {
            pendingConfirmation = null
        }
        localDeviceTaskExecutor.clearPendingContinuation()
        uiTaskExecutionEngine.clearPendingContinuation()
        if (uiTaskExecutionEngine.taskContextStore.getActiveContext()?.state == TaskLifecycleState.AWAITING_CONFIRMATION) {
            uiTaskExecutionEngine.taskContextStore.cancelTask("Pending confirmation cleared or superseded.")
        }
        conversationContextManager.syncTaskAndConfirmation(taskContextStore, null)
    }

    suspend fun handleConfirmationInput(
        rawInput: String,
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): ConfirmationTurnOutcome {
        val current: PendingProtectedAction
        val isExpired: Boolean
        synchronized(confirmationLock) {
            val now = nowMs()
            val pending = pendingConfirmation
            if (pending == null) {
                return ConfirmationTurnOutcome.None
            }
            current = pending
            isExpired = now > pending.expiresAtMs
            if (isExpired) {
                pendingConfirmation = null
            }
        }

        val intent = ConfirmationClassifier.classify(rawInput)
        if (isExpired) {
            localDeviceTaskExecutor.clearPendingContinuation()
            uiTaskExecutionEngine.clearPendingContinuation()
            if (uiTaskExecutionEngine.taskContextStore.getActiveContext()?.state == TaskLifecycleState.AWAITING_CONFIRMATION) {
                uiTaskExecutionEngine.taskContextStore.cancelTask("Confirmation expired.")
            }
            conversationContextManager.syncTaskAndConfirmation(taskContextStore, null)
            return when (intent) {
                ConfirmationIntent.CANCEL -> ConfirmationTurnOutcome.Cancelled(
                    actionDescription = current.description,
                    replyText = ToolConfirmationPolicy.buildCancelledReply(current.description, rawInput)
                )
                ConfirmationIntent.AFFIRM, ConfirmationIntent.AMBIGUOUS -> ConfirmationTurnOutcome.Expired(
                    actionDescription = current.description,
                    replyText = ToolConfirmationPolicy.buildExpiredReply(current.description, rawInput)
                )
                ConfirmationIntent.UNRELATED -> ConfirmationTurnOutcome.None
            }
        }

        return when (intent) {
            ConfirmationIntent.CANCEL -> {
                clearPendingConfirmation()
                repository.logAction(
                    current.binding.toolName,
                    "Confirmation cancelled: ${current.description}",
                    false
                )
                ConfirmationTurnOutcome.Cancelled(
                    actionDescription = current.description,
                    replyText = ToolConfirmationPolicy.buildCancelledReply(current.description, rawInput)
                )
            }
            ConfirmationIntent.AMBIGUOUS -> {
                ConfirmationTurnOutcome.NeedsClarification(
                    actionDescription = current.description,
                    replyText = ToolConfirmationPolicy.buildClarificationPrompt(current.description, rawInput)
                )
            }
            ConfirmationIntent.UNRELATED -> {
                clearPendingConfirmation()
                ConfirmationTurnOutcome.None
            }
            ConfirmationIntent.AFFIRM -> {
                val pair = synchronized(confirmationLock) {
                    val now = nowMs()
                    val latest = pendingConfirmation
                    if (latest == null || latest.id != current.id || now > latest.expiresAtMs) {
                        pendingConfirmation = null
                        null
                    } else {
                        pendingConfirmation = null
                        latest to ConfirmedGrant(latest.binding, latest.expiresAtMs)
                    }
                }
                if (pair == null) {
                    clearPendingConfirmation()
                    return ConfirmationTurnOutcome.Expired(
                        actionDescription = current.description,
                        replyText = ToolConfirmationPolicy.buildExpiredReply(current.description, rawInput)
                    )
                }
                val (boundAction, scopedGrant) = pair
                val result = executeInternal(
                    toolName = boundAction.binding.toolName,
                    args = boundAction.originalArgs,
                    onSideEffect = onSideEffect,
                    authorizedGrant = scopedGrant
                )
                ConfirmationTurnOutcome.Executed(boundAction, result)
            }
        }
    }

    private fun strArg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun rawStrArg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content.orEmpty()

    private fun intArg(args: Map<String, JsonElement>, key: String): Int? =
        (args[key] as? JsonPrimitive)?.intOrNull

    suspend fun execute(
        toolName: String,
        args: Map<String, JsonElement>,
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): ToolCallExecutionResult = executeInternal(
        toolName = toolName,
        args = args,
        onSideEffect = onSideEffect,
        authorizedGrant = null
    )

    private suspend fun executeInternal(
        toolName: String,
        args: Map<String, JsonElement>,
        onSideEffect: suspend (ToolSideEffects) -> Unit,
        authorizedGrant: ConfirmedGrant?
    ): ToolCallExecutionResult {
        val normalizedName = toolName.trim()
        val sanitizedInputArgs = if (authorizedGrant == null) {
            args.filterKeys { !it.startsWith("_") }
        } else {
            args
        }
        var effectiveArgs = sanitizedInputArgs
        if (normalizedName == "click_ui_element" && authorizedGrant == null) {
            val query = parseUiQuery(sanitizedInputArgs)
            when (val precheck = inspectClickCandidateRaw(query)) {
                is ClickPrecheckOutcome.Failed -> {
                    val failedRes = screenInteractionFailure(
                        toolName = "click_ui_element",
                        status = precheck.status,
                        message = precheck.message,
                        packageName = precheck.packageName
                    )
                    repository.logAction("click_ui_element", failedRes.summary, false)
                    return failedRes
                }
                is ClickPrecheckOutcome.Ready -> {
                    effectiveArgs = sanitizedInputArgs.toMutableMap().apply {
                        put("_bound_action", JsonPrimitive("click"))
                        put("_bound_package", JsonPrimitive(precheck.candidate.packageName))
                        put("_bound_fingerprint", JsonPrimitive(precheck.candidate.fingerprint))
                        put("_resolved_label", JsonPrimitive(precheck.candidate.displayLabel))
                        put("_requires_confirmation", JsonPrimitive(precheck.candidate.isSensitive.toString()))
                    }
                }
            }
        } else if (normalizedName == "type_text" && authorizedGrant == null) {
            val query = parseTypeTextQuery(sanitizedInputArgs)
            val textToEnter = rawStrArg(sanitizedInputArgs, "text")
            when (val precheck = inspectTypeTextCandidateRaw(query, textToEnter)) {
                is TypeTextPrecheckOutcome.Failed -> {
                    val failedRes = screenInteractionFailure(
                        toolName = "type_text",
                        status = precheck.status,
                        message = precheck.message,
                        packageName = precheck.packageName
                    )
                    repository.logAction("type_text", failedRes.summary, false)
                    return failedRes
                }
                is TypeTextPrecheckOutcome.Ready -> {
                    effectiveArgs = sanitizedInputArgs.toMutableMap().apply {
                        put("_bound_action", JsonPrimitive("type_text"))
                        put("_bound_package", JsonPrimitive(precheck.candidate.packageName))
                        put("_bound_fingerprint", JsonPrimitive(precheck.candidate.fingerprint))
                        put("_resolved_label", JsonPrimitive(precheck.candidate.displayLabel))
                        put("_requires_confirmation", JsonPrimitive(precheck.candidate.isSensitive.toString()))
                    }
                }
            }
        }
        val riskAssessment = ToolConfirmationPolicy.assessRisk(normalizedName, effectiveArgs)
        if (riskAssessment.requiresConfirmation) {
            val binding = ActionBindingKey(
                toolName = normalizedName,
                canonicalArgs = ToolConfirmationPolicy.canonicalizeArgs(normalizedName, effectiveArgs)
            )
            var blockedId = ""
            var blockedDescription = ""
            var blockedSummary: String? = null
            synchronized(confirmationLock) {
                val now = nowMs()
                pruneExpiredLocked(now)
                val grantValid = authorizedGrant != null &&
                    !authorizedGrant.consumed &&
                    authorizedGrant.binding == binding &&
                    now <= authorizedGrant.expiresAtMs
                if (grantValid) {
                    authorizedGrant!!.consumed = true
                    pendingConfirmation = null
                } else {
                    val id = "confirm_${nextConfirmationSeq++}"
                    val description = ToolConfirmationPolicy.describeProtectedAction(normalizedName, effectiveArgs)
                    pendingConfirmation = PendingProtectedAction(
                        id = id,
                        binding = binding,
                        description = description,
                        originalArgs = effectiveArgs.toMap(),
                        createdAtMs = now,
                        expiresAtMs = now + confirmationTimeoutMs
                    )
                    blockedId = id
                    blockedDescription = description
                    blockedSummary = ToolConfirmationPolicy.buildConfirmationPrompt(description)
                }
            }
            if (blockedSummary != null) {
                conversationContextManager.syncTaskAndConfirmation(taskContextStore, getPendingConfirmation())
                repository.logAction(normalizedName, "Confirmation required (${riskAssessment.level.name}): $blockedDescription", false)
                return confirmationRequired(normalizedName, blockedId, blockedDescription, blockedSummary, riskAssessment)
            }
        } else {
            synchronized(confirmationLock) {
                pruneExpiredLocked(nowMs())
            }
        }

        val result = when (normalizedName) {
            "get_time" -> {
                val now = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                ok("get_time", "It's $now.")
            }
            "get_day" -> {
                val today = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
                ok("get_day", "Today is $today.")
            }
            "get_weather" -> {
                val coords = resolveCoordinates()
                if (coords == null) {
                    fail("get_weather", "I couldn't resolve your location yet. Grant Location permission or pin a city in Settings.")
                } else {
                    val snap = httpTools.fetchWeather(coords.first, coords.second, coords.third)
                    if (snap.error != null || snap.temperatureC == null) {
                        fail("get_weather", snap.error ?: "Couldn't read weather forecast.")
                    } else {
                        val where = if (snap.place.isNotBlank()) " in ${snap.place}" else ""
                        val summary = "Right now$where it's ${snap.temperatureC}°C (feels like ${snap.feelsLikeC ?: snap.temperatureC}°C), ${snap.condition}, humidity ${snap.humidityPercent ?: "—"}%, wind ${snap.windKmh ?: 0} km/h. " +
                            snap.forecastDays.joinToString(" | ")
                        ok("get_weather", summary)
                    }
                }
            }
            "get_news" -> {
                val topic = strArg(args, "topic")
                val (success, msg) = httpTools.fetchNews(topic)
                if (success) ok("get_news", msg) else fail("get_news", msg)
            }
            "find_places" -> {
                val query = strArg(args, "query")
                val coords = resolveCoordinates()
                if (coords == null) fail("find_places", "Need location access or a pinned city first.")
                else {
                    val (success, msg) = httpTools.findNearbyPlaces(query, coords.first, coords.second)
                    if (success) ok("find_places", msg) else fail("find_places", msg)
                }
            }
            "get_directions" -> {
                val dest = strArg(args, "destination")
                val coords = resolveCoordinates()
                if (coords == null) fail("get_directions", "Need current or pinned location first.")
                else {
                    val (success, msg) = httpTools.getDirections(coords.first, coords.second, dest)
                    if (success) ok("get_directions", msg) else fail("get_directions", msg)
                }
            }
            "read_webpage" -> {
                val url = strArg(args, "url")
                val (success, msg) = httpTools.readWebpage(url)
                if (success) ok("read_webpage", msg) else fail("read_webpage", msg)
            }
            "make_qr_code" -> {
                val text = strArg(args, "text")
                val bmp = httpTools.generateQrBitmap(text)
                if (bmp == null) {
                    fail("make_qr_code", "Couldn't encode an empty QR code.")
                } else {
                    onSideEffect(ToolSideEffects(qrBitmap = bmp, qrText = text))
                    ok("make_qr_code", "Generated QR code for $text.")
                }
            }
            "generate_image" -> {
                val prompt = strArg(args, "prompt")
                try {
                    val bmp = firebaseAiService.generateImage(prompt)
                    onSideEffect(ToolSideEffects(generatedBitmap = bmp))
                    ok("generate_image", "Generated image for \"$prompt\".")
                } catch (e: Exception) {
                    fail("generate_image", "Image generation failed: ${e.localizedMessage ?: "unknown error"}")
                }
            }
            "remember_fact" -> {
                val text = strArg(args, "text")
                if (text.isBlank()) fail("remember_fact", "Nothing provided to remember.")
                else {
                    repository.addFact(text)
                    ok("remember_fact", "Saved to memory: $text")
                }
            }
            "forget_fact" -> {
                val text = strArg(args, "text")
                val removed = repository.forgetFactsMatching(text)
                ok("forget_fact", if (removed > 0) "Forgot $removed matching memory item(s)." else "No matching memory item found.")
            }
            "manage_schedule" -> {
                val action = strArg(args, "action").ifBlank { "get" }
                val dayKey = RoutineAlarms.isoDate(System.currentTimeMillis())
                when (action) {
                    "add" -> {
                        val task = strArg(args, "task")
                        val time = strArg(args, "time").ifBlank { "Today" }
                        if (task.isBlank()) fail("manage_schedule", "Specify a task to add.")
                        else {
                            repository.addAgendaItem(dayKey, time, task)
                            ok("manage_schedule", "Added \"$task\" ($time) to today's agenda.")
                        }
                    }
                    "clear" -> {
                        repository.clearAgendaDay(dayKey)
                        ok("manage_schedule", "Cleared today's local agenda items.")
                    }
                    else -> {
                        val items = repository.getAgendaOnce(dayKey)
                        val calItems = if (configStore.getBrainConfigOnce().calendarSyncEnabled) {
                            deviceController.readTodaysCalendarEvents()
                        } else emptyList()
                        val all = (items + calItems).sortedBy { it.timeLabel }
                        if (all.isEmpty()) ok("manage_schedule", "Your agenda for today is clear.")
                        else ok("manage_schedule", "Today's agenda:\n" + all.joinToString("\n") { "• ${it.timeLabel}: ${it.task}" })
                    }
                }
            }
            "manage_routine" -> {
                val action = strArg(args, "action").ifBlank { "list" }
                val name = strArg(args, "name")
                when (action) {
                    "add" -> {
                        val prompt = strArg(args, "prompt")
                        val time = strArg(args, "time")
                        if (name.isBlank() || prompt.isBlank()) {
                            fail("manage_routine", "Routine needs both a name and steps.")
                        } else {
                            repository.upsertRoutine(RoutineItem(name = name, prompt = prompt, triggerTime = time))
                            ok("manage_routine", "Saved routine \"$name\"${if (time.isNotBlank()) " at $time" else ""}.")
                        }
                    }
                    "run" -> {
                        val routines = repository.getRoutinesOnce()
                        val match = routines.firstOrNull { it.name.contains(name, ignoreCase = true) }
                        if (match == null) fail("manage_routine", "Couldn't find routine \"$name\".")
                        else {
                            onSideEffect(ToolSideEffects(runRoutinePrompt = match.prompt))
                            ok("manage_routine", "Running routine \"${match.name}\": ${match.prompt}")
                        }
                    }
                    else -> {
                        val list = repository.getRoutinesOnce()
                        if (list.isEmpty()) ok("manage_routine", "You don't have any routines saved yet.")
                        else ok("manage_routine", "Saved routines: " + list.joinToString(", ") { "${it.name}${if (it.triggerTime.isNotBlank()) " (${it.triggerTime})" else ""}" })
                    }
                }
            }
            "set_timer" -> {
                val secs = intArg(args, "seconds") ?: strArg(args, "seconds").toIntOrNull() ?: 60
                val label = strArg(args, "label")
                val outcome = deviceController.setTimer(secs, label)
                if (outcome.ok) ok("set_timer", outcome.summary) else fail("set_timer", outcome.summary)
            }
            "set_alarm" -> {
                val hour = intArg(args, "hour") ?: 7
                val min = intArg(args, "minute") ?: 0
                val label = strArg(args, "label")
                val outcome = deviceController.setAlarm(hour, min, label)
                if (outcome.ok) ok("set_alarm", outcome.summary) else fail("set_alarm", outcome.summary)
            }
            "device_control" -> {
                val action = strArg(args, "action")
                val outcome = when (action) {
                    "flashlight_on" -> deviceController.toggleFlashlight(true)
                    "flashlight_off" -> deviceController.toggleFlashlight(false)
                    "volume_up" -> deviceController.adjustVolume("up")
                    "volume_down" -> deviceController.adjustVolume("down")
                    "mute" -> deviceController.adjustVolume("mute")
                    "unmute" -> deviceController.adjustVolume("unmute")
                    "dnd_on" -> deviceController.setDoNotDisturb(true)
                    "dnd_off" -> deviceController.setDoNotDisturb(false)
                    "media_play_pause" -> deviceController.sendMediaKey("play_pause")
                    "media_next" -> deviceController.sendMediaKey("next")
                    "media_prev" -> deviceController.sendMediaKey("previous")
                    "wifi_settings" -> deviceController.openSettingsScreen(Settings.ACTION_WIFI_SETTINGS, "Wi-Fi Settings")
                    "bluetooth_settings" -> deviceController.openSettingsScreen(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth Settings")
                    "accessibility_settings" -> deviceController.openSettingsScreen(Settings.ACTION_ACCESSIBILITY_SETTINGS, "Accessibility Settings")
                    "notification_listener_settings" -> deviceController.openSettingsScreen(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, "Notification Access Settings")
                    "dnd_settings" -> deviceController.openSettingsScreen(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, "Do Not Disturb Access Settings")
                    "display_settings" -> deviceController.openSettingsScreen(Settings.ACTION_DISPLAY_SETTINGS, "Display Settings")
                    "sound_settings" -> deviceController.openSettingsScreen(Settings.ACTION_SOUND_SETTINGS, "Sound Settings")
                    "battery_settings" -> deviceController.openBatterySettings()
                    "location_settings" -> deviceController.openSettingsScreen(Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Location Settings")
                    "apps_settings" -> deviceController.openSettingsScreen(Settings.ACTION_APPLICATION_SETTINGS, "Apps Settings")
                    "default_assistant_settings" -> deviceController.openDefaultAssistantSettings()
                    "system_settings" -> deviceController.openSettingsScreen(Settings.ACTION_SETTINGS, "Android System Settings")
                    else -> com.jarvis.app.platform.ActionOutcome(false, "Unknown device action: $action")
                }
                if (outcome.ok) ok("device_control", outcome.summary) else fail("device_control", outcome.summary)
            }
            "open_application" -> {
                val name = strArg(args, "name")
                val outcome = if (name.startsWith("uninstall ", ignoreCase = true) || name.startsWith("delete ", ignoreCase = true)) {
                    val target = name.substringAfter(" ").trim()
                    deviceController.requestUninstallApplication(target)
                } else {
                    deviceController.openApplication(name)
                }
                if (outcome.ok) ok("open_application", outcome.summary) else fail("open_application", outcome.summary)
            }
            "uninstall_app", "uninstall_application" -> {
                val pkgOrName = strArg(args, "package").ifBlank { strArg(args, "name") }
                val outcome = deviceController.requestUninstallApplication(pkgOrName)
                if (outcome.ok) ok(normalizedName, outcome.summary) else fail(normalizedName, outcome.summary)
            }
            "send_message", "compose_message" -> {
                val to = strArg(args, "to")
                val body = strArg(args, "body").ifBlank { strArg(args, "message") }
                val app = strArg(args, "app").ifBlank { "sms" }
                val outcome = deviceController.composeMessage(to, body, app)
                if (outcome.ok) ok(normalizedName, outcome.summary) else fail(normalizedName, outcome.summary)
            }
            "navigate_to" -> {
                val dest = strArg(args, "destination")
                val mode = strArg(args, "mode").ifBlank { "driving" }
                val outcome = deviceController.navigateMaps(dest, mode)
                if (outcome.ok) ok("navigate_to", outcome.summary) else fail("navigate_to", outcome.summary)
            }
            "play_media" -> {
                val query = strArg(args, "query")
                val app = strArg(args, "app")
                val outcome = deviceController.playMedia(query, app)
                if (outcome.ok) ok("play_media", outcome.summary) else fail("play_media", outcome.summary)
            }
            "read_notifications" -> {
                val cfg = configStore.getBrainConfigOnce()
                if (!cfg.notificationReadingEnabled) {
                    ToolCallExecutionResult(
                        toolName = "read_notifications",
                        ok = false,
                        summary = "Notification reading is currently disabled in JARVIS Settings.",
                        payload = buildJsonObject {
                            put("status", "failed")
                            put("error_code", "NOTIFICATION_READING_DISABLED")
                        }
                    )
                } else if (!JarvisNotificationListener.isConnectedNow()) {
                    val enabledInSettings = appContext?.let { JarvisNotificationListener.isEnabled(it) } ?: false
                    val msg = if (enabledInSettings) {
                        "JARVIS Notification Reader is enabled in Android Settings, but not connected yet. Open Notification Access settings to reconnect it."
                    } else {
                        "Notification access isn't enabled yet. Enable JARVIS Notification Reader in Android Settings."
                    }
                    ToolCallExecutionResult(
                        toolName = "read_notifications",
                        ok = false,
                        summary = msg,
                        payload = buildJsonObject {
                            put("status", "failed")
                            put("error_code", if (enabledInSettings) "SERVICE_ENABLED_BUT_DISCONNECTED" else "PERMISSION_DENIED")
                            put("settings_action", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        }
                    )
                } else {
                    val listener = JarvisNotificationListener.instance!!
                    val app = strArg(args, "app").ifBlank { strArg(args, "package") }
                    val from = strArg(args, "from")
                    val latestOnly = (args["latest_only"] as? JsonPrimitive)?.booleanOrNull
                        ?: strArg(args, "latest_only").equals("true", ignoreCase = true)
                    val limit = (intArg(args, "limit") ?: if (latestOnly) 1 else 6).coerceIn(1, 8)
                    val batch = listener.readActiveItemsDetailed(
                        filterApp = app,
                        filterFrom = from,
                        excludedPackages = cfg.excludedNotificationPackages
                    )
                    val selected = batch.items.take(limit)
                    val hiddenNote = if (batch.hiddenSensitiveCount > 0) {
                        " (${batch.hiddenSensitiveCount} sensitive/OTP notification(s) skipped for privacy.)"
                    } else {
                        ""
                    }
                    val summaryText = when {
                        selected.isEmpty() && batch.hiddenSensitiveCount > 0 ->
                            "No non-sensitive notifications to read right now. Skipped ${batch.hiddenSensitiveCount} OTP, PIN, or sensitive notification(s) for privacy."
                        selected.isEmpty() && batch.excludedCount > 0 && app.isNotBlank() ->
                            "Notifications from $app are excluded in JARVIS Settings."
                        selected.isEmpty() && app.isNotBlank() ->
                            "No matching notifications from $app right now."
                        selected.isEmpty() ->
                            "No unread notifications right now."
                        latestOnly -> {
                            val item = selected.first()
                            val cleanTitle = ScreenReaderEngine.sanitizeUntrustedField(item.title, 80)
                            val cleanBody = ScreenReaderEngine.sanitizeUntrustedField(item.text, 160)
                            val header = if (cleanTitle.isNotBlank()) "${item.app} ($cleanTitle)" else item.app
                            if (cleanBody.isNotBlank()) {
                                "Latest notification is from $header: $cleanBody$hiddenNote"
                            } else {
                                "Latest notification is from $header.$hiddenNote"
                            }
                        }
                        else -> {
                            val lines = selected.joinToString("\n") { item ->
                                val cleanTitle = ScreenReaderEngine.sanitizeUntrustedField(item.title, 80)
                                val cleanBody = ScreenReaderEngine.sanitizeUntrustedField(item.text, 160)
                                "• [${item.app}] ${cleanTitle.ifBlank { "Notification" }}: ${cleanBody.ifBlank { "(no preview text)" }}"
                            }
                            "Active notifications:\n$lines$hiddenNote"
                        }
                    }
                    ToolCallExecutionResult(
                        toolName = "read_notifications",
                        ok = true,
                        summary = summaryText,
                        payload = buildJsonObject {
                            put("status", "ok")
                            put("notification_count", selected.size)
                            put("hidden_sensitive_count", batch.hiddenSensitiveCount)
                            put("excluded_count", batch.excludedCount)
                            put("latest_only", latestOnly)
                        }
                    )
                }
            }
            "read_clipboard" -> {
                val clip = deviceController.readClipboard()
                if (clip.isBlank()) ok("read_clipboard", "Your clipboard is currently empty.")
                else ok("read_clipboard", "Clipboard contents: $clip")
            }
            "list_directory" -> {
                val uri = configStore.getBrainConfigOnce().grantedFolderUri
                if (uri.isBlank()) fail("list_directory", "No folder granted yet. Choose a folder in Settings -> Files.")
                else {
                    val files = deviceController.listGrantedFolder(uri)
                    if (files.isEmpty()) ok("list_directory", "The granted folder is empty.")
                    else ok("list_directory", "Files in granted folder:\n" + files.joinToString("\n") { "• $it" })
                }
            }
            "read_file" -> {
                val path = strArg(args, "path")
                val uri = configStore.getBrainConfigOnce().grantedFolderUri
                val outcome = deviceController.readGrantedFileText(uri, path)
                if (outcome.ok) ok("read_file", outcome.summary) else fail("read_file", outcome.summary)
            }
            "delete_file" -> {
                val path = strArg(args, "path").ifBlank { strArg(args, "name") }
                val uri = configStore.getBrainConfigOnce().grantedFolderUri
                val outcome = deviceController.deleteGrantedFile(uri, path)
                if (outcome.ok) ok("delete_file", outcome.summary) else fail("delete_file", outcome.summary)
            }
            "customize_screen" -> {
                val action = strArg(args, "action")
                val value = strArg(args, "value").lowercase()
                when (action) {
                    "set_look" -> {
                        val isIronMan = value.contains("iron")
                        configStore.updateScreenConfig { cur ->
                            if (isIronMan) {
                                cur.copy(
                                    look = "ironman",
                                    accentKey = "iron",
                                    mainHex = "#00E5FF",
                                    secondHex = "#FFAA00",
                                    fontStyle = "hud",
                                    cardStyle = "hud",
                                    centerpiece = "reactor"
                                )
                            } else {
                                cur.copy(
                                    look = "modern",
                                    accentKey = "cyan",
                                    mainHex = "#00E5FF",
                                    secondHex = "#3B82F6",
                                    fontStyle = "clean",
                                    cardStyle = "clean",
                                    centerpiece = "orb"
                                )
                            }
                        }
                        ok("customize_screen", "Switched HUD look to ${if (isIronMan) "Iron Man" else "Modern"}.")
                    }
                    "set_accent" -> {
                        val preset = com.jarvis.app.domain.model.DEFAULT_ACCENT_PRESETS.firstOrNull {
                            it.key.equals(value, true) || it.label.equals(value, true)
                        }
                        if (preset != null) {
                            configStore.updateScreenConfig {
                                it.copy(accentKey = preset.key, mainHex = preset.mainHex, secondHex = preset.secondHex)
                            }
                            ok("customize_screen", "Accent color set to ${preset.label}.")
                        } else {
                            fail("customize_screen", "Unknown accent preset \"$value\".")
                        }
                    }
                    "set_background" -> {
                        val bg = value.takeIf { it in setOf("grid", "glow", "aurora", "minimal") } ?: "grid"
                        configStore.updateScreenConfig { it.copy(backgroundStyle = bg) }
                        ok("customize_screen", "Background set to $bg.")
                    }
                    "reset" -> {
                        configStore.updateScreenConfig { com.jarvis.app.domain.model.ScreenConfig(quickStartCompleted = it.quickStartCompleted) }
                        ok("customize_screen", "Reset HUD appearance to defaults.")
                    }
                    else -> fail("customize_screen", "Unsupported screen action: $action")
                }
            }
            "control_interface" -> {
                val action = strArg(args, "action")
                when (action) {
                    "open_memory" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.MEMORY)); ok("control_interface", "Opened Memory.") }
                    "open_skills" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.SKILLS)); ok("control_interface", "Opened Skills.") }
                    "open_capabilities" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.CAPABILITIES)); ok("control_interface", "Opened Capabilities.") }
                    "open_settings" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.SETTINGS)); ok("control_interface", "Opened Settings.") }
                    "open_customize" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.CUSTOMIZE)); ok("control_interface", "Opened Customize.") }
                    "open_power" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.DEVICE_POWER)); ok("control_interface", "Opened Device & Power.") }
                    "open_remote" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.REMOTE_PC)); ok("control_interface", "Opened Remote PC & TV.") }
                    "clear_chat" -> { onSideEffect(ToolSideEffects(clearChat = true)); ok("control_interface", "Cleared conversation.") }
                    else -> ok("control_interface", "Handled UI action $action.")
                }
            }
            "accessibility_navigate" -> {
                val rawAction = strArg(args, "action").lowercase().replace("-", "_").replace(" ", "_")
                val action = when (rawAction) {
                    "back", "home", "recents", "notifications", "quick_settings" -> rawAction
                    "notification", "notification_panel", "notifications_panel", "notification_shade" -> "notifications"
                    "quicksettings", "quick_settings_panel", "quick_setting" -> "quick_settings"
                    "recent", "recent_apps" -> "recents"
                    else -> rawAction
                }
                if (action !in setOf("back", "home", "recents", "notifications", "quick_settings")) {
                    fail("accessibility_navigate", "Unsupported accessibility navigation action: '$rawAction'.")
                } else {
                    val bridge = navBridge()
                    if (!bridge.isServiceConnected()) {
                        if (bridge.isSettingsEnabled()) {
                            fail(
                                "accessibility_navigate",
                                "Accessibility Phone Control is enabled in Android Settings, but the service is disconnected. Reconnect JARVIS Phone Control in Accessibility Settings."
                            )
                        } else {
                            fail(
                                "accessibility_navigate",
                                "Accessibility Phone Control isn't connected yet. Enable JARVIS Phone Control in Android Accessibility Settings."
                            )
                        }
                    } else {
                        val performed = try {
                            bridge.performAction(action)
                        } catch (_: Exception) {
                            false
                        }
                        if (performed == true) {
                            val msg = when (action) {
                                "back" -> "Navigated back."
                                "home" -> "Navigated to Home screen."
                                "recents" -> "Opened Recent apps."
                                "notifications" -> "Opened Notifications panel."
                                "quick_settings" -> "Opened Quick Settings panel."
                                else -> "Performed $action."
                            }
                            ok("accessibility_navigate", msg)
                        } else {
                            fail(
                                "accessibility_navigate",
                                "Couldn't perform '$action' because the system rejected the accessibility global action."
                            )
                        }
                    }
                }
            }
            "read_screen" -> {
                return readScreen()
            }
            "find_ui_element" -> {
                val query = parseUiQuery(effectiveArgs)
                val found = findUiElementsRaw(query)
                val isOk = found.status == ScreenInteractionStatus.SUCCESS
                val untrustedData = found.toUntrustedDataBlock()
                ToolCallExecutionResult(
                    toolName = "find_ui_element",
                    ok = isOk,
                    summary = if (isOk) untrustedData else "${found.message}\n$untrustedData",
                    payload = buildJsonObject {
                        put("status", if (isOk) "ok" else "failed")
                        put("result_code", found.status.name)
                        put("trust", ScreenReaderEngine.UNTRUSTED_TRUST_LABEL)
                        put("untrusted", true)
                        put("package_name", found.packageName)
                        put("match_count", found.matches.size)
                        if (found.matches.size == 1) {
                            put("fingerprint", found.matches.first().fingerprint)
                            put("requires_confirmation", found.matches.first().isSensitive)
                        }
                        put("data", untrustedData)
                    }
                )
            }
            "click_ui_element" -> {
                val boundPkg = strArg(effectiveArgs, "_bound_package").ifBlank { strArg(effectiveArgs, "expected_package") }
                val boundFingerprint = strArg(effectiveArgs, "_bound_fingerprint").ifBlank { strArg(effectiveArgs, "expected_fingerprint") }
                val query = parseUiQuery(effectiveArgs).copy(
                    expectedPackage = boundPkg,
                    expectedFingerprint = boundFingerprint
                )
                val clicked = clickUiElementRaw(query)
                val isOk = clicked.status == ScreenInteractionStatus.SUCCESS
                ToolCallExecutionResult(
                    toolName = "click_ui_element",
                    ok = isOk,
                    summary = clicked.message,
                    payload = buildJsonObject {
                        put("status", if (isOk) "ok" else "failed")
                        put("result_code", clicked.status.name)
                        put("trust", ScreenReaderEngine.UNTRUSTED_TRUST_LABEL)
                        put("untrusted", true)
                        if (clicked.packageName.isNotBlank()) put("package_name", clicked.packageName)
                        if (clicked.fingerprint.isNotBlank()) put("fingerprint", clicked.fingerprint)
                        put("clicked_via_ancestor", clicked.usedAncestor)
                    }
                )
            }
            "type_text" -> {
                val boundPkg = strArg(effectiveArgs, "_bound_package").ifBlank { strArg(effectiveArgs, "expected_package") }
                val boundFingerprint = strArg(effectiveArgs, "_bound_fingerprint").ifBlank { strArg(effectiveArgs, "expected_fingerprint") }
                val query = parseTypeTextQuery(effectiveArgs).copy(
                    expectedPackage = boundPkg,
                    expectedFingerprint = boundFingerprint
                )
                val textToEnter = rawStrArg(effectiveArgs, "text")
                val typed = typeTextRaw(query, textToEnter)
                val isOk = typed.status == ScreenInteractionStatus.SUCCESS && typed.verified
                ToolCallExecutionResult(
                    toolName = "type_text",
                    ok = isOk,
                    summary = typed.message,
                    payload = buildJsonObject {
                        put("status", if (isOk) "ok" else "failed")
                        put("result_code", typed.status.name)
                        put("trust", ScreenReaderEngine.UNTRUSTED_TRUST_LABEL)
                        put("untrusted", true)
                        if (typed.packageName.isNotBlank()) put("package_name", typed.packageName)
                        if (typed.fingerprint.isNotBlank()) put("fingerprint", typed.fingerprint)
                        put("verified", typed.verified)
                    }
                )
            }
            "scroll_ui" -> {
                val direction = strArg(effectiveArgs, "direction")
                val query = parseUiQuery(effectiveArgs)
                val scrolled = scrollContainerRaw(query, direction)
                val isOk = scrolled.status == ScreenInteractionStatus.SUCCESS
                ToolCallExecutionResult(
                    toolName = "scroll_ui",
                    ok = isOk,
                    summary = scrolled.message,
                    payload = buildJsonObject {
                        put("status", if (isOk) "ok" else "failed")
                        put("result_code", scrolled.status.name)
                        put("trust", ScreenReaderEngine.UNTRUSTED_TRUST_LABEL)
                        put("untrusted", true)
                        if (scrolled.direction.isNotBlank()) put("direction", scrolled.direction)
                        if (scrolled.packageName.isNotBlank()) put("package_name", scrolled.packageName)
                        if (scrolled.fingerprint.isNotBlank()) put("fingerprint", scrolled.fingerprint)
                    }
                )
            }
            "execute_task_plan" -> {
                val objective = strArg(effectiveArgs, "objective").ifBlank { "Multi-step UI task" }
                val stepsElement = effectiveArgs["steps"] ?: effectiveArgs["steps_json"]
                when (val validated = UiTaskPlanner.planFromJson(objective = objective, stepsElement = stepsElement, nowMs = nowMs())) {
                    is TaskPlanValidationResult.Invalid -> {
                        ToolCallExecutionResult(
                            toolName = "execute_task_plan",
                            ok = false,
                            summary = validated.reason,
                            payload = buildJsonObject {
                                put("status", "failed")
                                put("error_code", validated.errorCode.name)
                            }
                        )
                    }
                    is TaskPlanValidationResult.Valid -> {
                        val report = uiTaskExecutionEngine.executePlan(validated.plan) { progress ->
                            if (progress is TaskProgressEvent.SideEffect) {
                                onSideEffect(progress.effect)
                            }
                        }
                        if (report.status == TaskExecutionStatus.AWAITING_CONFIRMATION) {
                            ToolCallExecutionResult(
                                toolName = "execute_task_plan",
                                ok = false,
                                summary = report.summary,
                                payload = buildJsonObject {
                                    put("status", ToolConfirmationPolicy.STATUS_CONFIRMATION_REQUIRED)
                                    put("requires_confirmation", true)
                                    put("pending_id", report.pendingConfirmationId)
                                    put("action", report.pendingConfirmationDescription)
                                    put("task_status", report.status.name)
                                    put("completed_steps", report.completedSteps.size)
                                    put("skipped_steps", report.skippedSteps.size)
                                }
                            )
                        } else {
                            val isOk = report.status == TaskExecutionStatus.SUCCEEDED
                            ToolCallExecutionResult(
                                toolName = "execute_task_plan",
                                ok = isOk,
                                summary = report.summary,
                                payload = buildJsonObject {
                                    put("status", if (isOk) "ok" else "failed")
                                    put("task_status", report.status.name)
                                    put("completed_steps", report.completedSteps.size)
                                    put("skipped_steps", report.skippedSteps.size)
                                    put("corrections_used", report.correctionsUsed)
                                    report.failedStep?.let { fs ->
                                        put("failed_step_index", fs.stepIndex)
                                        if (fs.resultCode.isNotBlank()) put("result_code", fs.resultCode)
                                    }
                                }
                            )
                        }
                    }
                }
            }
            "pc_task" -> {
                val cfg = configStore.getBrainConfigOnce()
                val goal = strArg(args, "goal")
                val kind = strArg(args, "kind").ifBlank { "browser" }
                val (success, msg) = remotePcClient.sendTask(cfg.pcHost, cfg.pcPort, goal, kind)
                if (success) ok("pc_task", msg) else fail("pc_task", msg)
            }
            "wake_pc" -> {
                val cfg = configStore.getBrainConfigOnce()
                if (cfg.pcMacAddress.isBlank()) fail("wake_pc", "No PC MAC address configured in Settings -> Devices.")
                else {
                    val (success, msg) = WakeOnLan.send(cfg.pcMacAddress, cfg.pcHost)
                    if (success) ok("wake_pc", msg) else fail("wake_pc", msg)
                }
            }
            "tv_control" -> {
                val cfg = configStore.getBrainConfigOnce()
                val action = strArg(args, "action").ifBlank { "status" }
                if (action == "wake" && cfg.tvMacAddress.isNotBlank()) {
                    val (success, msg) = WakeOnLan.send(cfg.tvMacAddress, cfg.tvHost)
                    if (success) ok("tv_control", msg) else fail("tv_control", msg)
                } else {
                    val (success, msg) = tvControlClient.checkTvReachable(cfg.tvHost, cfg.tvPort)
                    if (success) ok("tv_control", msg) else fail("tv_control", msg)
                }
            }
            else -> fail(toolName, "Unknown tool: $toolName")
        }

        val auditSummary = when (result.toolName) {
            "find_ui_element" -> {
                val pkg = result.payload["package_name"]?.jsonPrimitive?.content.orEmpty()
                val matches = result.payload["match_count"]?.jsonPrimitive?.content.orEmpty()
                val code = result.payload["result_code"]?.jsonPrimitive?.content.orEmpty()
                "Searched external UI ($pkg, matches=$matches, status=$code)"
            }
            "read_notifications" -> {
                if (result.ok) {
                    val count = result.payload["notification_count"]?.jsonPrimitive?.content ?: "0"
                    val hidden = result.payload["hidden_sensitive_count"]?.jsonPrimitive?.content ?: "0"
                    "Read active notifications on demand ($count shown, $hidden sensitive skipped)"
                } else {
                    result.summary
                }
            }
            else -> result.summary
        }
        recordContextAfterToolExecution(normalizedName, effectiveArgs, result, auditSummary)
        repository.logAction(result.toolName, auditSummary, result.ok)
        return result
    }

    private fun recordContextAfterToolExecution(
        toolName: String,
        args: Map<String, JsonElement>,
        result: ToolCallExecutionResult,
        auditSummary: String
    ) {
        when (toolName) {
            "open_application" -> {
                val name = strArg(args, "name")
                if (result.ok && name.isNotBlank() && !name.startsWith("uninstall ", true) && !name.startsWith("delete ", true)) {
                    val resolved = deviceController.resolveApplication(name)
                    if (resolved != null) {
                        conversationContextManager.onForegroundAppObserved(resolved.packageName, resolved.label)
                    } else {
                        conversationContextManager.onForegroundAppObserved(name, name)
                    }
                }
            }
            "device_control" -> {
                val action = strArg(args, "action")
                if (result.ok && action.endsWith("_settings")) {
                    conversationContextManager.recordSettingsOpened(action, action.replace('_', ' '))
                }
            }
            "scroll_ui" -> {
                val dir = strArg(args, "direction")
                val pkg = result.payload["package_name"]?.jsonPrimitive?.content.orEmpty()
                if (result.ok && dir.isNotBlank()) {
                    conversationContextManager.recordScroll(dir, pkg)
                }
            }
            "control_interface" -> {
                if (strArg(args, "action") == "clear_chat") {
                    conversationContextManager.clear()
                    return
                }
            }
            "accessibility_navigate" -> {
                val action = strArg(args, "action")
                if (result.ok && action == "home") {
                    conversationContextManager.onForegroundAppObserved("com.android.launcher", "Home")
                }
            }
        }
        val pkg = result.payload["package_name"]?.jsonPrimitive?.content.orEmpty()
        val label = strArg(args, "_resolved_label").ifBlank { strArg(args, "text") }.ifBlank { strArg(args, "name") }
        conversationContextManager.recordCommandOutcome(
            commandSummary = auditSummary,
            toolName = result.toolName,
            packageName = pkg,
            appLabel = if (toolName == "open_application") strArg(args, "name") else "",
            targetLabel = if (toolName == "type_text") strArg(args, "hint").ifBlank { strArg(args, "view_id") } else label,
            ok = result.ok,
            statusLabel = result.payload["result_code"]?.jsonPrimitive?.content.orEmpty().ifBlank { if (result.ok) "OK" else "FAILED" }
        )
        conversationContextManager.syncTaskAndConfirmation(taskContextStore, getPendingConfirmation())
    }

    private suspend fun resolveCoordinates(): Triple<Double, Double, String>? {
        val cfg = configStore.getBrainConfigOnce()
        if (cfg.pinnedLocation.isNotBlank()) {
            val geo = httpTools.geocode(cfg.pinnedLocation)
            if (geo != null) return Triple(geo.lat, geo.lon, cfg.pinnedLocation)
        }
        val gps = telemetry.lastKnownDeviceLocation()
        if (gps != null) return Triple(gps.first, gps.second, "Current location")
        val ip = httpTools.ipGeoLookup() ?: return null
        return Triple(ip.lat, ip.lon, ip.place.ifBlank { "Approximate location" })
    }

    private fun ok(tool: String, summary: String) = ToolCallExecutionResult(
        toolName = tool,
        ok = true,
        summary = summary,
        payload = buildJsonObject { put("status", "ok") }
    )

    private fun fail(tool: String, summary: String) = ToolCallExecutionResult(
        toolName = tool,
        ok = false,
        summary = summary,
        payload = buildJsonObject { put("status", "failed") }
    )

    private fun confirmationRequired(
        tool: String,
        pendingId: String,
        description: String,
        summary: String,
        riskAssessment: ActionRiskAssessment
    ) = ToolCallExecutionResult(
        toolName = tool,
        ok = false,
        summary = summary,
        payload = buildJsonObject {
            put("status", ToolConfirmationPolicy.STATUS_CONFIRMATION_REQUIRED)
            put("requires_confirmation", true)
            put("pending_id", pendingId)
            put("action", description)
            put("risk_level", riskAssessment.level.name)
            put("risk_category", riskAssessment.category)
        }
    )
}
