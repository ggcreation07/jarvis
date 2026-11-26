package com.jarvis.app.domain.brain

import com.jarvis.app.platform.ScreenInteractionStatus
import com.jarvis.app.platform.ScreenSnapshot
import com.jarvis.app.platform.SensitiveUiActionPolicy
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

enum class TaskLifecycleState {
    CREATED,
    RUNNING,
    PAUSED,
    AWAITING_CONFIRMATION,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED;

    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED || this == CANCELLED
    fun isActive(): Boolean = this in setOf(CREATED, RUNNING, PAUSED, AWAITING_CONFIRMATION, RECOVERING)
}

object TaskLifecycleStateMachine {
    fun isValidTransition(from: TaskLifecycleState, to: TaskLifecycleState): Boolean = when (from) {
        TaskLifecycleState.CREATED -> to in setOf(
            TaskLifecycleState.RUNNING,
            TaskLifecycleState.CANCELLED,
            TaskLifecycleState.FAILED
        )
        TaskLifecycleState.RUNNING -> to in setOf(
            TaskLifecycleState.PAUSED,
            TaskLifecycleState.AWAITING_CONFIRMATION,
            TaskLifecycleState.RECOVERING,
            TaskLifecycleState.COMPLETED,
            TaskLifecycleState.FAILED,
            TaskLifecycleState.CANCELLED
        )
        TaskLifecycleState.PAUSED -> to in setOf(
            TaskLifecycleState.RUNNING,
            TaskLifecycleState.CANCELLED,
            TaskLifecycleState.FAILED
        )
        TaskLifecycleState.AWAITING_CONFIRMATION -> to in setOf(
            TaskLifecycleState.RUNNING,
            TaskLifecycleState.CANCELLED,
            TaskLifecycleState.FAILED
        )
        TaskLifecycleState.RECOVERING -> to in setOf(
            TaskLifecycleState.RUNNING,
            TaskLifecycleState.COMPLETED,
            TaskLifecycleState.FAILED,
            TaskLifecycleState.CANCELLED
        )
        TaskLifecycleState.COMPLETED,
        TaskLifecycleState.FAILED,
        TaskLifecycleState.CANCELLED -> false
    }

    fun validateTransition(from: TaskLifecycleState, to: TaskLifecycleState) {
        if (!isValidTransition(from, to)) {
            throw IllegalStateException("Invalid task lifecycle transition from $from to $to")
        }
    }
}

data class TaskContext(
    val taskId: String,
    val originalRequest: String,
    val plannedSteps: List<PlannedTaskStep>,
    val completedSteps: List<TaskStepOutcome> = emptyList(),
    val currentStep: PlannedTaskStep? = null,
    val remainingSteps: List<PlannedTaskStep> = emptyList(),
    val lastVerifiedPackage: String = "",
    val lastVerifiedSnapshot: ScreenSnapshot? = null,
    val failedSteps: List<TaskStepOutcome> = emptyList(),
    val skippedSteps: List<PlannedTaskStep> = emptyList(),
    val recoveryAttempts: Int = 0,
    val pendingConfirmationId: String? = null,
    val pendingConfirmationDescription: String? = null,
    val state: TaskLifecycleState = TaskLifecycleState.CREATED,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val executionSummary: String? = null,
    val expectedFinalPackage: String = "",
    val expectedFinalElement: String = ""
)

object TaskContextSanitizer {
    private val sensitivePatterns = listOf(
        Regex("(?i)\\b(password|passwd|pwd|passcode|secret)\\b"),
        Regex("(?i)\\b(otp|one\\s*time\\s*password|totp|2fa|verification\\s*code)\\b"),
        Regex("(?i)\\b(pin|mpin|upi\\s*pin|cvv|cvc)\\b"),
        Regex("(?i)\\b(credit\\s*card|debit\\s*card|card\\s*number|expiry|exp\\s*date)\\b")
    )

    fun sanitizeStep(step: PlannedTaskStep): PlannedTaskStep {
        if (step.action != PlannedTaskAction.TYPE_TEXT) {
            return step
        }
        val cleanArgs = step.args.toMutableMap()
        val textValue = (cleanArgs["text"] as? JsonPrimitive)?.content.orEmpty()
        val fieldTarget = listOf(
            (cleanArgs["hint"] as? JsonPrimitive)?.content.orEmpty(),
            (cleanArgs["view_id"] as? JsonPrimitive)?.content.orEmpty(),
            (cleanArgs["content_description"] as? JsonPrimitive)?.content.orEmpty(),
            (cleanArgs["target_text"] as? JsonPrimitive)?.content.orEmpty()
        ).joinToString(" ")

        val isTargetCredential = SensitiveUiActionPolicy.isBlockedCredentialText(fieldTarget) ||
            sensitivePatterns.any { it.containsMatchIn(fieldTarget) }
        val isTextSensitive = sensitivePatterns.any { it.containsMatchIn(textValue) } ||
            (textValue.length in 4..6 && textValue.all { it.isDigit() } && isTargetCredential)

        if (isTargetCredential || isTextSensitive) {
            cleanArgs["text"] = JsonPrimitive("[REDACTED]")
            val cleanDesc = "Enter text ([REDACTED]) into \"$fieldTarget\""
            return step.copy(args = cleanArgs, description = cleanDesc)
        }
        return step
    }

