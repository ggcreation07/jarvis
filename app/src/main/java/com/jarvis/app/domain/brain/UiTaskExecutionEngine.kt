package com.jarvis.app.domain.brain

import com.jarvis.app.data.ai.ToolCallExecutionResult
import com.jarvis.app.platform.ScreenInteractionStatus
import com.jarvis.app.platform.ScreenReadErrorCode
import com.jarvis.app.platform.ScreenReadResult
import com.jarvis.app.platform.ScreenReadinessDetector
import com.jarvis.app.platform.ScreenSnapshot
import com.jarvis.app.platform.SensitiveUiActionPolicy
import com.jarvis.app.platform.UiWaitConfig
import com.jarvis.app.platform.UiWaitOutcome
import com.jarvis.app.platform.UiWaitPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class TaskExecutionStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    AWAITING_CONFIRMATION,
    PAUSED,
    VERIFICATION_UNAVAILABLE,
    ALREADY_RUNNING,
    LIMIT_EXCEEDED
}

enum class FinalVerificationStatus {
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED,
    VERIFICATION_UNAVAILABLE
}

enum class TaskStepStatus {
    COMPLETED,
    FAILED,
    AWAITING_CONFIRMATION,
    PAUSED,
    CANCELLED,
    SKIPPED
}

data class TaskStepOutcome(
    val stepIndex: Int,
    val action: PlannedTaskAction,
    val description: String,
    val status: TaskStepStatus,
    val verified: Boolean,
    val packageName: String = "",
    val resultCode: String = "",
    val message: String,
    val verificationAttempts: Int = 1
)

data class GoalVerificationResult(
    val status: FinalVerificationStatus,
    val isVerified: Boolean,
    val failureReason: String? = null
)

fun interface TaskGoalVerifier {
    suspend fun verifyGoal(
        objective: String,
        finalSnapshot: ScreenSnapshot?,
        plan: UiTaskPlan
    ): GoalVerificationResult
}

class DefaultTaskGoalVerifier(
    private val expectedFinalPackage: String = "",
    private val expectedFinalElement: String = ""
) : TaskGoalVerifier {
    override suspend fun verifyGoal(
        objective: String,
        finalSnapshot: ScreenSnapshot?,
        plan: UiTaskPlan
    ): GoalVerificationResult {
        if (finalSnapshot == null) {
            return GoalVerificationResult(
                status = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                isVerified = false,
                failureReason = "Live screen snapshot is unavailable for final goal verification."
            )
        }
        val targetPkg = expectedFinalPackage.ifBlank { plan.expectedFinalPackage }
        if (targetPkg.isNotBlank() && !finalSnapshot.packageName.equals(targetPkg, ignoreCase = true)) {
            return GoalVerificationResult(
                status = FinalVerificationStatus.FAILED,
                isVerified = false,
                failureReason = "Final screen package mismatch: expected '$targetPkg', but active screen is '${finalSnapshot.packageName}'."
            )
        }
        val targetEl = expectedFinalElement.ifBlank { plan.expectedFinalElement }
        if (targetEl.isNotBlank()) {
            val elFound = finalSnapshot.nodes.any { node ->
                node.text.equals(targetEl, ignoreCase = true) ||
                    node.viewId.equals(targetEl, ignoreCase = true) ||
                    node.contentDescription.equals(targetEl, ignoreCase = true) ||
                    node.hintText.equals(targetEl, ignoreCase = true) ||
                    node.text.contains(targetEl, ignoreCase = true)
            }
            if (!elFound) {
                return GoalVerificationResult(
                    status = FinalVerificationStatus.FAILED,
                    isVerified = false,
                    failureReason = "Expected final element '$targetEl' was not found on the final screen."
                )
            }
        }
        return GoalVerificationResult(
            status = FinalVerificationStatus.COMPLETED,
            isVerified = true
        )
    }
}