    fun sanitizeSummaryText(text: String): String {
        if (text.isBlank()) return text
        return text
            .replace(Regex("(?i)(password|passwd|pwd|passcode|pin|mpin|otp|totp|cvv|cvc)\\s*[:=]\\s*\\S+"), "$1=[REDACTED]")
            .replace(Regex("(?i)\\b(otp|pin|cvv|passcode)\\s+(\\d{3,8})\\b"), "$1 [REDACTED]")
            .replace(
                Regex("(?i)\\b(type|enter|write|input)\\s+([\"'])([^\"']+)\\2(\\s+(?:in|into|on)\\s+(?:the\\s+)?[^,;.]*?(?:password|passwd|pwd|passcode|secret|otp|totp|2fa|pin|mpin|cvv|cvc)[^,;.]*)"),
                "$1 [REDACTED]$4"
            )
            .replace(
                Regex("(?i)\\b(type|enter|write|input)\\s+(\\S+)(\\s+(?:in|into)\\s+(?:the\\s+)?[^,;.]*?(?:password|passwd|pwd|passcode|secret|otp|totp|2fa|pin|mpin|cvv|cvc)[^,;.]*)"),
                "$1 [REDACTED]$3"
            )
    }

    fun sanitizeOutcome(outcome: TaskStepOutcome): TaskStepOutcome {
        val sanitizedMsg = sanitizeSummaryText(outcome.message)
        return outcome.copy(message = sanitizedMsg)
    }

    fun sanitizeSnapshot(snapshot: ScreenSnapshot?): ScreenSnapshot? {
        if (snapshot == null) return null
        val sanitizedNodes = snapshot.nodes.map { node ->
            val isCred = node.isPassword || SensitiveUiActionPolicy.isBlockedCredentialField(node)
            if (isCred && (node.text.isNotEmpty() || node.hintText.isNotEmpty())) {
                node.copy(text = "", hintText = "")
            } else {
                node
            }
        }
        return snapshot.copy(nodes = sanitizedNodes)
    }
}

enum class TaskFollowUpIntent {
    CONTINUE,
    CHECK_PREVIOUS,
    CANCEL,
    PAUSE,
    NOT_FOLLOW_UP
}

object TaskFollowUpClassifier {
    private val continuePhrases = listOf(
        "next step karo", "agla step karo", "aage badho", "aage chalo", "pudhe ja",
        "pudhil step kara", "chalu rakho", "chalu kara", "continue karo", "resume karo",
        "aage ka step karo", "next step", "next step please", "do next step", "continue task",
        "resume task", "go ahead", "keep going", "proceed with task",
        "आगे बढ़ो", "अगला स्टेप करो", "अगला स्टेप कर", "नेक्स्ट स्टेप करो", "जारी रखो", "कंटिन्यू करो",
        "पुढे जा", "पुढील स्टेप कर", "पुढील स्टेप करा", "पुढचा स्टेप कर", "पुढचा स्टेप करा", "चालू ठेवा", "कंटिन्यू करा"
    )

    private val continueTokens = setOf(
        "continue", "resume", "proceed", "next", "aage", "pudhe"
    )

    private val checkPreviousPhrases = listOf(
        "pichla step dobara check karo", "pichla step check karo", "pichla step phir se check karo",
        "pichhla step check karo", "magcha step bagha", "magil step tasha", "pichla step verify karo",
        "check previous step", "check last step", "verify previous step", "verify last step",
        "check previous step again", "check last step again", "recheck last step", "re-verify last step",
        "what was the previous step", "did the last step work", "recheck previous step",
        "पिछला स्टेप दोबारा चेक करो", "पिछला स्टेप चेक करो", "मागचा स्टेप तपासा"
    )

    private val cancelPhrases = listOf(
        "task cancel karo", "task band karo", "task roko", "task radd karo", "task radd kara",
        "task cancel kara", "task thambwa", "stop the task", "cancel the task", "cancel active task",
        "cancel task", "stop task", "abort task", "terminate task",
        "टास्क कैंसिल करो", "टास्क रद्द करा", "टास्क थांबवा"
    )

    private val pausePhrases = listOf(
        "task pause karo", "pause task", "pause the task", "hold the task", "task hold karo",
        "ruko task", "task ruk jao", "टास्क रोको", "टास्क थांबवा"
    )

    fun classify(rawInput: String): TaskFollowUpIntent {
        val trimmed = rawInput.trim().lowercase(Locale.ROOT)
            .replace("'", "")
            .replace("\u2019", "")
            .replace(Regex("[\\p{Punct}\\u0964\\u0965]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (trimmed.isEmpty()) return TaskFollowUpIntent.NOT_FOLLOW_UP

        if (cancelPhrases.any { trimmed.contains(it) } || trimmed == "cancel task" || trimmed == "stop task") {
            return TaskFollowUpIntent.CANCEL
        }

        if (checkPreviousPhrases.any { trimmed.contains(it) } || trimmed == "check previous step" || trimmed == "check last step") {
            return TaskFollowUpIntent.CHECK_PREVIOUS
        }

        if (pausePhrases.any { trimmed.contains(it) } || trimmed == "pause task" || trimmed == "pause") {
            return TaskFollowUpIntent.PAUSE
        }

        if (continuePhrases.any { trimmed.contains(it) } || trimmed in continueTokens) {
            return TaskFollowUpIntent.CONTINUE
        }

        return TaskFollowUpIntent.NOT_FOLLOW_UP
    }
}

/**
 * Lightweight, strictly in-memory TaskContextStore.
 * Holds no persistent database or file records.
 */
class TaskContextStore(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val lock = Any()
    private var activeContext: TaskContext? = null
    private var lastSummary: String? = null
    private var lastCompletedTaskId: String? = null

    @Volatile
    var onContextChanged: ((TaskContext?) -> Unit)? = null

    private fun notifyChanged(ctx: TaskContext?) {
        onContextChanged?.invoke(ctx)
    }

    fun getActiveContext(): TaskContext? = synchronized(lock) { activeContext }

    fun getLastExecutionSummary(): String? = synchronized(lock) { lastSummary }

    fun hasActiveTask(): Boolean = synchronized(lock) {
        activeContext?.state?.isActive() == true
    }

    fun createTask(plan: UiTaskPlan): TaskContext {
        val newContext = synchronized(lock) {
            val current = activeContext
            if (current != null && current.state.isActive()) {
                throw IllegalStateException(
                    "Cannot create task '${plan.id}': active task '${current.taskId}' is already in state ${current.state}."
                )
            }
            val sanitizedSteps = plan.steps.map { TaskContextSanitizer.sanitizeStep(it) }
            val nc = TaskContext(
                taskId = plan.id,
                originalRequest = TaskContextSanitizer.sanitizeSummaryText(plan.objective),
                plannedSteps = sanitizedSteps,
                remainingSteps = sanitizedSteps,
                state = TaskLifecycleState.CREATED,
                createdAtMs = nowMs(),
                updatedAtMs = nowMs(),
                expectedFinalPackage = plan.expectedFinalPackage,
                expectedFinalElement = plan.expectedFinalElement
            )
            activeContext = nc
            nc
        }
        notifyChanged(newContext)
        return newContext
    }

    fun transitionTo(newState: TaskLifecycleState): TaskContext {
        val updated = synchronized(lock) {
            val current = activeContext
                ?: throw IllegalStateException("Cannot transition to $newState: no task context exists")
            TaskLifecycleStateMachine.validateTransition(current.state, newState)
            val u = current.copy(state = newState, updatedAtMs = nowMs())
            activeContext = u
            u
        }
        notifyChanged(updated)
        return updated
    }

    fun onStepStarted(step: PlannedTaskStep, remaining: List<PlannedTaskStep>): TaskContext {
        val updated = synchronized(lock) {
            val current = activeContext
                ?: throw IllegalStateException("Cannot mark step started: no active task context exists")
            if (current.state == TaskLifecycleState.CREATED) {
                TaskLifecycleStateMachine.validateTransition(current.state, TaskLifecycleState.RUNNING)
            }
            val sanitizedStep = TaskContextSanitizer.sanitizeStep(step)
            val sanitizedRemaining = remaining.map { TaskContextSanitizer.sanitizeStep(it) }
            val u = current.copy(
                state = TaskLifecycleState.RUNNING,
                currentStep = sanitizedStep,
                remainingSteps = sanitizedRemaining,
                updatedAtMs = nowMs()
            )
            activeContext = u
            u
        }
        notifyChanged(updated)
        return updated
    }

    fun onStepCompleted(
        outcome: TaskStepOutcome,
        verifiedPackage: String,
        screenSnapshot: ScreenSnapshot? = null
    ): TaskContext {
        val updated = synchronized(lock) {
            val current = activeContext
                ?: throw IllegalStateException("Cannot complete step: no active task context exists")
            val sanitizedOutcome = TaskContextSanitizer.sanitizeOutcome(outcome)
            val sanitizedSnapshot = TaskContextSanitizer.sanitizeSnapshot(screenSnapshot)
            val u = current.copy(
                completedSteps = current.completedSteps + sanitizedOutcome,
                currentStep = null,
                lastVerifiedPackage = verifiedPackage.ifBlank { current.lastVerifiedPackage },
                lastVerifiedSnapshot = sanitizedSnapshot ?: current.lastVerifiedSnapshot,
                updatedAtMs = nowMs()
            )
            activeContext = u
            u
        }
        notifyChanged(updated)
        return updated
    }

    fun onAwaitingConfirmation(pendingId: String, description: String): TaskContext {
        val updated = synchronized(lock) {
            val current = activeContext
                ?: throw IllegalStateException("Cannot await confirmation: no active task context exists")
            TaskLifecycleStateMachine.validateTransition(current.state, TaskLifecycleState.AWAITING_CONFIRMATION)
            val u = current.copy(
                state = TaskLifecycleState.AWAITING_CONFIRMATION,
                pendingConfirmationId = pendingId,
                pendingConfirmationDescription = description,
                updatedAtMs = nowMs()
            )
            activeContext = u
            u
        }
        notifyChanged(updated)
        return updated
    }

    fun onRecoveryAttempted(
        failedOutcome: TaskStepOutcome,
        recoveryStep: PlannedTaskStep
    ): TaskContext {
        val updated = synchronized(lock) {
            val current = activeContext
                ?: throw IllegalStateException("Cannot start recovery: no active task context exists")
            TaskLifecycleStateMachine.validateTransition(current.state, TaskLifecycleState.RECOVERING)
            val sanitizedOutcome = TaskContextSanitizer.sanitizeOutcome(failedOutcome)
            val sanitizedRecovery = TaskContextSanitizer.sanitizeStep(recoveryStep)
            val u = current.copy(
                state = TaskLifecycleState.RECOVERING,
                failedSteps = current.failedSteps + sanitizedOutcome,
                remainingSteps = listOf(sanitizedRecovery) + current.remainingSteps,
                recoveryAttempts = current.recoveryAttempts + 1,
                updatedAtMs = nowMs()
            )
            activeContext = u
            u
        }
        notifyChanged(updated)
        return updated
    }

    fun pauseTask(reason: String = "Paused by user"): TaskContext {
        val updated = synchronized(lock) {
            val current = activeContext
                ?: throw IllegalStateException("Cannot pause task: no active task context exists")
            TaskLifecycleStateMachine.validateTransition(current.state, TaskLifecycleState.PAUSED)
            val u = current.copy(
                state = TaskLifecycleState.PAUSED,
                executionSummary = TaskContextSanitizer.sanitizeSummaryText(reason),
                updatedAtMs = nowMs()
            )
            activeContext = u
            u
        }
        notifyChanged(updated)
        return updated
    }

    fun completeTask(summary: String): TaskContext? {
        val result = synchronized(lock) {
            val current = activeContext ?: return null
            TaskLifecycleStateMachine.validateTransition(current.state, TaskLifecycleState.COMPLETED)
            lastSummary = TaskContextSanitizer.sanitizeSummaryText(summary)
            lastCompletedTaskId = current.taskId
            activeContext = null
            null
        }
        notifyChanged(null)
        return result
    }

    fun failTask(summary: String, failedStepOutcome: TaskStepOutcome? = null): TaskContext? {
        val result = synchronized(lock) {
            val current = activeContext ?: return null
            TaskLifecycleStateMachine.validateTransition(current.state, TaskLifecycleState.FAILED)
            lastSummary = TaskContextSanitizer.sanitizeSummaryText(summary)
            lastCompletedTaskId = current.taskId
            activeContext = null
            null
        }
        notifyChanged(null)
        return result
    }

    fun cancelTask(summary: String = "Task cancelled"): TaskContext? {
        val result = synchronized(lock) {
            val current = activeContext ?: return null
            TaskLifecycleStateMachine.validateTransition(current.state, TaskLifecycleState.CANCELLED)
            lastSummary = TaskContextSanitizer.sanitizeSummaryText(summary)
            lastCompletedTaskId = current.taskId
            activeContext = null
            null
        }
        notifyChanged(null)
        return result
    }

    fun clear() {
        synchronized(lock) {
            activeContext = null
            lastSummary = null
            lastCompletedTaskId = null
        }
        notifyChanged(null)
    }
}