data class TaskExecutionReport(
    val taskId: String,
    val objective: String,
    val status: TaskExecutionStatus,
    val completedSteps: List<TaskStepOutcome>,
    val finalVerificationStatus: FinalVerificationStatus = when (status) {
        TaskExecutionStatus.SUCCEEDED -> FinalVerificationStatus.COMPLETED
        TaskExecutionStatus.AWAITING_CONFIRMATION, TaskExecutionStatus.PAUSED ->
            if (completedSteps.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED
        TaskExecutionStatus.CANCELLED, TaskExecutionStatus.FAILED, TaskExecutionStatus.LIMIT_EXCEEDED ->
            if (completedSteps.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED
        TaskExecutionStatus.VERIFICATION_UNAVAILABLE -> FinalVerificationStatus.VERIFICATION_UNAVAILABLE
        TaskExecutionStatus.ALREADY_RUNNING -> FinalVerificationStatus.FAILED
    },
    val failedStep: TaskStepOutcome? = null,
    val skippedSteps: List<PlannedTaskStep> = emptyList(),
    val correctionsUsed: Int = 0,
    val pendingConfirmationId: String = "",
    val pendingConfirmationDescription: String = "",
    val verificationFailureReason: String? = null,
    val summary: String
)

sealed interface TaskProgressEvent {
    data class Announced(
        val taskId: String,
        val objective: String,
        val totalSteps: Int,
        val message: String
    ) : TaskProgressEvent

    data class StepStarted(
        val stepIndex: Int,
        val totalSteps: Int,
        val step: PlannedTaskStep,
        val message: String
    ) : TaskProgressEvent

    data class StepFinished(
        val outcome: TaskStepOutcome,
        val totalSteps: Int
    ) : TaskProgressEvent

    data class CorrectionApplied(
        val correctionNumber: Int,
        val maxCorrections: Int,
        val reason: String,
        val replacementStep: PlannedTaskStep
    ) : TaskProgressEvent

    data class SideEffect(val effect: ToolSideEffects) : TaskProgressEvent
}

fun interface TaskPlanCorrector {
    suspend fun proposeCorrection(
        failedStep: PlannedTaskStep,
        failure: TaskStepOutcome,
        screenSnapshot: ScreenSnapshot?,
        remainingSteps: List<PlannedTaskStep>
    ): RawTaskStep?
}

class UiTaskExecutionEngine(
    private val toolExecutor: ToolExecutor,
    private val planCorrector: TaskPlanCorrector? = null,
    val taskContextStore: TaskContextStore = TaskContextStore(),
    private val goalVerifier: TaskGoalVerifier? = null,
    private val waitConfig: UiWaitConfig = UiWaitConfig(),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private class CancellationHandle(
        val taskId: String,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val pauseRequested: AtomicBoolean = AtomicBoolean(false),
        @Volatile var job: Job? = null
    )

    private data class PendingTaskContinuation(
        val taskId: String,
        val objective: String,
        val pendingConfirmationId: String,
        val haltedStep: PlannedTaskStep,
        val completedSteps: List<TaskStepOutcome>,
        val remainingSteps: List<PlannedTaskStep>,
        val trackedPackage: String,
        val executedSignatures: List<String>,
        val totalStepsExecuted: Int,
        val correctionsUsed: Int,
        val expectedFinalPackage: String = "",
        val expectedFinalElement: String = "",
        val preActionSnapshot: ScreenSnapshot? = null
    )

    private val isRunning = AtomicBoolean(false)
    private val activeHandle = AtomicReference<CancellationHandle?>(null)
    private val continuationLock = Any()
    private var pendingContinuation: PendingTaskContinuation? = null

    fun isTaskRunning(): Boolean = isRunning.get()

    fun pauseActiveTask(): Boolean {
        val handle = activeHandle.get() ?: return false
        handle.pauseRequested.set(true)
        return true
    }

    fun cancelActiveTask(): Boolean {
        clearPendingContinuation()
        val hadActive = taskContextStore.hasActiveTask()
        taskContextStore.cancelTask("Active task cancelled by user request.")
        val handle = activeHandle.get()
        if (handle != null) {
            handle.cancelled.set(true)
            handle.job?.cancel()
            return true
        }
        return hadActive
    }

    fun clearPendingContinuation() {
        synchronized(continuationLock) {
            pendingContinuation = null
        }
    }

    suspend fun executePlan(
        plan: UiTaskPlan,
        onProgress: suspend (TaskProgressEvent) -> Unit = {}
    ): TaskExecutionReport {
        if (!isRunning.compareAndSet(false, true)) {
            return TaskExecutionReport(
                taskId = plan.id,
                objective = plan.objective,
                status = TaskExecutionStatus.ALREADY_RUNNING,
                completedSteps = emptyList(),
                skippedSteps = plan.steps,
                summary = "Another multi-step task is already running. Wait for it to finish or cancel it first."
            )
        }

        if (taskContextStore.hasActiveTask()) {
            isRunning.set(false)
            val current = taskContextStore.getActiveContext()
            return TaskExecutionReport(
                taskId = plan.id,
                objective = plan.objective,
                status = TaskExecutionStatus.ALREADY_RUNNING,
                completedSteps = emptyList(),
                skippedSteps = plan.steps,
                summary = "Cannot start task '${plan.id}': active task '${current?.taskId}' is already in progress (${current?.state}). Cancel or finish it first."
            )
        }

        val handle = CancellationHandle(plan.id)
        activeHandle.set(handle)
        clearPendingContinuation()
        return try {
            taskContextStore.createTask(plan)
            taskContextStore.transitionTo(TaskLifecycleState.RUNNING)
            runLoopUnderActiveLock(
                plan = plan,
                handle = handle,
                taskId = plan.id,
                objective = plan.objective,
                initialSteps = plan.steps,
                initialCompleted = emptyList(),
                initialTrackedPackage = "",
                initialExecutedSignatures = emptyList(),
                initialTotalExecuted = 0,
                initialCorrectionsUsed = 0,
                announceTask = true,
                onProgress = onProgress
            )
        } finally {
            activeHandle.set(null)
            isRunning.set(false)
        }
    }

    suspend fun resumeActiveTask(
        onProgress: suspend (TaskProgressEvent) -> Unit = {}
    ): TaskExecutionReport {
        val active = taskContextStore.getActiveContext()
            ?: return TaskExecutionReport(
                taskId = "",
                objective = "",
                status = TaskExecutionStatus.FAILED,
                finalVerificationStatus = FinalVerificationStatus.FAILED,
                completedSteps = emptyList(),
                summary = "No active task to resume."
            )

        if (active.state != TaskLifecycleState.PAUSED) {
            return TaskExecutionReport(
                taskId = active.taskId,
                objective = active.originalRequest,
                status = TaskExecutionStatus.FAILED,
                finalVerificationStatus = FinalVerificationStatus.PARTIALLY_COMPLETED,
                completedSteps = active.completedSteps,
                summary = "Cannot resume task '${active.taskId}': task is in state ${active.state}, not PAUSED."
            )
        }

        // Re-read live screen before resuming after pause/interruption
        val screenCheck = toolExecutor.readScreenRaw()
        val livePkg = when (screenCheck) {
            is ScreenReadResult.Success -> screenCheck.snapshot.packageName
            is ScreenReadResult.Error -> ""
        }

        if (screenCheck is ScreenReadResult.Error) {
            val failureSummary = "Failed to resume task: unable to read current screen (${screenCheck.message})."
            taskContextStore.failTask(failureSummary)
            return TaskExecutionReport(
                taskId = active.taskId,
                objective = active.originalRequest,
                status = TaskExecutionStatus.FAILED,
                finalVerificationStatus = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                completedSteps = active.completedSteps,
                skippedSteps = active.remainingSteps,
                correctionsUsed = active.recoveryAttempts,
                verificationFailureReason = failureSummary,
                summary = failureSummary
            )
        }

        // Detect when foreground app or screen no longer matches expected context
        val expectedPkg = active.lastVerifiedPackage.ifBlank {
            active.remainingSteps.firstOrNull()?.expectedPackage.orEmpty()
        }
        if (expectedPkg.isNotBlank() && !livePkg.equals(expectedPkg, ignoreCase = true)) {
            val mismatchSummary = "Screen context mismatch: Expected foreground package '$expectedPkg', but active screen is '$livePkg'. Resume aborted for safety."
            val mismatchOutcome = TaskStepOutcome(
                stepIndex = active.remainingSteps.firstOrNull()?.index ?: (active.completedSteps.size + 1),
                action = active.remainingSteps.firstOrNull()?.action ?: PlannedTaskAction.READ_SCREEN,
                description = "Resume verification",
                status = TaskStepStatus.FAILED,
                verified = false,
                packageName = livePkg,
                resultCode = ScreenInteractionStatus.SCREEN_CHANGED.name,
                message = mismatchSummary
            )
            taskContextStore.failTask(mismatchSummary, mismatchOutcome)
            return TaskExecutionReport(
                taskId = active.taskId,
                objective = active.originalRequest,
                status = TaskExecutionStatus.FAILED,
                finalVerificationStatus = FinalVerificationStatus.PARTIALLY_COMPLETED,
                completedSteps = active.completedSteps,
                failedStep = mismatchOutcome,
                skippedSteps = active.remainingSteps,
                correctionsUsed = active.recoveryAttempts,
                verificationFailureReason = mismatchSummary,
                summary = mismatchSummary
            )
        }

        if (!isRunning.compareAndSet(false, true)) {
            return TaskExecutionReport(
                taskId = active.taskId,
                objective = active.originalRequest,
                status = TaskExecutionStatus.ALREADY_RUNNING,
                completedSteps = active.completedSteps,
                skippedSteps = active.remainingSteps,
                summary = "Cannot resume task because an execution loop is already running."
            )
        }

        val handle = CancellationHandle(active.taskId)
        activeHandle.set(handle)
        val synthesizedPlan = UiTaskPlan(
            id = active.taskId,
            objective = active.originalRequest,
            steps = active.plannedSteps,
            createdAtMs = active.createdAtMs,
            expectedFinalPackage = active.expectedFinalPackage.ifBlank { expectedPkg },
            expectedFinalElement = active.expectedFinalElement
        )
        return try {
            taskContextStore.transitionTo(TaskLifecycleState.RUNNING)
            runLoopUnderActiveLock(
                plan = synthesizedPlan,
                handle = handle,
                taskId = active.taskId,
                objective = active.originalRequest,
                initialSteps = active.remainingSteps,
                initialCompleted = active.completedSteps,
                initialTrackedPackage = livePkg.ifBlank { active.lastVerifiedPackage },
                initialExecutedSignatures = active.completedSteps.map { "${it.action.toolName}|${it.description}" },
                initialTotalExecuted = active.completedSteps.size,
                initialCorrectionsUsed = active.recoveryAttempts,
                announceTask = false,
                onProgress = onProgress
            )
        } finally {
            activeHandle.set(null)
            isRunning.set(false)
        }
    }

    suspend fun resumeIfPending(
        pendingConfirmationId: String,
        confirmedResult: ToolCallExecutionResult,
        onProgress: suspend (TaskProgressEvent) -> Unit = {}
    ): TaskExecutionReport? {
        val continuation = synchronized(continuationLock) {
            val current = pendingContinuation ?: return null
            if (current.pendingConfirmationId != pendingConfirmationId) {
                return null
            }
            pendingContinuation = null
            current
        }

        if (!isRunning.compareAndSet(false, true)) {
            return TaskExecutionReport(
                taskId = continuation.taskId,
                objective = continuation.objective,
                status = TaskExecutionStatus.ALREADY_RUNNING,
                completedSteps = continuation.completedSteps,
                skippedSteps = continuation.remainingSteps,
                summary = "Cannot resume task because another task is already running."
            )
        }

        if (taskContextStore.getActiveContext()?.state == TaskLifecycleState.AWAITING_CONFIRMATION) {
            taskContextStore.transitionTo(TaskLifecycleState.RUNNING)
        }

        val handle = CancellationHandle(continuation.taskId)
        activeHandle.set(handle)
        return try {
            coroutineScope {
                handle.job = coroutineContext[Job]
                if (handle.cancelled.get()) {
                    taskContextStore.cancelTask("Task cancelled during confirmation resumption.")
                    throw CancellationException("Task cancelled")
                }
                if (!confirmedResult.ok) {
                    val resultCode = confirmedResult.payload["result_code"]?.jsonPrimitive?.content.orEmpty()
                    val pkg = confirmedResult.payload["package_name"]?.jsonPrimitive?.content.orEmpty()
                    val failedOutcome = TaskStepOutcome(
                        stepIndex = continuation.haltedStep.index,
                        action = continuation.haltedStep.action,
                        description = continuation.haltedStep.description,
                        status = TaskStepStatus.FAILED,
                        verified = false,
                        packageName = pkg,
                        resultCode = resultCode,
                        message = confirmedResult.summary
                    )
                    taskContextStore.failTask(confirmedResult.summary, failedOutcome)
                    onProgress(TaskProgressEvent.StepFinished(failedOutcome, continuation.completedSteps.size + 1 + continuation.remainingSteps.size))
                    return@coroutineScope buildReport(
                        taskId = continuation.taskId,
                        objective = continuation.objective,
                        status = TaskExecutionStatus.FAILED,
                        finalVerificationStatus = if (continuation.completedSteps.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                        completedSteps = continuation.completedSteps,
                        failedStep = failedOutcome,
                        skippedSteps = continuation.remainingSteps,
                        correctionsUsed = continuation.correctionsUsed
                    )
                }

                // Re-read and independently verify screen state after confirmed UI-changing step
                val verified = verifyStepOutcome(
                    handle = handle,
                    step = continuation.haltedStep,
                    toolResult = confirmedResult,
                    previousTrackedPackage = continuation.trackedPackage,
                    preActionSnapshot = continuation.preActionSnapshot
                )
                if (verified.errorOutcome != null) {
                    val failedOutcome = verified.errorOutcome
                    taskContextStore.failTask(failedOutcome.message, failedOutcome)
                    onProgress(TaskProgressEvent.StepFinished(failedOutcome, continuation.completedSteps.size + 1 + continuation.remainingSteps.size))
                    return@coroutineScope buildReport(
                        taskId = continuation.taskId,
                        objective = continuation.objective,
                        status = TaskExecutionStatus.FAILED,
                        finalVerificationStatus = if (continuation.completedSteps.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                        completedSteps = continuation.completedSteps,
                        failedStep = failedOutcome,
                        skippedSteps = continuation.remainingSteps,
                        correctionsUsed = continuation.correctionsUsed
                    )
                }

                val confirmedOutcome = TaskStepOutcome(
                    stepIndex = continuation.haltedStep.index,
                    action = continuation.haltedStep.action,
                    description = continuation.haltedStep.description,
                    status = TaskStepStatus.COMPLETED,
                    verified = true,
                    packageName = verified.trackedPackage,
                    resultCode = confirmedResult.payload["result_code"]?.jsonPrimitive?.content.orEmpty().ifBlank { "SUCCESS" },
                    message = confirmedResult.summary,
                    verificationAttempts = verified.verificationAttempts
                )
                taskContextStore.onStepCompleted(confirmedOutcome, verified.trackedPackage)
                val updatedCompleted = continuation.completedSteps + confirmedOutcome
                onProgress(TaskProgressEvent.StepFinished(confirmedOutcome, updatedCompleted.size + continuation.remainingSteps.size))

                val plan = UiTaskPlan(
                    id = continuation.taskId,
                    objective = continuation.objective,
                    steps = continuation.completedSteps.map { PlannedTaskStep(it.stepIndex, it.action, it.description, emptyMap()) } +
                        continuation.haltedStep + continuation.remainingSteps,
                    createdAtMs = nowMs(),
                    expectedFinalPackage = continuation.expectedFinalPackage,
                    expectedFinalElement = continuation.expectedFinalElement
                )

                if (continuation.remainingSteps.isEmpty()) {
                    val goalOutcome = evaluateFinalGoal(handle, continuation.objective, plan)
                    if (!goalOutcome.isVerified) {
                        val failRep = buildReport(
                            taskId = continuation.taskId,
                            objective = continuation.objective,
                            status = TaskExecutionStatus.FAILED,
                            finalVerificationStatus = goalOutcome.status,
                            completedSteps = updatedCompleted,
                            skippedSteps = emptyList(),
                            correctionsUsed = continuation.correctionsUsed,
                            verificationFailureReason = goalOutcome.failureReason
                        )
                        taskContextStore.failTask(failRep.summary)
                        return@coroutineScope failRep
                    }
                    val finalRep = buildReport(
                        taskId = continuation.taskId,
                        objective = continuation.objective,
                        status = TaskExecutionStatus.SUCCEEDED,
                        finalVerificationStatus = FinalVerificationStatus.COMPLETED,
                        completedSteps = updatedCompleted,
                        correctionsUsed = continuation.correctionsUsed
                    )
                    taskContextStore.completeTask(finalRep.summary)
                    return@coroutineScope finalRep
                }

                runLoopInternal(
                    plan = plan,
                    handle = handle,
                    taskId = continuation.taskId,
                    objective = continuation.objective,
                    initialSteps = continuation.remainingSteps,
                    initialCompleted = updatedCompleted,
                    initialTrackedPackage = verified.trackedPackage,
                    initialExecutedSignatures = continuation.executedSignatures + continuation.haltedStep.signatureKey(),
                    initialTotalExecuted = continuation.totalStepsExecuted + 1,
                    initialCorrectionsUsed = continuation.correctionsUsed,
                    announceTask = false,
                    onProgress = onProgress
                )
            }
        } catch (e: CancellationException) {
            if (handle.cancelled.get()) {
                taskContextStore.cancelTask("Task cancelled by request.")
                buildReport(
                    taskId = continuation.taskId,
                    objective = continuation.objective,
                    status = TaskExecutionStatus.CANCELLED,
                    finalVerificationStatus = if (continuation.completedSteps.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = continuation.completedSteps,
                    skippedSteps = continuation.remainingSteps,
                    correctionsUsed = continuation.correctionsUsed
                )
            } else {
                taskContextStore.cancelTask("Task cancelled externally during resumption.")
                throw e
            }
        } finally {
            activeHandle.set(null)
            isRunning.set(false)
        }
    }

    private suspend fun runLoopUnderActiveLock(
        plan: UiTaskPlan,
        handle: CancellationHandle,
        taskId: String,
        objective: String,
        initialSteps: List<PlannedTaskStep>,
        initialCompleted: List<TaskStepOutcome>,
        initialTrackedPackage: String,
        initialExecutedSignatures: List<String>,
        initialTotalExecuted: Int,
        initialCorrectionsUsed: Int,
        announceTask: Boolean,
        onProgress: suspend (TaskProgressEvent) -> Unit
    ): TaskExecutionReport {
        activeHandle.set(handle)

        return try {
            coroutineScope {
                handle.job = coroutineContext[Job]
                if (handle.cancelled.get()) {
                    taskContextStore.cancelTask("Task cancelled by request.")
                    throw CancellationException("Task cancelled")
                }
                if (!taskContextStore.hasActiveTask()) {
                    return@coroutineScope buildReport(
                        taskId = taskId,
                        objective = objective,
                        status = TaskExecutionStatus.CANCELLED,
                        finalVerificationStatus = if (initialCompleted.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                        completedSteps = initialCompleted,
                        skippedSteps = initialSteps,
                        correctionsUsed = initialCorrectionsUsed
                    )
                }
                runLoopInternal(
                    plan = plan,
                    handle = handle,
                    taskId = taskId,
                    objective = objective,
                    initialSteps = initialSteps,
                    initialCompleted = initialCompleted,
                    initialTrackedPackage = initialTrackedPackage,
                    initialExecutedSignatures = initialExecutedSignatures,
                    initialTotalExecuted = initialTotalExecuted,
                    initialCorrectionsUsed = initialCorrectionsUsed,
                    announceTask = announceTask,
                    onProgress = onProgress
                )
            }
        } catch (e: CancellationException) {
            if (handle.cancelled.get()) {
                taskContextStore.cancelTask("Task cancelled by request.")
                buildReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.CANCELLED,
                    finalVerificationStatus = if (initialCompleted.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = initialCompleted,
                    skippedSteps = initialSteps,
                    correctionsUsed = initialCorrectionsUsed
                )
            } else {
                taskContextStore.cancelTask("Task cancelled externally during execution.")
                throw e
            }
        }
    }

    private suspend fun runLoopInternal(
        plan: UiTaskPlan,
        handle: CancellationHandle,
        taskId: String,
        objective: String,
        initialSteps: List<PlannedTaskStep>,
        initialCompleted: List<TaskStepOutcome>,
        initialTrackedPackage: String,
        initialExecutedSignatures: List<String>,
        initialTotalExecuted: Int,
        initialCorrectionsUsed: Int,
        announceTask: Boolean,
        onProgress: suspend (TaskProgressEvent) -> Unit
    ): TaskExecutionReport {
        if (initialSteps.size + initialCompleted.size > UiTaskPlanner.MAX_PLANNED_STEPS) {
            val limitRep = buildReport(
                taskId = taskId,
                objective = objective,
                status = TaskExecutionStatus.LIMIT_EXCEEDED,
                finalVerificationStatus = if (initialCompleted.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                completedSteps = initialCompleted,
                skippedSteps = initialSteps,
                correctionsUsed = initialCorrectionsUsed,
                overrideSummary = "Task exceeds the maximum limit of ${UiTaskPlanner.MAX_PLANNED_STEPS} steps."
            )
            taskContextStore.failTask(limitRep.summary)
            return limitRep
        }

        val queue = ArrayDeque(initialSteps)
        val completed = initialCompleted.toMutableList()
        val executedSignatures = initialExecutedSignatures.toMutableList()
        var trackedPackage = initialTrackedPackage
        var stepsExecutedCount = initialTotalExecuted
        var correctionsUsed = initialCorrectionsUsed

        if (announceTask) {
            onProgress(
                TaskProgressEvent.Announced(
                    taskId = taskId,
                    objective = objective,
                    totalSteps = queue.size,
                    message = "Starting task (${queue.size} step${if (queue.size == 1) "" else "s"}): $objective"
                )
            )
        }

        while (queue.isNotEmpty()) {
            if (handle.pauseRequested.get()) {
                val remaining = queue.toList()
                val pauseRep = buildReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.PAUSED,
                    finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = completed,
                    skippedSteps = remaining,
                    correctionsUsed = correctionsUsed
                )
                taskContextStore.pauseTask("Task paused by request.")
                return pauseRep
            }

            if (handle.cancelled.get() || !currentCoroutineContext().isActive) {
                val cancelRep = buildReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.CANCELLED,
                    finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = completed,
                    skippedSteps = queue.toList(),
                    correctionsUsed = correctionsUsed
                )
                taskContextStore.cancelTask(cancelRep.summary)
                return cancelRep
            }

            if (stepsExecutedCount >= UiTaskPlanner.MAX_PLANNED_STEPS) {
                val limitRep = buildReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.LIMIT_EXCEEDED,
                    finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = completed,
                    skippedSteps = queue.toList(),
                    correctionsUsed = correctionsUsed,
                    overrideSummary = "Stopped after reaching the maximum limit of ${UiTaskPlanner.MAX_PLANNED_STEPS} steps."
                )
                taskContextStore.failTask(limitRep.summary)
                return limitRep
            }

            val step = queue.removeFirst()
            val stepSig = step.signatureKey()
            val duplicateConsecutive = executedSignatures.lastOrNull() == stepSig
            val excessiveNonScroll = step.action != PlannedTaskAction.SCROLL_UI &&
                executedSignatures.count { it == stepSig } >= 2
            if (duplicateConsecutive || excessiveNonScroll) {
                val loopFailure = TaskStepOutcome(
                    stepIndex = step.index,
                    action = step.action,
                    description = step.description,
                    status = TaskStepStatus.FAILED,
                    verified = false,
                    packageName = trackedPackage,
                    resultCode = TaskPlanErrorCode.DUPLICATE_ACTION.name,
                    message = "Duplicate/looping action prevented before executing step ${step.index} (${step.description})."
                )
                onProgress(TaskProgressEvent.StepFinished(loopFailure, completed.size + 1 + queue.size))
                val failRep = buildReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.FAILED,
                    finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = completed,
                    failedStep = loopFailure,
                    skippedSteps = queue.toList(),
                    correctionsUsed = correctionsUsed
                )
                taskContextStore.failTask(failRep.summary, loopFailure)
                return failRep
            }

            val plannedTotal = completed.size + 1 + queue.size
            taskContextStore.onStepStarted(step, queue.toList())
            onProgress(
                TaskProgressEvent.StepStarted(
                    stepIndex = step.index,
                    totalSteps = plannedTotal,
                    step = step,
                    message = "Step ${step.index}/$plannedTotal: ${step.description}…"
                )
            )

            // Wait boundedly for loading screens or transient null/empty roots before executing UI steps,
            // and capture pre-action immutable screen snapshot for independent change verification.
            var preActionSnapshot: ScreenSnapshot? = null
            if (step.action in setOf(
                    PlannedTaskAction.FIND_UI_ELEMENT,
                    PlannedTaskAction.CLICK_UI_ELEMENT,
                    PlannedTaskAction.TYPE_TEXT,
                    PlannedTaskAction.SCROLL_UI
                )
            ) {
                when (val initialScreen = toolExecutor.readScreenRaw()) {
                    is ScreenReadResult.Success -> {
                        if (!initialScreen.snapshot.isLoadingState()) {
                            preActionSnapshot = initialScreen.snapshot
                        } else {
                            when (
                                val waitOutcome = UiWaitPolicy.waitForCondition(
                                    timeoutMs = waitConfig.loadingTimeoutMs,
                                    pollIntervalMs = waitConfig.pollIntervalMs,
                                    maxPolls = waitConfig.maxPolls,
                                    nowMs = nowMs,
                                    isCancelled = { handle.cancelled.get() },
                                    requireNonLoading = true,
                                    readFresh = { toolExecutor.readScreenRaw() },
                                    condition = { true }
                                )
                            ) {
                                is UiWaitOutcome.Satisfied -> {
                                    preActionSnapshot = waitOutcome.snapshot
                                }
                                is UiWaitOutcome.ServiceDisconnected -> {
                                    val dcFail = TaskStepOutcome(
                                        stepIndex = step.index,
                                        action = step.action,
                                        description = step.description,
                                        status = TaskStepStatus.FAILED,
                                        verified = false,
                                        packageName = trackedPackage,
                                        resultCode = ScreenInteractionStatus.SERVICE_DISCONNECTED.name,
                                        message = waitOutcome.message
                                    )
                                    onProgress(TaskProgressEvent.StepFinished(dcFail, plannedTotal))
                                    val failRep = buildReport(
                                        taskId = taskId,
                                        objective = objective,
                                        status = TaskExecutionStatus.FAILED,
                                        finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                                        completedSteps = completed,
                                        failedStep = dcFail,
                                        skippedSteps = queue.toList(),
                                        correctionsUsed = correctionsUsed
                                    )
                                    taskContextStore.failTask(failRep.summary, dcFail)
                                    return failRep
                                }
                                is UiWaitOutcome.TimedOut -> {
                                    val timeoutFail = TaskStepOutcome(
                                        stepIndex = step.index,
                                        action = step.action,
                                        description = step.description,
                                        status = TaskStepStatus.FAILED,
                                        verified = false,
                                        packageName = waitOutcome.lastSnapshot?.packageName.orEmpty().ifBlank { trackedPackage },
                                        resultCode = if (waitOutcome.sawLoading) "LOADING_TIMEOUT" else ScreenInteractionStatus.ACTION_FAILED.name,
                                        message = waitOutcome.reason
                                    )
                                    onProgress(TaskProgressEvent.StepFinished(timeoutFail, plannedTotal))
                                    val failRep = buildReport(
                                        taskId = taskId,
                                        objective = objective,
                                        status = TaskExecutionStatus.FAILED,
                                        finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                                        completedSteps = completed,
                                        failedStep = timeoutFail,
                                        skippedSteps = queue.toList(),
                                        correctionsUsed = correctionsUsed
                                    )
                                    taskContextStore.failTask(failRep.summary, timeoutFail)
                                    return failRep
                                }
                            }
                        }
                    }
                    is ScreenReadResult.Error -> {
                        if (initialScreen.code == ScreenReadErrorCode.UNAVAILABLE_NULL_ROOT ||
                            initialScreen.code == ScreenReadErrorCode.EMPTY_SCREEN_TREE
                        ) {
                            when (
                                val waitOutcome = UiWaitPolicy.waitForCondition(
                                    timeoutMs = waitConfig.loadingTimeoutMs,
                                    pollIntervalMs = waitConfig.pollIntervalMs,
                                    maxPolls = waitConfig.maxPolls,
                                    nowMs = nowMs,
                                    isCancelled = { handle.cancelled.get() },
                                    requireNonLoading = true,
                                    readFresh = { toolExecutor.readScreenRaw() },
                                    condition = { true }
                                )
                            ) {
                                is UiWaitOutcome.Satisfied -> {
                                    preActionSnapshot = waitOutcome.snapshot
                                }
                                is UiWaitOutcome.ServiceDisconnected -> {
                                    val dcFail = TaskStepOutcome(
                                        stepIndex = step.index,
                                        action = step.action,
                                        description = step.description,
                                        status = TaskStepStatus.FAILED,
                                        verified = false,
                                        packageName = trackedPackage,
                                        resultCode = ScreenInteractionStatus.SERVICE_DISCONNECTED.name,
                                        message = waitOutcome.message
                                    )
                                    onProgress(TaskProgressEvent.StepFinished(dcFail, plannedTotal))
                                    val failRep = buildReport(
                                        taskId = taskId,
                                        objective = objective,
                                        status = TaskExecutionStatus.FAILED,
                                        finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                                        completedSteps = completed,
                                        failedStep = dcFail,
                                        skippedSteps = queue.toList(),
                                        correctionsUsed = correctionsUsed
                                    )
                                    taskContextStore.failTask(failRep.summary, dcFail)
                                    return failRep
                                }
                                is UiWaitOutcome.TimedOut -> {
                                    val timeoutFail = TaskStepOutcome(
                                        stepIndex = step.index,
                                        action = step.action,
                                        description = step.description,
                                        status = TaskStepStatus.FAILED,
                                        verified = false,
                                        packageName = trackedPackage,
                                        resultCode = ScreenInteractionStatus.ACTION_FAILED.name,
                                        message = waitOutcome.reason
                                    )
                                    onProgress(TaskProgressEvent.StepFinished(timeoutFail, plannedTotal))
                                    val failRep = buildReport(
                                        taskId = taskId,
                                        objective = objective,
                                        status = TaskExecutionStatus.FAILED,
                                        finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                                        completedSteps = completed,
                                        failedStep = timeoutFail,
                                        skippedSteps = queue.toList(),
                                        correctionsUsed = correctionsUsed
                                    )
                                    taskContextStore.failTask(failRep.summary, timeoutFail)
                                    return failRep
                                }
                            }
                        }
                    }
                }
            }

            val effectiveArgs = buildEffectiveStepArgs(step, trackedPackage)
            val toolResult = toolExecutor.execute(step.action.toolName, effectiveArgs) { eff ->
                onProgress(TaskProgressEvent.SideEffect(eff))
            }

            if (handle.cancelled.get()) {
                val cancelRep = buildReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.CANCELLED,
                    finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = completed,
                    skippedSteps = queue.toList(),
                    correctionsUsed = correctionsUsed
                )
                taskContextStore.cancelTask(cancelRep.summary)
                return cancelRep
            }

            // Confirmation gate triggered -> pause immediately and require exact confirmation
            if (toolResult.isConfirmationRequired()) {
                val pendingId = toolResult.payload["pending_id"]?.jsonPrimitive?.content.orEmpty()
                val actionDesc = toolResult.payload["action"]?.jsonPrimitive?.content.orEmpty()
                    .ifBlank { step.description }
                val haltedOutcome = TaskStepOutcome(
                    stepIndex = step.index,
                    action = step.action,
                    description = step.description,
                    status = TaskStepStatus.AWAITING_CONFIRMATION,
                    verified = false,
                    packageName = trackedPackage,
                    resultCode = ToolConfirmationPolicy.STATUS_CONFIRMATION_REQUIRED,
                    message = toolResult.summary
                )
                taskContextStore.onAwaitingConfirmation(pendingId, actionDesc)
                onProgress(TaskProgressEvent.StepFinished(haltedOutcome, plannedTotal))
                synchronized(continuationLock) {
                    pendingContinuation = PendingTaskContinuation(
                        taskId = taskId,
                        objective = objective,
                        pendingConfirmationId = pendingId,
                        haltedStep = step,
                        completedSteps = completed.toList(),
                        remainingSteps = queue.toList(),
                        trackedPackage = trackedPackage,
                        executedSignatures = executedSignatures.toList(),
                        totalStepsExecuted = stepsExecutedCount,
                        correctionsUsed = correctionsUsed,
                        expectedFinalPackage = plan.expectedFinalPackage,
                        expectedFinalElement = plan.expectedFinalElement,
                        preActionSnapshot = TaskContextSanitizer.sanitizeSnapshot(preActionSnapshot)
                    )
                }
                return TaskExecutionReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.AWAITING_CONFIRMATION,
                    finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = completed.toList(),
                    failedStep = haltedOutcome,
                    skippedSteps = queue.toList(),
                    correctionsUsed = correctionsUsed,
                    pendingConfirmationId = pendingId,
                    pendingConfirmationDescription = actionDesc,
                    summary = buildReportSummary(
                        status = TaskExecutionStatus.AWAITING_CONFIRMATION,
                        finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                        objective = objective,
                        completedSteps = completed,
                        failedStep = haltedOutcome,
                        skippedSteps = queue.toList(),
                        correctionsUsed = correctionsUsed,
                        verificationFailureReason = null
                    )
                )
            }

            if (toolResult.ok) {
                // Independent Action Verification: verify actual state change on fresh hierarchy
                val verification = verifyStepOutcome(
                    handle = handle,
                    step = step,
                    toolResult = toolResult,
                    previousTrackedPackage = trackedPackage,
                    preActionSnapshot = preActionSnapshot
                )
                if (verification.errorOutcome == null) {
                    trackedPackage = verification.trackedPackage
                    executedSignatures.add(stepSig)
                    stepsExecutedCount += 1

                    val rawText = (step.args["text"] as? JsonPrimitive)?.content.orEmpty()
                    val isSensitive = step.action == PlannedTaskAction.TYPE_TEXT &&
                        (SensitiveUiActionPolicy.isBlockedCredentialText(step.description, rawText) ||
                            (rawText.length in 4..6 && rawText.all { it.isDigit() }))
                    val sanitizedSummary = if (isSensitive && rawText.isNotBlank()) {
                        toolResult.summary.replace(rawText, "[REDACTED]")
                    } else {
                        toolResult.summary
                    }

                    val outcome = TaskStepOutcome(
                        stepIndex = step.index,
                        action = step.action,
                        description = step.description,
                        status = TaskStepStatus.COMPLETED,
                        verified = true,
                        packageName = trackedPackage,
                        resultCode = toolResult.payload["result_code"]?.jsonPrimitive?.content.orEmpty().ifBlank { "SUCCESS" },
                        message = sanitizedSummary,
                        verificationAttempts = verification.verificationAttempts
                    )
                    completed.add(outcome)
                    val snap = (toolExecutor.readScreenRaw() as? ScreenReadResult.Success)?.snapshot
                    taskContextStore.onStepCompleted(outcome, trackedPackage, snap)
                    onProgress(TaskProgressEvent.StepFinished(outcome, plannedTotal))
                    continue
                } else {
                    val verifyFailure = verification.errorOutcome
                    onProgress(TaskProgressEvent.StepFinished(verifyFailure, plannedTotal))
                    val failRep = buildReport(
                        taskId = taskId,
                        objective = objective,
                        status = TaskExecutionStatus.FAILED,
                        finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                        completedSteps = completed,
                        failedStep = verifyFailure,
                        skippedSteps = queue.toList(),
                        correctionsUsed = correctionsUsed,
                        verificationFailureReason = verifyFailure.message
                    )
                    taskContextStore.failTask(failRep.summary, verifyFailure)
                    return failRep
                }
            }

            // Step failed -> evaluate bounded non-duplicate correction ONLY if eligible & corrector supplied
            val resultCode = toolResult.payload["result_code"]?.jsonPrimitive?.content.orEmpty()
            val pkg = toolResult.payload["package_name"]?.jsonPrimitive?.content.orEmpty()
            val rawFailText = (step.args["text"] as? JsonPrimitive)?.content.orEmpty()
            val isFailSensitive = step.action == PlannedTaskAction.TYPE_TEXT &&
                (SensitiveUiActionPolicy.isBlockedCredentialText(step.description, rawFailText) ||
                    (rawFailText.length in 4..6 && rawFailText.all { it.isDigit() }))
            val sanitizedFailSummary = if (isFailSensitive && rawFailText.isNotBlank()) {
                toolResult.summary.replace(rawFailText, "[REDACTED]")
            } else {
                toolResult.summary
            }
            val failureOutcome = TaskStepOutcome(
                stepIndex = step.index,
                action = step.action,
                description = step.description,
                status = TaskStepStatus.FAILED,
                verified = false,
                packageName = pkg,
                resultCode = resultCode,
                message = sanitizedFailSummary
            )
            executedSignatures.add(stepSig)
            stepsExecutedCount += 1

            val correction = attemptBoundedCorrection(
                failedStep = step,
                failureOutcome = failureOutcome,
                executedSignatures = executedSignatures,
                remainingSteps = queue.toList(),
                correctionsUsed = correctionsUsed,
                totalStepsSoFar = completed.size + 1 + queue.size
            )
            if (correction != null) {
                correctionsUsed += 1
                val updatedReplacement = correction.copy(index = step.index)
                queue.addFirst(updatedReplacement)
                taskContextStore.onRecoveryAttempted(failureOutcome, updatedReplacement)
                onProgress(
                    TaskProgressEvent.CorrectionApplied(
                        correctionNumber = correctionsUsed,
                        maxCorrections = UiTaskPlanner.MAX_PLAN_CORRECTIONS,
                        reason = failureOutcome.message,
                        replacementStep = updatedReplacement
                    )
                )
                continue
            }

            onProgress(TaskProgressEvent.StepFinished(failureOutcome, plannedTotal))
            val finalFailRep = buildReport(
                taskId = taskId,
                objective = objective,
                status = TaskExecutionStatus.FAILED,
                finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                completedSteps = completed,
                failedStep = failureOutcome,
                skippedSteps = queue.toList(),
                correctionsUsed = correctionsUsed
            )
            taskContextStore.failTask(finalFailRep.summary, failureOutcome)
            return finalFailRep
        }

        val goalOutcome = evaluateFinalGoal(handle, objective, plan)

        if (!goalOutcome.isVerified) {
            val failRep = buildReport(
                taskId = taskId,
                objective = objective,
                status = TaskExecutionStatus.FAILED,
                finalVerificationStatus = goalOutcome.status,
                completedSteps = completed,
                skippedSteps = emptyList(),
                correctionsUsed = correctionsUsed,
                verificationFailureReason = goalOutcome.failureReason
            )
            taskContextStore.failTask(failRep.summary)
            return failRep
        }

        val successRep = buildReport(
            taskId = taskId,
            objective = objective,
            status = TaskExecutionStatus.SUCCEEDED,
            finalVerificationStatus = FinalVerificationStatus.COMPLETED,
            completedSteps = completed,
            correctionsUsed = correctionsUsed
        )
        taskContextStore.completeTask(successRep.summary)
        return successRep
    }

    private suspend fun evaluateFinalGoal(
        handle: CancellationHandle,
        objective: String,
        plan: UiTaskPlan
    ): GoalVerificationResult {
        return when (val initialFinalCheck = toolExecutor.readScreenRaw()) {
            is ScreenReadResult.Success -> {
                if (!initialFinalCheck.snapshot.isLoadingState()) {
                    val activeVerifier = goalVerifier ?: DefaultTaskGoalVerifier()
                    activeVerifier.verifyGoal(objective, initialFinalCheck.snapshot, plan)
                } else {
                    when (
                        val settled = UiWaitPolicy.waitForCondition(
                            timeoutMs = waitConfig.loadingTimeoutMs,
                            idleTimeoutMs = waitConfig.loadingTimeoutMs,
                            pollIntervalMs = waitConfig.pollIntervalMs,
                            maxPolls = waitConfig.maxPolls,
                            nowMs = nowMs,
                            isCancelled = { handle.cancelled.get() },
                            requireNonLoading = true,
                            readFresh = { toolExecutor.readScreenRaw() },
                            condition = { true }
                        )
                    ) {
                        is UiWaitOutcome.Satisfied -> {
                            val activeVerifier = goalVerifier ?: DefaultTaskGoalVerifier()
                            activeVerifier.verifyGoal(objective, settled.snapshot, plan)
                        }
                        is UiWaitOutcome.ServiceDisconnected -> GoalVerificationResult(
                            status = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                            isVerified = false,
                            failureReason = "Final goal verification unavailable: ${settled.message}"
                        )
                        is UiWaitOutcome.TimedOut -> GoalVerificationResult(
                            status = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                            isVerified = false,
                            failureReason = "Final goal verification unavailable: ${settled.reason}"
                        )
                    }
                }
            }
            is ScreenReadResult.Error -> {
                if (initialFinalCheck.code == ScreenReadErrorCode.UNAVAILABLE_NULL_ROOT ||
                    initialFinalCheck.code == ScreenReadErrorCode.EMPTY_SCREEN_TREE
                ) {
                    when (
                        val settled = UiWaitPolicy.waitForCondition(
                            timeoutMs = waitConfig.loadingTimeoutMs,
                            idleTimeoutMs = waitConfig.loadingTimeoutMs,
                            pollIntervalMs = waitConfig.pollIntervalMs,
                            maxPolls = waitConfig.maxPolls,
                            nowMs = nowMs,
                            isCancelled = { handle.cancelled.get() },
                            requireNonLoading = true,
                            readFresh = { toolExecutor.readScreenRaw() },
                            condition = { true }
                        )
                    ) {
                        is UiWaitOutcome.Satisfied -> {
                            val activeVerifier = goalVerifier ?: DefaultTaskGoalVerifier()
                            activeVerifier.verifyGoal(objective, settled.snapshot, plan)
                        }
                        is UiWaitOutcome.ServiceDisconnected -> GoalVerificationResult(
                            status = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                            isVerified = false,
                            failureReason = "Final goal verification unavailable: ${settled.message}"
                        )
                        is UiWaitOutcome.TimedOut -> GoalVerificationResult(
                            status = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                            isVerified = false,
                            failureReason = "Final goal verification unavailable: ${settled.reason}"
                        )
                    }
                } else {
                    GoalVerificationResult(
                        status = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                        isVerified = false,
                        failureReason = "Final goal verification unavailable: ${initialFinalCheck.message}"
                    )
                }
            }
        }
    }

    private fun buildEffectiveStepArgs(
        step: PlannedTaskStep,
        trackedPackage: String
    ): Map<String, JsonElement> {
        val effective = step.args.toMutableMap()
        if (step.action in setOf(
                PlannedTaskAction.FIND_UI_ELEMENT,
                PlannedTaskAction.CLICK_UI_ELEMENT,
                PlannedTaskAction.TYPE_TEXT,
                PlannedTaskAction.SCROLL_UI
            )
        ) {
            val existingExpected = (effective["expected_package"] as? JsonPrimitive)?.content?.trim().orEmpty()
                .ifBlank { (effective["package_name"] as? JsonPrimitive)?.content?.trim().orEmpty() }
            val resolvedExpected = step.expectedPackage.ifBlank { existingExpected }.ifBlank { trackedPackage }
            if (resolvedExpected.isNotBlank()) {
                effective["expected_package"] = JsonPrimitive(resolvedExpected)
            }
        }
        return effective
    }

    private data class VerificationOutcome(
        val trackedPackage: String,
        val verificationAttempts: Int = 1,
        val errorOutcome: TaskStepOutcome? = null
    )

    private suspend fun verifyStepOutcome(
        handle: CancellationHandle,
        step: PlannedTaskStep,
        toolResult: ToolCallExecutionResult,
        previousTrackedPackage: String,
        preActionSnapshot: ScreenSnapshot?
    ): VerificationOutcome {
        val resultPkg = toolResult.payload["package_name"]?.jsonPrimitive?.content.orEmpty()

        if (handle.cancelled.get()) {
            throw CancellationException("Task cancelled during verification")
        }

        if (!step.verifyAfterAction) {
            return VerificationOutcome(
                trackedPackage = resultPkg.ifBlank { previousTrackedPackage },
                verificationAttempts = 1
            )
        }

        // Bounded verification: at most 2 verification attempts per action,
        // using condition-based polling instead of relying only on fixed delays.
        for (attempt in 1..2) {
            if (handle.cancelled.get()) {
                throw CancellationException("Task cancelled during verification attempt $attempt")
            }
            currentCoroutineContext().ensureActive()

            val expectedPkg = step.expectedPackage
                .ifBlank { preActionSnapshot?.packageName.orEmpty() }
                .ifBlank { previousTrackedPackage }

            val waitOutcome = UiWaitPolicy.waitForCondition(
                timeoutMs = waitConfig.loadingTimeoutMs,
                idleTimeoutMs = waitConfig.actionSettleTimeoutMs,
                pollIntervalMs = waitConfig.pollIntervalMs,
                maxPolls = waitConfig.maxPolls,
                nowMs = nowMs,
                isCancelled = { handle.cancelled.get() },
                requireNonLoading = true,
                readFresh = { toolExecutor.readScreenRaw() },
                condition = { postSnap ->
                    val pkgMismatch = expectedPkg.isNotBlank() &&
                        step.action != PlannedTaskAction.OPEN_APP &&
                        !postSnap.packageName.equals(expectedPkg, ignoreCase = true)
                    if (pkgMismatch) {
                        true
                    } else {
                        when (step.action) {
                            PlannedTaskAction.CLICK_UI_ELEMENT -> hasScreenOrTargetChanged(preActionSnapshot, postSnap, step)
                            PlannedTaskAction.SCROLL_UI -> hasScrollChanged(preActionSnapshot, postSnap)
                            PlannedTaskAction.TYPE_TEXT -> {
                                val rawText = (step.args["text"] as? JsonPrimitive)?.content.orEmpty()
                                val verifiedField = toolResult.payload["verified"]?.jsonPrimitive?.booleanOrNull ?: false
                                verifiedField || isFieldVerified(step, postSnap, rawText)
                            }
                            PlannedTaskAction.OPEN_APP -> {
                                val isExpectedPkg = step.expectedPackage.isNotBlank() && postSnap.packageName.equals(step.expectedPackage, ignoreCase = true)
                                val isNotLauncher = !postSnap.packageName.contains("launcher", ignoreCase = true) && postSnap.packageName != "android"
                                isExpectedPkg || isNotLauncher
                            }
                            else -> true
                        }
                    }
                }
            )

            when (waitOutcome) {
                is UiWaitOutcome.ServiceDisconnected -> {
                    return VerificationOutcome(
                        trackedPackage = previousTrackedPackage,
                        verificationAttempts = attempt,
                        errorOutcome = TaskStepOutcome(
                            stepIndex = step.index,
                            action = step.action,
                            description = step.description,
                            status = TaskStepStatus.FAILED,
                            verified = false,
                            packageName = resultPkg,
                            resultCode = ScreenInteractionStatus.SERVICE_DISCONNECTED.name,
                            message = "Post-action screen verification failed (attempt $attempt): ${waitOutcome.message}",
                            verificationAttempts = attempt
                        )
                    )
                }
                is UiWaitOutcome.Satisfied -> {
                    val postSnap = waitOutcome.snapshot
                    if (expectedPkg.isNotBlank() &&
                        step.action != PlannedTaskAction.OPEN_APP &&
                        !postSnap.packageName.equals(expectedPkg, ignoreCase = true)
                    ) {
                        return VerificationOutcome(
                            trackedPackage = postSnap.packageName,
                            verificationAttempts = attempt,
                            errorOutcome = TaskStepOutcome(
                                stepIndex = step.index,
                                action = step.action,
                                description = step.description,
                                status = TaskStepStatus.FAILED,
                                verified = false,
                                packageName = postSnap.packageName,
                                resultCode = ScreenInteractionStatus.SCREEN_CHANGED.name,
                                message = "Verification failed: expected package '$expectedPkg', but active screen is '${postSnap.packageName}'.",
                                verificationAttempts = attempt
                            )
                        )
                    }
                    return VerificationOutcome(
                        trackedPackage = postSnap.packageName.ifBlank { resultPkg.ifBlank { previousTrackedPackage } },
                        verificationAttempts = attempt
                    )
                }
                is UiWaitOutcome.TimedOut -> {
                    if (waitOutcome.lastError != null && !waitOutcome.sawLoading) {
                        val lastErr = waitOutcome.lastError
                        val errCode = when (lastErr.code) {
                            ScreenReadErrorCode.SERVICE_NOT_CONNECTED,
                            ScreenReadErrorCode.SERVICE_ENABLED_BUT_DISCONNECTED -> ScreenInteractionStatus.SERVICE_DISCONNECTED.name
                            else -> ScreenInteractionStatus.ACTION_FAILED.name
                        }
                        return VerificationOutcome(
                            trackedPackage = previousTrackedPackage,
                            verificationAttempts = attempt,
                            errorOutcome = TaskStepOutcome(
                                stepIndex = step.index,
                                action = step.action,
                                description = step.description,
                                status = TaskStepStatus.FAILED,
                                verified = false,
                                packageName = resultPkg,
                                resultCode = errCode,
                                message = "Post-action screen verification failed (attempt $attempt): ${lastErr.message}",
                                verificationAttempts = attempt
                            )
                        )
                    }

                    if (waitOutcome.sawLoading || attempt == 2) {
                        val lastPkg = waitOutcome.lastSnapshot?.packageName.orEmpty()
                            .ifBlank { resultPkg.ifBlank { previousTrackedPackage } }
                        val failureMsg = if (waitOutcome.sawLoading) {
                            "Verification timed out while screen remained in loading state (${waitOutcome.loadingIndicators.joinToString(", ")})."
                        } else {
                            when (step.action) {
                                PlannedTaskAction.CLICK_UI_ELEMENT ->
                                    "Verification failed: Click on '${step.description}' was performed, but screen and target state remained unchanged after 2 verification attempts."
                                PlannedTaskAction.SCROLL_UI -> {
                                    val dir = (step.args["direction"] as? JsonPrimitive)?.content ?: "DOWN"
                                    "Verification failed: scroll $dir action was performed, but visible container content and scroll state remained unchanged after 2 verification attempts."
                                }
                                PlannedTaskAction.TYPE_TEXT ->
                                    "Verification failed: target input field did not reflect entered text after 2 verification attempts."
                                PlannedTaskAction.OPEN_APP -> {
                                    val appName = (step.args["name"] as? JsonPrimitive)?.content.orEmpty()
                                    "Verification failed: application '$appName' did not appear in the foreground after 2 verification attempts."
                                }
                                else -> "Verification failed after $attempt attempts: ${waitOutcome.reason}"
                            }
                        }
                        return VerificationOutcome(
                            trackedPackage = previousTrackedPackage,
                            verificationAttempts = attempt,
                            errorOutcome = TaskStepOutcome(
                                stepIndex = step.index,
                                action = step.action,
                                description = step.description,
                                status = TaskStepStatus.FAILED,
                                verified = false,
                                packageName = lastPkg,
                                resultCode = if (waitOutcome.sawLoading) "LOADING_TIMEOUT" else ScreenInteractionStatus.ACTION_FAILED.name,
                                message = failureMsg,
                                verificationAttempts = attempt
                            )
                        )
                    }
                }
            }
        }

        return VerificationOutcome(
            trackedPackage = resultPkg.ifBlank { previousTrackedPackage },
            verificationAttempts = 2
        )
    }

    private fun hasScreenOrTargetChanged(
        pre: ScreenSnapshot?,
        post: ScreenSnapshot,
        step: PlannedTaskStep
    ): Boolean {
        if (pre == null) return post.nodes.isNotEmpty()
        if (!pre.packageName.equals(post.packageName, ignoreCase = true)) return true
        if (pre.nodes.size != post.nodes.size) return true

        for (i in pre.nodes.indices) {
            val p = pre.nodes[i]
            val q = post.nodes[i]
            if (p.text != q.text ||
                p.hintText != q.hintText ||
                p.contentDescription != q.contentDescription ||
                p.viewId != q.viewId ||
                p.bounds != q.bounds ||
                p.isEnabled != q.isEnabled ||
                p.isClickable != q.isClickable ||
                p.isFocused != q.isFocused ||
                p.isEditable != q.isEditable ||
                p.depth != q.depth
            ) {
                return true
            }
        }
        return false
    }

    private fun hasScrollChanged(
        pre: ScreenSnapshot?,
        post: ScreenSnapshot
    ): Boolean {
        if (pre == null) return post.nodes.isNotEmpty()
        if (!pre.packageName.equals(post.packageName, ignoreCase = true)) return true
        if (pre.nodes.size != post.nodes.size) return true

        for (i in pre.nodes.indices) {
            val p = pre.nodes[i]
            val q = post.nodes[i]
            if (p.bounds != q.bounds || p.text != q.text || p.viewId != q.viewId) {
                return true
            }
        }
        return false
    }

    private fun isFieldVerified(
        step: PlannedTaskStep,
        post: ScreenSnapshot,
        expectedText: String
    ): Boolean {
        val targetViewId = (step.args["view_id"] as? JsonPrimitive)?.content.orEmpty()
        val targetHint = (step.args["hint"] as? JsonPrimitive)?.content.orEmpty()
        val targetDesc = (step.args["content_description"] as? JsonPrimitive)?.content.orEmpty()
        val targetText = (step.args["target_text"] as? JsonPrimitive)?.content.orEmpty()

        val matchedNode = post.nodes.firstOrNull { node ->
            (targetViewId.isNotBlank() && node.viewId.contains(targetViewId, ignoreCase = true)) ||
                (targetHint.isNotBlank() && node.hintText.equals(targetHint, ignoreCase = true)) ||
                (targetDesc.isNotBlank() && node.contentDescription.equals(targetDesc, ignoreCase = true)) ||
                (targetText.isNotBlank() && node.text.contains(targetText, ignoreCase = true))
        } ?: post.nodes.firstOrNull { it.isEditable }

        if (matchedNode == null) return false
        val isSensitive = matchedNode.isPassword ||
            SensitiveUiActionPolicy.isBlockedCredentialField(matchedNode) ||
            SensitiveUiActionPolicy.isBlockedCredentialText(targetHint, targetViewId, targetDesc) ||
            (expectedText.length in 4..6 && expectedText.all { it.isDigit() } && SensitiveUiActionPolicy.isBlockedCredentialText(targetHint, targetViewId))

        return if (isSensitive) {
            matchedNode.text.isNotEmpty() || matchedNode.isPassword
        } else {
            matchedNode.text == expectedText || matchedNode.text.contains(expectedText)
        }
    }

    private suspend fun attemptBoundedCorrection(
        failedStep: PlannedTaskStep,
        failureOutcome: TaskStepOutcome,
        executedSignatures: List<String>,
        remainingSteps: List<PlannedTaskStep>,
        correctionsUsed: Int,
        totalStepsSoFar: Int
    ): PlannedTaskStep? {
        val corrector = planCorrector ?: return null
        if (correctionsUsed >= UiTaskPlanner.MAX_PLAN_CORRECTIONS) return null
        if (totalStepsSoFar >= UiTaskPlanner.MAX_PLANNED_STEPS) return null

        // Never retry/correct fatal states, ambiguity, screen changes, credential blocks, or rejected input/scroll/click.
        val isRecoverableNotFound = failureOutcome.resultCode == ScreenInteractionStatus.NOT_FOUND.name &&
            (failedStep.action == PlannedTaskAction.FIND_UI_ELEMENT ||
                failedStep.action == PlannedTaskAction.CLICK_UI_ELEMENT)
        if (!isRecoverableNotFound) return null

        val snapshot = (toolExecutor.readScreenRaw() as? ScreenReadResult.Success)?.snapshot
        val rawProposed = corrector.proposeCorrection(
            failedStep = failedStep,
            failure = failureOutcome,
            screenSnapshot = snapshot,
            remainingSteps = remainingSteps
        ) ?: return null

        val validated = UiTaskPlanner.validateSingleStep(failedStep.index, rawProposed)
        if (validated !is UiTaskPlanner.StepValidation.Valid) return null

        val proposed = validated.step
        val proposedSig = proposed.signatureKey()
        // Never blindly retry the exact failed action or repeat an already-executed step.
        if (proposedSig == failedStep.signatureKey() || proposedSig in executedSignatures) {
            return null
        }
        return proposed
    }

    private fun buildReport(
        taskId: String,
        objective: String,
        status: TaskExecutionStatus,
        completedSteps: List<TaskStepOutcome>,
        finalVerificationStatus: FinalVerificationStatus = when (status) {
            TaskExecutionStatus.SUCCEEDED -> FinalVerificationStatus.COMPLETED
            TaskExecutionStatus.AWAITING_CONFIRMATION, TaskExecutionStatus.PAUSED ->
                if (completedSteps.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED
            TaskExecutionStatus.CANCELLED, TaskExecutionStatus.FAILED, TaskExecutionStatus.LIMIT_EXCEEDED ->
                if (completedSteps.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED
            TaskExecutionStatus.VERIFICATION_UNAVAILABLE -> FinalVerificationStatus.VERIFICATION_UNAVAILABLE
            TaskExecutionStatus.ALREADY_RUNNING -> FinalVerificationStatus.FAILED
        },
        failedStep: TaskStepOutcome? = null,
        skippedSteps: List<PlannedTaskStep> = emptyList(),
        correctionsUsed: Int = 0,
        verificationFailureReason: String? = null,
        pendingConfirmationId: String = "",
        pendingConfirmationDescription: String = "",
        overrideSummary: String? = null
    ): TaskExecutionReport {
        val summary = overrideSummary ?: buildReportSummary(
            status = status,
            finalVerificationStatus = finalVerificationStatus,
            objective = objective,
            completedSteps = completedSteps,
            failedStep = failedStep,
            skippedSteps = skippedSteps,
            correctionsUsed = correctionsUsed,
            verificationFailureReason = verificationFailureReason
        )
        return TaskExecutionReport(
            taskId = taskId,
            objective = objective,
            status = status,
            finalVerificationStatus = finalVerificationStatus,
            completedSteps = completedSteps.toList(),
            failedStep = failedStep,
            skippedSteps = skippedSteps.toList(),
            correctionsUsed = correctionsUsed,
            pendingConfirmationId = pendingConfirmationId,
            pendingConfirmationDescription = pendingConfirmationDescription,
            verificationFailureReason = verificationFailureReason,
            summary = summary
        )
    }

    private fun buildReportSummary(
        status: TaskExecutionStatus,
        finalVerificationStatus: FinalVerificationStatus,
        objective: String,
        completedSteps: List<TaskStepOutcome>,
        failedStep: TaskStepOutcome?,
        skippedSteps: List<PlannedTaskStep>,
        correctionsUsed: Int,
        verificationFailureReason: String?
    ): String {
        val sb = StringBuilder()
        if (status == TaskExecutionStatus.SUCCEEDED) {
            val total = completedSteps.size + skippedSteps.size
            sb.append("Completed task: $objective (${completedSteps.size}/$total verified steps). ")
        }
        sb.append("Requested goal: \"$objective\". ")
        sb.append("Final verification status: ${finalVerificationStatus.name}. ")
        if (completedSteps.isNotEmpty()) {
            sb.append("Verified completed steps (${completedSteps.size}): ${completedSteps.joinToString(" -> ") { "${it.stepIndex}. ${it.description}" }}. ")
        } else {
            sb.append("Verified completed steps: None. ")
        }
        if (failedStep != null) {
            sb.append("Failed step: Step ${failedStep.stepIndex} (${failedStep.description}): ${failedStep.message}. ")
        }
        if (skippedSteps.isNotEmpty()) {
            sb.append("Skipped steps (${skippedSteps.size}): ${skippedSteps.joinToString(", ") { it.description }}. ")
        }
        if (!verificationFailureReason.isNullOrBlank()) {
            sb.append("Reason: $verificationFailureReason. ")
        }
        if (correctionsUsed > 0) {
            sb.append("(Planning corrections used: $correctionsUsed/${UiTaskPlanner.MAX_PLAN_CORRECTIONS}). ")
        }
        return sb.toString().trim()
    }
}
