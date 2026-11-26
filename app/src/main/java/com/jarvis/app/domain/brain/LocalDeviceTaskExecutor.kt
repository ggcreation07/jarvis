package com.jarvis.app.domain.brain

import com.jarvis.app.data.ai.ToolCallExecutionResult
import com.jarvis.app.platform.DeviceActionId
import com.jarvis.app.platform.DeviceCapabilitiesSnapshot
import com.jarvis.app.platform.DeviceCapabilityRegistry
import com.jarvis.app.platform.ScreenInteractionStatus
import com.jarvis.app.platform.ScreenReadErrorCode
import com.jarvis.app.platform.ScreenReadResult
import com.jarvis.app.platform.ScreenSnapshot
import com.jarvis.app.platform.SensitiveUiActionPolicy
import com.jarvis.app.platform.UiWaitConfig
import com.jarvis.app.platform.UiWaitOutcome
import com.jarvis.app.platform.UiWaitPolicy
import com.jarvis.app.platform.VolumeStateSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LocalDeviceTaskExecutor(
    private val toolExecutor: ToolExecutor,
    private val capabilityRegistryProvider: () -> DeviceCapabilityRegistry? = { null },
    private val waitConfig: UiWaitConfig = UiWaitConfig(),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private class CancellationHandle(
        val taskId: String,
        val cancelled: AtomicBoolean = AtomicBoolean(false),
        val pauseRequested: AtomicBoolean = AtomicBoolean(false),
        @Volatile var job: Job? = null
    )

    private data class PausedLocalContinuation(
        val taskId: String,
        val objective: String,
        val allSteps: List<LocalTaskStep>,
        val completedSteps: List<TaskStepOutcome>,
        val remainingSteps: List<LocalTaskStep>,
        val trackedPackage: String,
        val executedSignatures: List<String>,
        val executedRecoveryKeys: Set<String>,
        val totalStepsExecuted: Int,
        val correctionsUsed: Int,
        val expectedFinalPackage: String,
        val expectedFinalElement: String,
        val lastVolumeBefore: VolumeStateSnapshot?,
        val lastVolumeDirection: String?,
        val lastFlashExpectedOn: Boolean?
    )

    private data class PendingLocalConfirmation(
        val taskId: String,
        val objective: String,
        val pendingConfirmationId: String,
        val allSteps: List<LocalTaskStep>,
        val haltedStep: LocalTaskStep,
        val completedSteps: List<TaskStepOutcome>,
        val remainingSteps: List<LocalTaskStep>,
        val trackedPackage: String,
        val executedSignatures: List<String>,
        val executedRecoveryKeys: Set<String>,
        val totalStepsExecuted: Int,
        val correctionsUsed: Int,
        val expectedFinalPackage: String,
        val expectedFinalElement: String,
        val preActionSnapshot: ScreenSnapshot?,
        val lastVolumeBefore: VolumeStateSnapshot?,
        val lastVolumeDirection: String?,
        val lastFlashExpectedOn: Boolean?
    )

    private data class StepVerifyResult(
        val trackedPackage: String,
        val verificationAttempts: Int = 1,
        val errorOutcome: TaskStepOutcome? = null
    )

    private val taskContextStore: TaskContextStore get() = toolExecutor.taskContextStore
    private val isRunning = AtomicBoolean(false)
    private val activeHandle = AtomicReference<CancellationHandle?>(null)
    private val stateLock = Any()
    private var pausedContinuation: PausedLocalContinuation? = null
    private var pendingConfirmation: PendingLocalConfirmation? = null

    fun isTaskRunning(): Boolean = isRunning.get()

    fun hasPausedTask(): Boolean = synchronized(stateLock) {
        pausedContinuation != null && taskContextStore.getActiveContext()?.state == TaskLifecycleState.PAUSED
    }

    fun pauseActiveTask(): Boolean {
        val handle = activeHandle.get() ?: return false
        handle.pauseRequested.set(true)
        return true
    }

    fun clearPendingContinuation() {
        synchronized(stateLock) {
            pendingConfirmation = null
            pausedContinuation = null
        }
    }

    fun cancelActiveTask(): Boolean {
        clearPendingContinuation()
        val hadActive = taskContextStore.hasActiveTask()
        taskContextStore.cancelTask("Active local automation task cancelled by user request.")
        val handle = activeHandle.get()
        if (handle != null) {
            handle.cancelled.set(true)
            handle.job?.cancel()
            return true
        }
        return hadActive
    }

    suspend fun executePlan(
        plan: LocalDeviceTaskPlan,
        onProgress: suspend (TaskProgressEvent) -> Unit = {}
    ): TaskExecutionReport {
        if (!isRunning.compareAndSet(false, true)) {
            return TaskExecutionReport(
                taskId = plan.id,
                objective = plan.objective,
                status = TaskExecutionStatus.ALREADY_RUNNING,
                completedSteps = emptyList(),
                skippedSteps = plan.steps.map { it.toPlannedTaskStep() },
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
                skippedSteps = plan.steps.map { it.toPlannedTaskStep() },
                summary = "Cannot start task '${plan.id}': active task '${current?.taskId}' is already in progress (${current?.state})."
            )
        }

        val handle = CancellationHandle(plan.id)
        activeHandle.set(handle)
        clearPendingContinuation()

        return try {
            taskContextStore.createTask(plan.toUiTaskPlan())
            taskContextStore.transitionTo(TaskLifecycleState.RUNNING)
            runLoopUnderActiveLock(
                handle = handle,
                taskId = plan.id,
                objective = plan.objective,
                allSteps = plan.steps,
                initialQueue = plan.steps,
                initialCompleted = emptyList(),
                initialTrackedPackage = "",
                initialExecutedSignatures = emptyList(),
                initialExecutedRecoveryKeys = emptySet(),
                initialTotalExecuted = 0,
                initialCorrectionsUsed = 0,
                expectedFinalPackage = plan.expectedFinalPackage,
                expectedFinalElement = plan.expectedFinalElement,
                initialVolumeBefore = null,
                initialVolumeDirection = null,
                initialFlashExpectedOn = null,
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
                summary = "No active local automation task to resume."
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

        val saved = synchronized(stateLock) {
            val cur = pausedContinuation
            if (cur == null || cur.taskId != active.taskId) null else cur
        } ?: return TaskExecutionReport(
            taskId = active.taskId,
            objective = active.originalRequest,
            status = TaskExecutionStatus.FAILED,
            finalVerificationStatus = FinalVerificationStatus.FAILED,
            completedSteps = active.completedSteps,
            skippedSteps = active.remainingSteps,
            summary = "Cannot resume task '${active.taskId}': local continuation state was cleared."
        )

        // Refresh device capabilities dynamically before resuming
        val capSnapshot = capabilityRegistryProvider()?.refresh()
        if (capSnapshot != null) {
            for (rem in saved.remainingSteps) {
                val reqCap = rem.requiredCapability ?: continue
                val status = capSnapshot.statusFor(reqCap)
                if (status != null && !status.isAvailable) {
                    val reason = "Cannot resume task: capability '${status.label}' is no longer available (${status.reason})."
                    val failOutcome = TaskStepOutcome(
                        stepIndex = rem.index,
                        action = rem.action.toPlannedTaskAction(),
                        description = rem.description,
                        status = TaskStepStatus.FAILED,
                        verified = false,
                        packageName = saved.trackedPackage,
                        resultCode = status.status.name,
                        message = reason
                    )
                    synchronized(stateLock) { pausedContinuation = null }
                    taskContextStore.failTask(reason, failOutcome)
                    return buildReport(
                        taskId = saved.taskId,
                        objective = saved.objective,
                        status = TaskExecutionStatus.FAILED,
                        finalVerificationStatus = if (saved.completedSteps.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                        completedSteps = saved.completedSteps,
                        failedStep = failOutcome,
                        skippedSteps = saved.remainingSteps.map { it.toPlannedTaskStep() },
                        correctionsUsed = saved.correctionsUsed
                    )
                }
            }
        }

        var resumedTrackedPackage = saved.trackedPackage
        val needsScreenCheck = saved.trackedPackage.isNotBlank() || saved.remainingSteps.any { it.usesAccessibilityScreen() }
        if (needsScreenCheck) {
            when (val screenCheck = toolExecutor.readScreenRaw()) {
                is ScreenReadResult.Error -> {
                    val failureSummary = "Failed to resume task: unable to read current screen (${screenCheck.message})."
                    synchronized(stateLock) { pausedContinuation = null }
                    taskContextStore.failTask(failureSummary)
                    return buildReport(
                        taskId = saved.taskId,
                        objective = saved.objective,
                        status = TaskExecutionStatus.FAILED,
                        finalVerificationStatus = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                        completedSteps = saved.completedSteps,
                        skippedSteps = saved.remainingSteps.map { it.toPlannedTaskStep() },
                        correctionsUsed = saved.correctionsUsed,
                        verificationFailureReason = failureSummary
                    )
                }
                is ScreenReadResult.Success -> {
                    val livePkg = screenCheck.snapshot.packageName
                    val expectedPkg = saved.trackedPackage.ifBlank {
                        saved.remainingSteps.firstOrNull()?.expectedPackage.orEmpty()
                    }
                    if (expectedPkg.isNotBlank() && !livePkg.equals(expectedPkg, ignoreCase = true)) {
                        val mismatchSummary =
                            "Screen context mismatch: Expected foreground package '$expectedPkg', but active screen is '$livePkg'. Resume aborted safely."
                        val nextStep = saved.remainingSteps.firstOrNull()
                        val mismatchOutcome = TaskStepOutcome(
                            stepIndex = nextStep?.index ?: (saved.completedSteps.size + 1),
                            action = nextStep?.action?.toPlannedTaskAction() ?: PlannedTaskAction.READ_SCREEN,
                            description = "Resume verification",
                            status = TaskStepStatus.FAILED,
                            verified = false,
                            packageName = livePkg,
                            resultCode = ScreenInteractionStatus.SCREEN_CHANGED.name,
                            message = mismatchSummary
                        )
                        synchronized(stateLock) { pausedContinuation = null }
                        taskContextStore.failTask(mismatchSummary, mismatchOutcome)
                        return buildReport(
                            taskId = saved.taskId,
                            objective = saved.objective,
                            status = TaskExecutionStatus.FAILED,
                            finalVerificationStatus = if (saved.completedSteps.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                            completedSteps = saved.completedSteps,
                            failedStep = mismatchOutcome,
                            skippedSteps = saved.remainingSteps.map { it.toPlannedTaskStep() },
                            correctionsUsed = saved.correctionsUsed,
                            verificationFailureReason = mismatchSummary
                        )
                    }
                    resumedTrackedPackage = livePkg.ifBlank { saved.trackedPackage }
                }
            }
        }

        if (!isRunning.compareAndSet(false, true)) {
            return TaskExecutionReport(
                taskId = saved.taskId,
                objective = saved.objective,
                status = TaskExecutionStatus.ALREADY_RUNNING,
                completedSteps = saved.completedSteps,
                skippedSteps = saved.remainingSteps.map { it.toPlannedTaskStep() },
                summary = "Cannot resume task because an execution loop is already running."
            )
        }

        synchronized(stateLock) {
            if (pausedContinuation?.taskId == saved.taskId) {
                pausedContinuation = null
            }
        }

        val handle = CancellationHandle(saved.taskId)
        activeHandle.set(handle)
        return try {
            taskContextStore.transitionTo(TaskLifecycleState.RUNNING)
            runLoopUnderActiveLock(
                handle = handle,
                taskId = saved.taskId,
                objective = saved.objective,
                allSteps = saved.allSteps,
                initialQueue = saved.remainingSteps,
                initialCompleted = saved.completedSteps,
                initialTrackedPackage = resumedTrackedPackage,
                initialExecutedSignatures = saved.executedSignatures,
                initialExecutedRecoveryKeys = saved.executedRecoveryKeys,
                initialTotalExecuted = saved.totalStepsExecuted,
                initialCorrectionsUsed = saved.correctionsUsed,
                expectedFinalPackage = saved.expectedFinalPackage,
                expectedFinalElement = saved.expectedFinalElement,
                initialVolumeBefore = saved.lastVolumeBefore,
                initialVolumeDirection = saved.lastVolumeDirection,
                initialFlashExpectedOn = saved.lastFlashExpectedOn,
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
        val cont = synchronized(stateLock) {
            val cur = pendingConfirmation ?: return null
            if (cur.pendingConfirmationId != pendingConfirmationId) return null
            pendingConfirmation = null
            cur
        }

        if (!isRunning.compareAndSet(false, true)) {
            return TaskExecutionReport(
                taskId = cont.taskId,
                objective = cont.objective,
                status = TaskExecutionStatus.ALREADY_RUNNING,
                completedSteps = cont.completedSteps,
                skippedSteps = cont.remainingSteps.map { it.toPlannedTaskStep() },
                summary = "Cannot resume task because another task is already running."
            )
        }

        if (taskContextStore.getActiveContext()?.state == TaskLifecycleState.AWAITING_CONFIRMATION) {
            taskContextStore.transitionTo(TaskLifecycleState.RUNNING)
        }

        val handle = CancellationHandle(cont.taskId)
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
                        stepIndex = cont.haltedStep.index,
                        action = cont.haltedStep.action.toPlannedTaskAction(),
                        description = cont.haltedStep.description,
                        status = TaskStepStatus.FAILED,
                        verified = false,
                        packageName = pkg,
                        resultCode = resultCode,
                        message = confirmedResult.summary
                    )
                    taskContextStore.failTask(confirmedResult.summary, failedOutcome)
                    onProgress(
                        TaskProgressEvent.StepFinished(
                            failedOutcome,
                            cont.completedSteps.size + 1 + cont.remainingSteps.size
                        )
                    )
                    return@coroutineScope buildReport(
                        taskId = cont.taskId,
                        objective = cont.objective,
                        status = TaskExecutionStatus.FAILED,
                        finalVerificationStatus = if (cont.completedSteps.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                        completedSteps = cont.completedSteps,
                        failedStep = failedOutcome,
                        skippedSteps = cont.remainingSteps.map { it.toPlannedTaskStep() },
                        correctionsUsed = cont.correctionsUsed
                    )
                }

                val verified = verifyStepOutcome(
                    handle = handle,
                    step = cont.haltedStep,
                    toolResult = confirmedResult,
                    previousTrackedPackage = cont.trackedPackage,
                    preActionSnapshot = cont.preActionSnapshot,
                    preVolume = cont.lastVolumeBefore,
                    lastVolumeDir = cont.lastVolumeDirection,
                    lastFlashExpectedOn = cont.lastFlashExpectedOn,
                    remainingSteps = cont.remainingSteps,
                    capSnapshot = capabilityRegistryProvider()?.refresh()
                )
                if (verified.errorOutcome != null) {
                    val failedOutcome = verified.errorOutcome
                    taskContextStore.failTask(failedOutcome.message, failedOutcome)
                    onProgress(
                        TaskProgressEvent.StepFinished(
                            failedOutcome,
                            cont.completedSteps.size + 1 + cont.remainingSteps.size
                        )
                    )
                    return@coroutineScope buildReport(
                        taskId = cont.taskId,
                        objective = cont.objective,
                        status = TaskExecutionStatus.FAILED,
                        finalVerificationStatus = if (cont.completedSteps.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                        completedSteps = cont.completedSteps,
                        failedStep = failedOutcome,
                        skippedSteps = cont.remainingSteps.map { it.toPlannedTaskStep() },
                        correctionsUsed = cont.correctionsUsed
                    )
                }

                val confirmedOutcome = TaskStepOutcome(
                    stepIndex = cont.haltedStep.index,
                    action = cont.haltedStep.action.toPlannedTaskAction(),
                    description = cont.haltedStep.description,
                    status = TaskStepStatus.COMPLETED,
                    verified = true,
                    packageName = verified.trackedPackage,
                    resultCode = confirmedResult.payload["result_code"]?.jsonPrimitive?.content.orEmpty().ifBlank { "SUCCESS" },
                    message = sanitizeStepSummary(cont.haltedStep, confirmedResult.summary),
                    verificationAttempts = verified.verificationAttempts
                )
                val snap = if (cont.haltedStep.usesAccessibilityScreen()) {
                    (toolExecutor.readScreenRaw() as? ScreenReadResult.Success)?.snapshot
                } else null
                taskContextStore.onStepCompleted(confirmedOutcome, verified.trackedPackage, snap)
                val updatedCompleted = cont.completedSteps + confirmedOutcome
                onProgress(
                    TaskProgressEvent.StepFinished(
                        confirmedOutcome,
                        updatedCompleted.size + cont.remainingSteps.size
                    )
                )

                val updatedSignatures = cont.executedSignatures + cont.haltedStep.signatureKey()
                if (cont.remainingSteps.isEmpty()) {
                    val goalOutcome = evaluateFinalGoal(
                        handle = handle,
                        objective = cont.objective,
                        allSteps = cont.allSteps,
                        completedSteps = updatedCompleted,
                        expectedFinalPackage = cont.expectedFinalPackage,
                        expectedFinalElement = cont.expectedFinalElement
                    )
                    if (!goalOutcome.isVerified) {
                        val failRep = buildReport(
                            taskId = cont.taskId,
                            objective = cont.objective,
                            status = TaskExecutionStatus.FAILED,
                            finalVerificationStatus = goalOutcome.status,
                            completedSteps = updatedCompleted,
                            skippedSteps = emptyList(),
                            correctionsUsed = cont.correctionsUsed,
                            verificationFailureReason = goalOutcome.failureReason
                        )
                        taskContextStore.failTask(failRep.summary)
                        return@coroutineScope failRep
                    }
                    val finalRep = buildReport(
                        taskId = cont.taskId,
                        objective = cont.objective,
                        status = TaskExecutionStatus.SUCCEEDED,
                        finalVerificationStatus = FinalVerificationStatus.COMPLETED,
                        completedSteps = updatedCompleted,
                        correctionsUsed = cont.correctionsUsed
                    )
                    taskContextStore.completeTask(finalRep.summary)
                    return@coroutineScope finalRep
                }

                runLoopInternal(
                    handle = handle,
                    taskId = cont.taskId,
                    objective = cont.objective,
                    allSteps = cont.allSteps,
                    initialQueue = cont.remainingSteps,
                    initialCompleted = updatedCompleted,
                    initialTrackedPackage = verified.trackedPackage,
                    initialExecutedSignatures = updatedSignatures,
                    initialExecutedRecoveryKeys = cont.executedRecoveryKeys,
                    initialTotalExecuted = cont.totalStepsExecuted + 1,
                    initialCorrectionsUsed = cont.correctionsUsed,
                    expectedFinalPackage = cont.expectedFinalPackage,
                    expectedFinalElement = cont.expectedFinalElement,
                    initialVolumeBefore = cont.lastVolumeBefore,
                    initialVolumeDirection = cont.lastVolumeDirection,
                    initialFlashExpectedOn = cont.lastFlashExpectedOn,
                    announceTask = false,
                    onProgress = onProgress
                )
            }
        } catch (e: CancellationException) {
            if (handle.cancelled.get()) {
                taskContextStore.cancelTask("Task cancelled by request.")
                buildReport(
                    taskId = cont.taskId,
                    objective = cont.objective,
                    status = TaskExecutionStatus.CANCELLED,
                    finalVerificationStatus = if (cont.completedSteps.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = cont.completedSteps,
                    skippedSteps = cont.remainingSteps.map { it.toPlannedTaskStep() },
                    correctionsUsed = cont.correctionsUsed
                )
            } else {
                taskContextStore.cancelTask("Task cancelled externally during confirmation resume.")
                throw e
            }
        } finally {
            activeHandle.set(null)
            isRunning.set(false)
        }
    }

    private suspend fun runLoopUnderActiveLock(
        handle: CancellationHandle,
        taskId: String,
        objective: String,
        allSteps: List<LocalTaskStep>,
        initialQueue: List<LocalTaskStep>,
        initialCompleted: List<TaskStepOutcome>,
        initialTrackedPackage: String,
        initialExecutedSignatures: List<String>,
        initialExecutedRecoveryKeys: Set<String>,
        initialTotalExecuted: Int,
        initialCorrectionsUsed: Int,
        expectedFinalPackage: String,
        expectedFinalElement: String,
        initialVolumeBefore: VolumeStateSnapshot?,
        initialVolumeDirection: String?,
        initialFlashExpectedOn: Boolean?,
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
                        skippedSteps = initialQueue.map { it.toPlannedTaskStep() },
                        correctionsUsed = initialCorrectionsUsed
                    )
                }
                runLoopInternal(
                    handle = handle,
                    taskId = taskId,
                    objective = objective,
                    allSteps = allSteps,
                    initialQueue = initialQueue,
                    initialCompleted = initialCompleted,
                    initialTrackedPackage = initialTrackedPackage,
                    initialExecutedSignatures = initialExecutedSignatures,
                    initialExecutedRecoveryKeys = initialExecutedRecoveryKeys,
                    initialTotalExecuted = initialTotalExecuted,
                    initialCorrectionsUsed = initialCorrectionsUsed,
                    expectedFinalPackage = expectedFinalPackage,
                    expectedFinalElement = expectedFinalElement,
                    initialVolumeBefore = initialVolumeBefore,
                    initialVolumeDirection = initialVolumeDirection,
                    initialFlashExpectedOn = initialFlashExpectedOn,
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
                    skippedSteps = initialQueue.map { it.toPlannedTaskStep() },
                    correctionsUsed = initialCorrectionsUsed
                )
            } else {
                taskContextStore.cancelTask("Task cancelled externally during local automation.")
                throw e
            }
        }
    }

    private suspend fun runLoopInternal(
        handle: CancellationHandle,
        taskId: String,
        objective: String,
        allSteps: List<LocalTaskStep>,
        initialQueue: List<LocalTaskStep>,
        initialCompleted: List<TaskStepOutcome>,
        initialTrackedPackage: String,
        initialExecutedSignatures: List<String>,
        initialExecutedRecoveryKeys: Set<String>,
        initialTotalExecuted: Int,
        initialCorrectionsUsed: Int,
        expectedFinalPackage: String,
        expectedFinalElement: String,
        initialVolumeBefore: VolumeStateSnapshot?,
        initialVolumeDirection: String?,
        initialFlashExpectedOn: Boolean?,
        announceTask: Boolean,
        onProgress: suspend (TaskProgressEvent) -> Unit
    ): TaskExecutionReport {
        if (initialQueue.size + initialCompleted.size > LocalDeviceTaskPlanner.MAX_LOCAL_TASK_STEPS) {
            val limitRep = buildReport(
                taskId = taskId,
                objective = objective,
                status = TaskExecutionStatus.LIMIT_EXCEEDED,
                finalVerificationStatus = if (initialCompleted.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                completedSteps = initialCompleted,
                skippedSteps = initialQueue.map { it.toPlannedTaskStep() },
                correctionsUsed = initialCorrectionsUsed,
                overrideSummary = "Task exceeds the maximum limit of ${LocalDeviceTaskPlanner.MAX_LOCAL_TASK_STEPS} steps."
            )
            taskContextStore.failTask(limitRep.summary)
            return limitRep
        }

        val queue = ArrayDeque(initialQueue)
        val completed = initialCompleted.toMutableList()
        val executedSignatures = initialExecutedSignatures.toMutableList()
        val executedRecoveryKeys = initialExecutedRecoveryKeys.toMutableSet()
        var trackedPackage = initialTrackedPackage
        var stepsExecutedCount = initialTotalExecuted
        var correctionsUsed = initialCorrectionsUsed
        var lastVolumeBefore: VolumeStateSnapshot? = initialVolumeBefore
        var lastVolumeDirection: String? = initialVolumeDirection
        var lastFlashExpectedOn: Boolean? = initialFlashExpectedOn

        if (announceTask) {
            onProgress(
                TaskProgressEvent.Announced(
                    taskId = taskId,
                    objective = objective,
                    totalSteps = queue.size,
                    message = "Starting local task (${queue.size} step${if (queue.size == 1) "" else "s"}): $objective"
                )
            )
        }

        while (queue.isNotEmpty()) {
            if (handle.pauseRequested.get()) {
                val remaining = queue.toList()
                synchronized(stateLock) {
                    pausedContinuation = PausedLocalContinuation(
                        taskId = taskId,
                        objective = objective,
                        allSteps = allSteps,
                        completedSteps = completed.toList(),
                        remainingSteps = remaining,
                        trackedPackage = trackedPackage,
                        executedSignatures = executedSignatures.toList(),
                        executedRecoveryKeys = executedRecoveryKeys.toSet(),
                        totalStepsExecuted = stepsExecutedCount,
                        correctionsUsed = correctionsUsed,
                        expectedFinalPackage = expectedFinalPackage,
                        expectedFinalElement = expectedFinalElement,
                        lastVolumeBefore = lastVolumeBefore,
                        lastVolumeDirection = lastVolumeDirection,
                        lastFlashExpectedOn = lastFlashExpectedOn
                    )
                }
                val pauseRep = buildReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.PAUSED,
                    finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = completed,
                    skippedSteps = remaining.map { it.toPlannedTaskStep() },
                    correctionsUsed = correctionsUsed
                )
                taskContextStore.pauseTask("Local task paused by request.")
                return pauseRep
            }

            if (handle.cancelled.get() || !currentCoroutineContext().isActive) {
                val cancelRep = buildReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.CANCELLED,
                    finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = completed,
                    skippedSteps = queue.map { it.toPlannedTaskStep() },
                    correctionsUsed = correctionsUsed
                )
                taskContextStore.cancelTask(cancelRep.summary)
                return cancelRep
            }

            if (stepsExecutedCount >= LocalDeviceTaskPlanner.MAX_LOCAL_TASK_STEPS) {
                val limitRep = buildReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.LIMIT_EXCEEDED,
                    finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = completed,
                    skippedSteps = queue.map { it.toPlannedTaskStep() },
                    correctionsUsed = correctionsUsed,
                    overrideSummary = "Stopped after reaching the maximum limit of ${LocalDeviceTaskPlanner.MAX_LOCAL_TASK_STEPS} actions."
                )
                taskContextStore.failTask(limitRep.summary)
                return limitRep
            }

            val step = queue.removeFirst()
            val plannedStep = step.toPlannedTaskStep()
            val plannedTotal = completed.size + 1 + queue.size

            // Dynamic capability discovery & refresh before every step
            val capSnapshot = capabilityRegistryProvider()?.refresh()
            val reqCap = step.requiredCapability
            if (capSnapshot != null && reqCap != null) {
                val status = capSnapshot.statusFor(reqCap)
                if (status != null && !status.isAvailable) {
                    val capFailure = TaskStepOutcome(
                        stepIndex = step.index,
                        action = plannedStep.action,
                        description = step.description,
                        status = TaskStepStatus.FAILED,
                        verified = false,
                        packageName = trackedPackage,
                        resultCode = status.status.name,
                        message = "Stopped safely: capability '${status.label}' is unavailable (${status.reason})"
                    )
                    onProgress(TaskProgressEvent.StepFinished(capFailure, plannedTotal))
                    val failRep = buildReport(
                        taskId = taskId,
                        objective = objective,
                        status = TaskExecutionStatus.FAILED,
                        finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                        completedSteps = completed,
                        failedStep = capFailure,
                        skippedSteps = queue.map { it.toPlannedTaskStep() },
                        correctionsUsed = correctionsUsed
                    )
                    taskContextStore.failTask(failRep.summary, capFailure)
                    return failRep
                }
            }

            val stepSig = step.signatureKey()
            val duplicateConsecutive = executedSignatures.lastOrNull() == stepSig
            val alreadyCompletedNonIdempotent = !step.action.isIdempotent && stepSig in executedSignatures
            val excessiveCount = step.action != LocalTaskAction.SCROLL_UI && executedSignatures.count { it == stepSig } >= 2
            if (duplicateConsecutive || alreadyCompletedNonIdempotent || excessiveCount) {
                val loopFailure = TaskStepOutcome(
                    stepIndex = step.index,
                    action = plannedStep.action,
                    description = step.description,
                    status = TaskStepStatus.FAILED,
                    verified = false,
                    packageName = trackedPackage,
                    resultCode = TaskPlanErrorCode.DUPLICATE_ACTION.name,
                    message = "Duplicate or repeated action prevented at step ${step.index} (${step.description})."
                )
                onProgress(TaskProgressEvent.StepFinished(loopFailure, plannedTotal))
                val failRep = buildReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.FAILED,
                    finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = completed,
                    failedStep = loopFailure,
                    skippedSteps = queue.map { it.toPlannedTaskStep() },
                    correctionsUsed = correctionsUsed
                )
                taskContextStore.failTask(failRep.summary, loopFailure)
                return failRep
            }

            taskContextStore.onStepStarted(plannedStep, queue.map { it.toPlannedTaskStep() })
            onProgress(
                TaskProgressEvent.StepStarted(
                    stepIndex = step.index,
                    totalSteps = plannedTotal,
                    step = plannedStep,
                    message = "Step ${step.index}/$plannedTotal: ${step.description}…"
                )
            )

            // Capture pre-action baseline state
            var preActionSnapshot: ScreenSnapshot? = null
            if (step.action in setOf(
                    LocalTaskAction.FIND_UI_ELEMENT,
                    LocalTaskAction.CLICK_UI_ELEMENT,
                    LocalTaskAction.TYPE_TEXT,
                    LocalTaskAction.SCROLL_UI
                )
            ) {
                val preWait = awaitScreenReadyForUiStep(handle, trackedPackage)
                when (preWait) {
                    is PreScreenResult.Ready -> preActionSnapshot = preWait.snapshot
                    is PreScreenResult.Disconnected -> {
                        val dcFail = TaskStepOutcome(
                            stepIndex = step.index,
                            action = plannedStep.action,
                            description = step.description,
                            status = TaskStepStatus.FAILED,
                            verified = false,
                            packageName = trackedPackage,
                            resultCode = ScreenInteractionStatus.SERVICE_DISCONNECTED.name,
                            message = preWait.message
                        )
                        onProgress(TaskProgressEvent.StepFinished(dcFail, plannedTotal))
                        val failRep = buildReport(
                            taskId = taskId,
                            objective = objective,
                            status = TaskExecutionStatus.FAILED,
                            finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                            completedSteps = completed,
                            failedStep = dcFail,
                            skippedSteps = queue.map { it.toPlannedTaskStep() },
                            correctionsUsed = correctionsUsed
                        )
                        taskContextStore.failTask(failRep.summary, dcFail)
                        return failRep
                    }
                    is PreScreenResult.TimedOut -> {
                        val timeoutFail = TaskStepOutcome(
                            stepIndex = step.index,
                            action = plannedStep.action,
                            description = step.description,
                            status = TaskStepStatus.FAILED,
                            verified = false,
                            packageName = preWait.lastPackage.ifBlank { trackedPackage },
                            resultCode = preWait.resultCode,
                            message = preWait.message
                        )
                        onProgress(TaskProgressEvent.StepFinished(timeoutFail, plannedTotal))
                        val failRep = buildReport(
                            taskId = taskId,
                            objective = objective,
                            status = TaskExecutionStatus.FAILED,
                            finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                            completedSteps = completed,
                            failedStep = timeoutFail,
                            skippedSteps = queue.map { it.toPlannedTaskStep() },
                            correctionsUsed = correctionsUsed
                        )
                        taskContextStore.failTask(failRep.summary, timeoutFail)
                        return failRep
                    }
                }
            }

            if (step.action == LocalTaskAction.ADJUST_VOLUME) {
                lastVolumeBefore = toolExecutor.localDeviceController.readVolumeState()
                lastVolumeDirection = step.args["action"]
            } else if (step.action == LocalTaskAction.SET_FLASHLIGHT) {
                lastFlashExpectedOn = step.args["action"] == "flashlight_on"
            }

            val toolResult = executeLocalStepAction(
                handle = handle,
                step = step,
                trackedPackage = trackedPackage,
                preVolume = lastVolumeBefore,
                lastVolumeDirection = lastVolumeDirection,
                lastFlashExpectedOn = lastFlashExpectedOn,
                onProgress = onProgress
            )

            if (handle.cancelled.get()) {
                val cancelRep = buildReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.CANCELLED,
                    finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = completed,
                    skippedSteps = queue.map { it.toPlannedTaskStep() },
                    correctionsUsed = correctionsUsed
                )
                taskContextStore.cancelTask(cancelRep.summary)
                return cancelRep
            }

            if (toolResult.isConfirmationRequired()) {
                val pendingId = toolResult.payload["pending_id"]?.jsonPrimitive?.content.orEmpty()
                val actionDesc = toolResult.payload["action"]?.jsonPrimitive?.content.orEmpty()
                    .ifBlank { step.description }
                val haltedOutcome = TaskStepOutcome(
                    stepIndex = step.index,
                    action = plannedStep.action,
                    description = step.description,
                    status = TaskStepStatus.AWAITING_CONFIRMATION,
                    verified = false,
                    packageName = trackedPackage,
                    resultCode = ToolConfirmationPolicy.STATUS_CONFIRMATION_REQUIRED,
                    message = toolResult.summary
                )
                taskContextStore.onAwaitingConfirmation(pendingId, actionDesc)
                onProgress(TaskProgressEvent.StepFinished(haltedOutcome, plannedTotal))
                synchronized(stateLock) {
                    pendingConfirmation = PendingLocalConfirmation(
                        taskId = taskId,
                        objective = objective,
                        pendingConfirmationId = pendingId,
                        allSteps = allSteps,
                        haltedStep = step,
                        completedSteps = completed.toList(),
                        remainingSteps = queue.toList(),
                        trackedPackage = trackedPackage,
                        executedSignatures = executedSignatures.toList(),
                        executedRecoveryKeys = executedRecoveryKeys.toSet(),
                        totalStepsExecuted = stepsExecutedCount,
                        correctionsUsed = correctionsUsed,
                        expectedFinalPackage = expectedFinalPackage,
                        expectedFinalElement = expectedFinalElement,
                        preActionSnapshot = TaskContextSanitizer.sanitizeSnapshot(preActionSnapshot),
                        lastVolumeBefore = lastVolumeBefore,
                        lastVolumeDirection = lastVolumeDirection,
                        lastFlashExpectedOn = lastFlashExpectedOn
                    )
                }
                return buildReport(
                    taskId = taskId,
                    objective = objective,
                    status = TaskExecutionStatus.AWAITING_CONFIRMATION,
                    finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                    completedSteps = completed,
                    failedStep = haltedOutcome,
                    skippedSteps = queue.map { it.toPlannedTaskStep() },
                    correctionsUsed = correctionsUsed,
                    pendingConfirmationId = pendingId,
                    pendingConfirmationDescription = actionDesc
                )
            }

            if (toolResult.ok) {
                val postVerify = verifyStepOutcome(
                    handle = handle,
                    step = step,
                    toolResult = toolResult,
                    previousTrackedPackage = trackedPackage,
                    preActionSnapshot = preActionSnapshot,
                    preVolume = lastVolumeBefore,
                    lastVolumeDir = lastVolumeDirection,
                    lastFlashExpectedOn = lastFlashExpectedOn,
                    remainingSteps = queue.toList(),
                    capSnapshot = capabilityRegistryProvider()?.refresh()
                )
                if (postVerify.errorOutcome == null) {
                    trackedPackage = postVerify.trackedPackage
                    executedSignatures.add(stepSig)
                    stepsExecutedCount += 1

                    val outcome = TaskStepOutcome(
                        stepIndex = step.index,
                        action = plannedStep.action,
                        description = step.description,
                        status = TaskStepStatus.COMPLETED,
                        verified = true,
                        packageName = trackedPackage,
                        resultCode = toolResult.payload["result_code"]?.jsonPrimitive?.content.orEmpty().ifBlank { "SUCCESS" },
                        message = sanitizeStepSummary(step, toolResult.summary),
                        verificationAttempts = postVerify.verificationAttempts
                    )
                    completed.add(outcome)
                    val snap = if (step.usesAccessibilityScreen()) {
                        (toolExecutor.readScreenRaw() as? ScreenReadResult.Success)?.snapshot
                    } else null
                    taskContextStore.onStepCompleted(outcome, trackedPackage, snap)
                    onProgress(TaskProgressEvent.StepFinished(outcome, plannedTotal))
                    continue
                } else {
                    val verifyFailure = postVerify.errorOutcome
                    onProgress(TaskProgressEvent.StepFinished(verifyFailure, plannedTotal))
                    val failRep = buildReport(
                        taskId = taskId,
                        objective = objective,
                        status = TaskExecutionStatus.FAILED,
                        finalVerificationStatus = if (completed.isNotEmpty()) FinalVerificationStatus.PARTIALLY_COMPLETED else FinalVerificationStatus.FAILED,
                        completedSteps = completed,
                        failedStep = verifyFailure,
                        skippedSteps = queue.map { it.toPlannedTaskStep() },
                        correctionsUsed = correctionsUsed,
                        verificationFailureReason = verifyFailure.message
                    )
                    taskContextStore.failTask(failRep.summary, verifyFailure)
                    return failRep
                }
            }

            // Step failed -> evaluate safe, bounded dynamic recovery (never blindly repeat non-idempotent steps)
            val resultCode = toolResult.payload["result_code"]?.jsonPrimitive?.content.orEmpty()
            val pkg = toolResult.payload["package_name"]?.jsonPrimitive?.content.orEmpty()
            val failureOutcome = TaskStepOutcome(
                stepIndex = step.index,
                action = plannedStep.action,
                description = step.description,
                status = TaskStepStatus.FAILED,
                verified = false,
                packageName = pkg.ifBlank { trackedPackage },
                resultCode = resultCode,
                message = sanitizeStepSummary(step, toolResult.summary)
            )
            executedSignatures.add(stepSig)
            stepsExecutedCount += 1

            val recoverySteps = attemptBoundedSafeRecovery(
                failedStep = step,
                failureOutcome = failureOutcome,
                trackedPackage = trackedPackage,
                executedRecoveryKeys = executedRecoveryKeys,
                correctionsUsed = correctionsUsed,
                totalStepsSoFar = stepsExecutedCount + queue.size
            )
            if (recoverySteps.isNotEmpty()) {
                correctionsUsed += 1
                // Insert recovery step(s) + idempotent retry in order
                for (rStep in recoverySteps.asReversed()) {
                    queue.addFirst(rStep)
                }
                val firstRecovery = recoverySteps.first().toPlannedTaskStep()
                taskContextStore.onRecoveryAttempted(failureOutcome, firstRecovery)
                onProgress(
                    TaskProgressEvent.CorrectionApplied(
                        correctionNumber = correctionsUsed,
                        maxCorrections = LocalDeviceTaskPlanner.MAX_LOCAL_RECOVERY_ATTEMPTS,
                        reason = failureOutcome.message,
                        replacementStep = firstRecovery
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
                skippedSteps = queue.map { it.toPlannedTaskStep() },
                correctionsUsed = correctionsUsed
            )
            taskContextStore.failTask(finalFailRep.summary, failureOutcome)
            return finalFailRep
        }

        val goalOutcome = evaluateFinalGoal(
            handle = handle,
            objective = objective,
            allSteps = allSteps,
            completedSteps = completed,
            expectedFinalPackage = expectedFinalPackage,
            expectedFinalElement = expectedFinalElement
        )

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

    private sealed interface PreScreenResult {
        data class Ready(val snapshot: ScreenSnapshot?) : PreScreenResult
        data class Disconnected(val message: String) : PreScreenResult
        data class TimedOut(val resultCode: String, val lastPackage: String, val message: String) : PreScreenResult
    }

    private suspend fun awaitScreenReadyForUiStep(
        handle: CancellationHandle,
        trackedPackage: String
    ): PreScreenResult {
        return when (val initial = toolExecutor.readScreenRaw()) {
            is ScreenReadResult.Success -> {
                if (!initial.snapshot.isLoadingState()) {
                    PreScreenResult.Ready(initial.snapshot)
                } else {
                    when (
                        val waited = UiWaitPolicy.waitForCondition(
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
                        is UiWaitOutcome.Satisfied -> PreScreenResult.Ready(waited.snapshot)
                        is UiWaitOutcome.ServiceDisconnected -> PreScreenResult.Disconnected(waited.message)
                        is UiWaitOutcome.TimedOut -> PreScreenResult.TimedOut(
                            resultCode = if (waited.sawLoading) "LOADING_TIMEOUT" else ScreenInteractionStatus.ACTION_FAILED.name,
                            lastPackage = waited.lastSnapshot?.packageName.orEmpty().ifBlank { trackedPackage },
                            message = waited.reason
                        )
                    }
                }
            }
            is ScreenReadResult.Error -> {
                if (initial.code == ScreenReadErrorCode.SERVICE_NOT_CONNECTED ||
                    initial.code == ScreenReadErrorCode.SERVICE_ENABLED_BUT_DISCONNECTED
                ) {
                    PreScreenResult.Disconnected(initial.message)
                } else if (initial.code == ScreenReadErrorCode.UNAVAILABLE_NULL_ROOT ||
                    initial.code == ScreenReadErrorCode.EMPTY_SCREEN_TREE
                ) {
                    when (
                        val waited = UiWaitPolicy.waitForCondition(
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
                        is UiWaitOutcome.Satisfied -> PreScreenResult.Ready(waited.snapshot)
                        is UiWaitOutcome.ServiceDisconnected -> PreScreenResult.Disconnected(waited.message)
                        is UiWaitOutcome.TimedOut -> PreScreenResult.TimedOut(
                            resultCode = ScreenInteractionStatus.ACTION_FAILED.name,
                            lastPackage = trackedPackage,
                            message = waited.reason
                        )
                    }
                } else {
                    PreScreenResult.Ready(null)
                }
            }
        }
    }

    private suspend fun executeLocalStepAction(
        handle: CancellationHandle,
        step: LocalTaskStep,
        trackedPackage: String,
        preVolume: VolumeStateSnapshot?,
        lastVolumeDirection: String?,
        lastFlashExpectedOn: Boolean?,
        onProgress: suspend (TaskProgressEvent) -> Unit
    ): ToolCallExecutionResult {
        return when (step.action) {
            LocalTaskAction.WAIT_FOR_APP -> {
                val expectedPkg = step.expectedPackage.ifBlank { trackedPackage }
                val label = step.expectedAppLabel.ifBlank { step.args["app_name"].orEmpty() }.ifBlank { "app" }
                val waitOutcome = UiWaitPolicy.waitForCondition(
                    timeoutMs = waitConfig.loadingTimeoutMs,
                    pollIntervalMs = waitConfig.pollIntervalMs,
                    maxPolls = waitConfig.maxPolls,
                    nowMs = nowMs,
                    isCancelled = { handle.cancelled.get() },
                    requireNonLoading = true,
                    readFresh = { toolExecutor.readScreenRaw() },
                    condition = { snap ->
                        if (expectedPkg.isNotBlank()) {
                            snap.packageName.equals(expectedPkg, ignoreCase = true)
                        } else {
                            snap.packageName.isNotBlank() &&
                                !snap.packageName.contains("launcher", ignoreCase = true) &&
                                snap.packageName != "android"
                        }
                    }
                )
                val res = when (waitOutcome) {
                    is UiWaitOutcome.Satisfied -> {
                        val pkg = waitOutcome.snapshot.packageName
                        ToolCallExecutionResult(
                            toolName = LocalTaskAction.WAIT_FOR_APP.toolName,
                            ok = true,
                            summary = "Verified '$label' finished loading in foreground ($pkg).",
                            payload = buildJsonObject {
                                put("status", "ok")
                                put("result_code", "LOADED")
                                put("package_name", pkg)
                            }
                        )
                    }
                    is UiWaitOutcome.ServiceDisconnected -> ToolCallExecutionResult(
                        toolName = LocalTaskAction.WAIT_FOR_APP.toolName,
                        ok = false,
                        summary = waitOutcome.message,
                        payload = buildJsonObject {
                            put("status", "failed")
                            put("result_code", ScreenInteractionStatus.SERVICE_DISCONNECTED.name)
                        }
                    )
                    is UiWaitOutcome.TimedOut -> ToolCallExecutionResult(
                        toolName = LocalTaskAction.WAIT_FOR_APP.toolName,
                        ok = false,
                        summary = if (expectedPkg.isNotBlank()) {
                            "Timed out waiting for '$label' ($expectedPkg) to load: ${waitOutcome.reason}"
                        } else {
                            "Timed out waiting for screen to finish loading: ${waitOutcome.reason}"
                        },
                        payload = buildJsonObject {
                            put("status", "failed")
                            put("result_code", if (waitOutcome.sawLoading) "LOADING_TIMEOUT" else ScreenInteractionStatus.SCREEN_CHANGED.name)
                            waitOutcome.lastSnapshot?.packageName?.takeIf { it.isNotBlank() }?.let {
                                put("package_name", it)
                            }
                        }
                    )
                }
                toolExecutor.logLocalActionAudit(step.action.toolName, res.summary, res.ok)
                res
            }

            LocalTaskAction.VERIFY_FOREGROUND_APP -> {
                val expectedPkg = step.expectedPackage.ifBlank { trackedPackage }
                val label = step.expectedAppLabel.ifBlank { step.args["app_name"].orEmpty() }.ifBlank { "target app" }
                val res = when (val read = toolExecutor.readScreenRaw()) {
                    is ScreenReadResult.Error -> ToolCallExecutionResult(
                        toolName = LocalTaskAction.VERIFY_FOREGROUND_APP.toolName,
                        ok = false,
                        summary = "Could not verify foreground app: ${read.message}",
                        payload = buildJsonObject {
                            put("status", "failed")
                            val code = if (read.code == ScreenReadErrorCode.EMPTY_SCREEN_TREE ||
                                read.code == ScreenReadErrorCode.UNAVAILABLE_NULL_ROOT
                            ) {
                                read.code.name
                            } else {
                                ScreenInteractionStatus.SERVICE_DISCONNECTED.name
                            }
                            put("result_code", code)
                        }
                    )
                    is ScreenReadResult.Success -> {
                        val livePkg = read.snapshot.packageName
                        val matched = if (expectedPkg.isNotBlank()) {
                            livePkg.equals(expectedPkg, ignoreCase = true)
                        } else {
                            livePkg.isNotBlank() &&
                                !livePkg.contains("launcher", ignoreCase = true) &&
                                livePkg != "android"
                        }
                        if (matched) {
                            ToolCallExecutionResult(
                                toolName = LocalTaskAction.VERIFY_FOREGROUND_APP.toolName,
                                ok = true,
                                summary = "Verified foreground app is $livePkg.",
                                payload = buildJsonObject {
                                    put("status", "ok")
                                    put("result_code", "FOREGROUND_VERIFIED")
                                    put("package_name", livePkg)
                                }
                            )
                        } else {
                            ToolCallExecutionResult(
                                toolName = LocalTaskAction.VERIFY_FOREGROUND_APP.toolName,
                                ok = false,
                                summary = "Foreground app verification failed: expected '$label' (${expectedPkg.ifBlank { "non-launcher" }}), but active app is '$livePkg'.",
                                payload = buildJsonObject {
                                    put("status", "failed")
                                    put("result_code", ScreenInteractionStatus.SCREEN_CHANGED.name)
                                    put("package_name", livePkg)
                                }
                            )
                        }
                    }
                }
                toolExecutor.logLocalActionAudit(step.action.toolName, res.summary, res.ok)
                res
            }

            LocalTaskAction.VERIFY_VOLUME -> {
                val after = toolExecutor.localDeviceController.readVolumeState()
                val res = if (after == null) {
                    ToolCallExecutionResult(
                        toolName = LocalTaskAction.VERIFY_VOLUME.toolName,
                        ok = false,
                        summary = "Volume verification failed: AudioManager unavailable.",
                        payload = buildJsonObject {
                            put("status", "failed")
                            put("result_code", "AUDIO_UNAVAILABLE")
                        }
                    )
                } else {
                    val ok = when (lastVolumeDirection) {
                        "volume_up" -> preVolume == null ||
                            after.streamVolume > preVolume.streamVolume ||
                            (preVolume.streamVolume >= preVolume.maxVolume && after.streamVolume >= after.maxVolume) ||
                            (preVolume.isMuted && !after.isMuted)
                        "volume_down" -> preVolume == null ||
                            after.streamVolume < preVolume.streamVolume ||
                            (preVolume.streamVolume <= 0 && after.streamVolume <= 0)
                        "mute" -> after.isMuted || after.streamVolume == 0
                        "unmute" -> !after.isMuted
                        else -> true
                    }
                    val msg = if (ok) {
                        "Verified media volume: ${after.streamVolume}/${after.maxVolume}${if (after.isMuted) " (muted)" else ""}."
                    } else {
                        "Volume verification failed after '$lastVolumeDirection': expected volume state did not hold (actual ${after.streamVolume}/${after.maxVolume}, muted=${after.isMuted})."
                    }
                    ToolCallExecutionResult(
                        toolName = LocalTaskAction.VERIFY_VOLUME.toolName,
                        ok = ok,
                        summary = msg,
                        payload = buildJsonObject {
                            put("status", if (ok) "ok" else "failed")
                            put("stream_volume", after.streamVolume)
                            put("max_volume", after.maxVolume)
                            put("muted", after.isMuted)
                        }
                    )
                }
                toolExecutor.logLocalActionAudit(step.action.toolName, res.summary, res.ok)
                res
            }

            LocalTaskAction.VERIFY_FLASHLIGHT -> {
                val expectedOn = step.args["expected_on"]?.toBooleanStrictOrNull() ?: lastFlashExpectedOn ?: true
                val outcome = toolExecutor.localDeviceController.verifyFlashlightState(expectedOn)
                val res = ToolCallExecutionResult(
                    toolName = LocalTaskAction.VERIFY_FLASHLIGHT.toolName,
                    ok = outcome.ok,
                    summary = outcome.summary,
                    payload = buildJsonObject {
                        put("status", if (outcome.ok) "ok" else "failed")
                        put("expected_on", expectedOn)
                    }
                )
                toolExecutor.logLocalActionAudit(step.action.toolName, res.summary, res.ok)
                res
            }

            else -> {
                val effectiveArgs: Map<String, JsonElement> = buildEffectiveStepArgs(step, trackedPackage)
                toolExecutor.execute(step.action.toolName, effectiveArgs) { eff ->
                    onProgress(TaskProgressEvent.SideEffect(eff))
                }
            }
        }
    }

    private fun buildEffectiveStepArgs(
        step: LocalTaskStep,
        trackedPackage: String
    ): Map<String, JsonElement> {
        val map = mutableMapOf<String, JsonElement>()
        step.args.forEach { (k, v) ->
            if (!k.startsWith("_")) {
                map[k] = JsonPrimitive(v)
            }
        }
        if (step.action in setOf(
                LocalTaskAction.FIND_UI_ELEMENT,
                LocalTaskAction.CLICK_UI_ELEMENT,
                LocalTaskAction.TYPE_TEXT,
                LocalTaskAction.SCROLL_UI
            )
        ) {
            val resolvedPkg = step.expectedPackage.ifBlank { trackedPackage }
            if (resolvedPkg.isNotBlank() && !map.containsKey("expected_package")) {
                map["expected_package"] = JsonPrimitive(resolvedPkg)
            }
        }
        return map
    }

    private suspend fun verifyStepOutcome(
        handle: CancellationHandle,
        step: LocalTaskStep,
        toolResult: ToolCallExecutionResult,
        previousTrackedPackage: String,
        preActionSnapshot: ScreenSnapshot?,
        preVolume: VolumeStateSnapshot?,
        lastVolumeDir: String?,
        lastFlashExpectedOn: Boolean?,
        remainingSteps: List<LocalTaskStep>,
        capSnapshot: DeviceCapabilitiesSnapshot?
    ): StepVerifyResult {
        if (handle.cancelled.get()) {
            throw CancellationException("Task cancelled during step verification")
        }

        val resultPkg = toolResult.payload["package_name"]?.jsonPrimitive?.content.orEmpty()

        // Explicit verification steps or read-only steps complete with their direct verified result
        when (step.action) {
            LocalTaskAction.WAIT_FOR_APP,
            LocalTaskAction.VERIFY_FOREGROUND_APP,
            LocalTaskAction.VERIFY_VOLUME,
            LocalTaskAction.VERIFY_FLASHLIGHT,
            LocalTaskAction.READ_SCREEN,
            LocalTaskAction.FIND_UI_ELEMENT -> {
                return StepVerifyResult(
                    trackedPackage = resultPkg.ifBlank { previousTrackedPackage },
                    verificationAttempts = 1
                )
            }
            LocalTaskAction.ADJUST_VOLUME -> {
                val snap = toolExecutor.localDeviceController.readVolumeState()
                if (snap == null) {
                    return StepVerifyResult(
                        trackedPackage = previousTrackedPackage,
                        errorOutcome = TaskStepOutcome(
                            stepIndex = step.index,
                            action = step.action.toPlannedTaskAction(),
                            description = step.description,
                            status = TaskStepStatus.FAILED,
                            verified = false,
                            resultCode = "AUDIO_UNAVAILABLE",
                            message = "Post-action volume verification failed: AudioManager unavailable."
                        )
                    )
                }
                val verified = when (lastVolumeDir ?: step.args["action"]) {
                    "volume_up" -> preVolume == null ||
                        snap.streamVolume > preVolume.streamVolume ||
                        (preVolume.streamVolume >= preVolume.maxVolume && snap.streamVolume >= snap.maxVolume) ||
                        (preVolume.isMuted && !snap.isMuted)
                    "volume_down" -> preVolume == null ||
                        snap.streamVolume < preVolume.streamVolume ||
                        (preVolume.streamVolume <= 0 && snap.streamVolume <= 0)
                    "mute" -> snap.isMuted || snap.streamVolume == 0
                    "unmute" -> !snap.isMuted
                    else -> true
                }
                return if (verified) {
                    StepVerifyResult(trackedPackage = previousTrackedPackage)
                } else {
                    StepVerifyResult(
                        trackedPackage = previousTrackedPackage,
                        errorOutcome = TaskStepOutcome(
                            stepIndex = step.index,
                            action = step.action.toPlannedTaskAction(),
                            description = step.description,
                            status = TaskStepStatus.FAILED,
                            verified = false,
                            resultCode = "VOLUME_VERIFY_FAILED",
                            message = "Independent volume check failed: actual media volume is ${snap.streamVolume}/${snap.maxVolume} (muted=${snap.isMuted})."
                        )
                    )
                }
            }
            LocalTaskAction.SET_FLASHLIGHT -> {
                val expectedOn = lastFlashExpectedOn ?: (step.args["action"] == "flashlight_on")
                val nextIsExplicitVerify = remainingSteps.firstOrNull()?.action == LocalTaskAction.VERIFY_FLASHLIGHT
                if (!nextIsExplicitVerify) {
                    val verifyRes = toolExecutor.localDeviceController.verifyFlashlightState(expectedOn)
                    if (!verifyRes.ok) {
                        return StepVerifyResult(
                            trackedPackage = previousTrackedPackage,
                            errorOutcome = TaskStepOutcome(
                                stepIndex = step.index,
                                action = step.action.toPlannedTaskAction(),
                                description = step.description,
                                status = TaskStepStatus.FAILED,
                                verified = false,
                                resultCode = "FLASHLIGHT_VERIFY_FAILED",
                                message = verifyRes.summary
                            )
                        )
                    }
                }
                return StepVerifyResult(trackedPackage = previousTrackedPackage)
            }
            LocalTaskAction.OPEN_APP -> {
                val resolvedPkg = step.expectedPackage.ifBlank { resultPkg }
                val hasExplicitWaitOrVerifyNext = remainingSteps.firstOrNull()?.action in setOf(
                    LocalTaskAction.WAIT_FOR_APP,
                    LocalTaskAction.VERIFY_FOREGROUND_APP
                )
                val needsUiNext = remainingSteps.any { it.usesAccessibilityScreen() }
                // If followed by UI interactions without explicit wait/verify and accessibility is connected,
                // verify the launched app actually reached foreground before running UI steps.
                if (!hasExplicitWaitOrVerifyNext && needsUiNext && capSnapshot?.accessibilityServiceConnected == true) {
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
                            val matchesExpected = resolvedPkg.isNotBlank() &&
                                postSnap.packageName.equals(resolvedPkg, ignoreCase = true)
                            val isExternal = postSnap.packageName.isNotBlank() &&
                                !postSnap.packageName.contains("launcher", ignoreCase = true) &&
                                postSnap.packageName != "android"
                            if (resolvedPkg.isNotBlank()) matchesExpected else isExternal
                        }
                    )
                    return when (waitOutcome) {
                        is UiWaitOutcome.Satisfied -> StepVerifyResult(
                            trackedPackage = waitOutcome.snapshot.packageName.ifBlank { resolvedPkg }
                        )
                        is UiWaitOutcome.ServiceDisconnected -> StepVerifyResult(
                            trackedPackage = previousTrackedPackage,
                            errorOutcome = TaskStepOutcome(
                                stepIndex = step.index,
                                action = step.action.toPlannedTaskAction(),
                                description = step.description,
                                status = TaskStepStatus.FAILED,
                                verified = false,
                                resultCode = ScreenInteractionStatus.SERVICE_DISCONNECTED.name,
                                message = "Post-open verification failed: ${waitOutcome.message}"
                            )
                        )
                        is UiWaitOutcome.TimedOut -> StepVerifyResult(
                            trackedPackage = previousTrackedPackage,
                            errorOutcome = TaskStepOutcome(
                                stepIndex = step.index,
                                action = step.action.toPlannedTaskAction(),
                                description = step.description,
                                status = TaskStepStatus.FAILED,
                                verified = false,
                                packageName = waitOutcome.lastSnapshot?.packageName.orEmpty(),
                                resultCode = ScreenInteractionStatus.SCREEN_CHANGED.name,
                                message = "Opened app did not reach the foreground cleanly (${waitOutcome.reason})."
                            )
                        )
                    }
                }
                return StepVerifyResult(trackedPackage = resolvedPkg.ifBlank { previousTrackedPackage })
            }
            LocalTaskAction.SET_DND,
            LocalTaskAction.MEDIA_CONTROL,
            LocalTaskAction.OPEN_SETTINGS,
            LocalTaskAction.NAVIGATE -> {
                val clearedPkg = if (step.action == LocalTaskAction.OPEN_SETTINGS ||
                    (step.action == LocalTaskAction.NAVIGATE && step.args["action"] in setOf("home", "recents"))
                ) "" else previousTrackedPackage
                return StepVerifyResult(trackedPackage = resultPkg.ifBlank { clearedPkg })
            }
            LocalTaskAction.CLICK_UI_ELEMENT,
            LocalTaskAction.TYPE_TEXT,
            LocalTaskAction.SCROLL_UI -> {
                // Handled below via bounded independent screen hierarchy verification
            }
        }

        // Bounded independent verification (max 2 verification attempts) for UI-changing actions
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
                        !postSnap.packageName.equals(expectedPkg, ignoreCase = true)
                    if (pkgMismatch) {
                        true
                    } else {
                        when (step.action) {
                            LocalTaskAction.CLICK_UI_ELEMENT -> hasScreenOrTargetChanged(preActionSnapshot, postSnap)
                            LocalTaskAction.SCROLL_UI -> hasScrollChanged(preActionSnapshot, postSnap)
                            LocalTaskAction.TYPE_TEXT -> {
                                val rawText = step.args["text"].orEmpty()
                                val verifiedField = toolResult.payload["verified"]?.jsonPrimitive?.booleanOrNull ?: false
                                verifiedField || isFieldVerified(step, postSnap, rawText)
                            }
                        }
                    }
                }
            )

            when (waitOutcome) {
                is UiWaitOutcome.ServiceDisconnected -> return StepVerifyResult(
                    trackedPackage = previousTrackedPackage,
                    verificationAttempts = attempt,
                    errorOutcome = TaskStepOutcome(
                        stepIndex = step.index,
                        action = step.action.toPlannedTaskAction(),
                        description = step.description,
                        status = TaskStepStatus.FAILED,
                        verified = false,
                        packageName = resultPkg,
                        resultCode = ScreenInteractionStatus.SERVICE_DISCONNECTED.name,
                        message = "Post-action verification failed: ${waitOutcome.message}",
                        verificationAttempts = attempt
                    )
                )
                is UiWaitOutcome.Satisfied -> {
                    val postSnap = waitOutcome.snapshot
                    if (expectedPkg.isNotBlank() &&
                        step.action != LocalTaskAction.CLICK_UI_ELEMENT &&
                        !postSnap.packageName.equals(expectedPkg, ignoreCase = true)
                    ) {
                        return StepVerifyResult(
                            trackedPackage = postSnap.packageName,
                            verificationAttempts = attempt,
                            errorOutcome = TaskStepOutcome(
                                stepIndex = step.index,
                                action = step.action.toPlannedTaskAction(),
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
                    return StepVerifyResult(
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
                        return StepVerifyResult(
                            trackedPackage = previousTrackedPackage,
                            verificationAttempts = attempt,
                            errorOutcome = TaskStepOutcome(
                                stepIndex = step.index,
                                action = step.action.toPlannedTaskAction(),
                                description = step.description,
                                status = TaskStepStatus.FAILED,
                                verified = false,
                                packageName = resultPkg,
                                resultCode = errCode,
                                message = "Post-action verification failed (attempt $attempt): ${lastErr.message}",
                                verificationAttempts = attempt
                            )
                        )
                    }
                    if (waitOutcome.sawLoading || attempt == 2) {
                        val failureMsg = if (waitOutcome.sawLoading) {
                            "Verification timed out while screen remained in loading state (${waitOutcome.loadingIndicators.joinToString(", ")})."
                        } else {
                            when (step.action) {
                                LocalTaskAction.CLICK_UI_ELEMENT ->
                                    "Verification failed: Click on '${step.description}' showed no verified UI change after 2 attempts."
                                LocalTaskAction.SCROLL_UI ->
                                    "Verification failed: scroll container state remained unchanged after 2 verification attempts."
                                LocalTaskAction.TYPE_TEXT ->
                                    "Verification failed: target input field did not reflect entered text after 2 attempts."
                            }
                        }
                        return StepVerifyResult(
                            trackedPackage = previousTrackedPackage,
                            verificationAttempts = attempt,
                            errorOutcome = TaskStepOutcome(
                                stepIndex = step.index,
                                action = step.action.toPlannedTaskAction(),
                                description = step.description,
                                status = TaskStepStatus.FAILED,
                                verified = false,
                                packageName = waitOutcome.lastSnapshot?.packageName.orEmpty().ifBlank { resultPkg.ifBlank { previousTrackedPackage } },
                                resultCode = if (waitOutcome.sawLoading) "LOADING_TIMEOUT" else ScreenInteractionStatus.ACTION_FAILED.name,
                                message = failureMsg,
                                verificationAttempts = attempt
                            )
                        )
                    }
                }
            }
        }

        return StepVerifyResult(
            trackedPackage = resultPkg.ifBlank { previousTrackedPackage },
            verificationAttempts = 2
        )
    }

    /**
     * Bounded safe dynamic recovery (max 2 attempts):
     * - Re-refreshes capability registry before deciding recovery.
     * - Never blindly repeats non-idempotent actions (CLICK_UI_ELEMENT, TYPE_TEXT, ADJUST_VOLUME, OPEN_APP, NAVIGATE).
     * - Only inserts idempotent read-safe recovery (e.g. bounded scroll-down before re-finding a NOT_FOUND target,
     *   or waiting for transient empty/loading tree before re-reading).
     */
    private suspend fun attemptBoundedSafeRecovery(
        failedStep: LocalTaskStep,
        failureOutcome: TaskStepOutcome,
        trackedPackage: String,
        executedRecoveryKeys: MutableSet<String>,
        correctionsUsed: Int,
        totalStepsSoFar: Int
    ): List<LocalTaskStep> {
        if (correctionsUsed >= LocalDeviceTaskPlanner.MAX_LOCAL_RECOVERY_ATTEMPTS) return emptyList()
        if (totalStepsSoFar + 2 > LocalDeviceTaskPlanner.MAX_LOCAL_TASK_STEPS) return emptyList()

        // Never recover when capability was revoked or Accessibility disconnected or screen switched unexpectedly
        val freshCaps = capabilityRegistryProvider()?.refresh()
        if (freshCaps != null && !freshCaps.isActionAvailable(DeviceActionId.SCREEN_READ)) {
            return emptyList()
        }
        val code = failureOutcome.resultCode
        if (code == ScreenInteractionStatus.SERVICE_DISCONNECTED.name ||
            code == ScreenInteractionStatus.SCREEN_CHANGED.name ||
            code == ScreenInteractionStatus.PASSWORD_FIELD.name ||
            code == ScreenInteractionStatus.AMBIGUOUS.name
        ) {
            return emptyList()
        }

        // Case 1: Read-only FIND_UI_ELEMENT returned NOT_FOUND -> scroll DOWN once on same screen, then re-find
        if (failedStep.action == LocalTaskAction.FIND_UI_ELEMENT &&
            code == ScreenInteractionStatus.NOT_FOUND.name &&
            (freshCaps == null || freshCaps.isActionAvailable(DeviceActionId.UI_SCROLL))
        ) {
            val recoveryKey = "scroll_recover|${failedStep.signatureKey()}"
            if (recoveryKey in executedRecoveryKeys) return emptyList()

            val liveSnap = (toolExecutor.readScreenRaw() as? ScreenReadResult.Success)?.snapshot ?: return emptyList()
            if (trackedPackage.isNotBlank() && !liveSnap.packageName.equals(trackedPackage, ignoreCase = true)) {
                return emptyList()
            }
            val hasScrollable = liveSnap.nodes.any { it.isScrollable }
            if (!hasScrollable) return emptyList()

            executedRecoveryKeys.add(recoveryKey)
            val scrollRecoverStep = LocalTaskStep(
                index = failedStep.index,
                action = LocalTaskAction.SCROLL_UI,
                description = "Scroll DOWN to locate target",
                args = buildMap {
                    put("direction", "DOWN")
                    if (trackedPackage.isNotBlank()) put("expected_package", trackedPackage)
                },
                expectedPackage = trackedPackage,
                requiredCapability = DeviceActionId.UI_SCROLL
            )
            // Retry the read-only FIND step after scrolling (assign a recovery marker so signatureKey stays distinct
            // from the failed attempt while preserving target selectors cleanly)
            val reFindStep = failedStep.copy(
                index = failedStep.index,
                expectedPackage = failedStep.expectedPackage.ifBlank { trackedPackage }
            )
            return listOf(scrollRecoverStep, reFindStep)
        }

        // Case 2: READ_SCREEN or VERIFY_FOREGROUND_APP hit transient empty/null tree -> WAIT_FOR_APP then retry read-only step
        if (failedStep.action in setOf(LocalTaskAction.READ_SCREEN, LocalTaskAction.VERIFY_FOREGROUND_APP) &&
            code in setOf(ScreenReadErrorCode.EMPTY_SCREEN_TREE.name, ScreenReadErrorCode.UNAVAILABLE_NULL_ROOT.name)
        ) {
            val recoveryKey = "wait_recover|${failedStep.signatureKey()}"
            if (recoveryKey in executedRecoveryKeys) return emptyList()
            executedRecoveryKeys.add(recoveryKey)

            val waitStep = LocalTaskStep(
                index = failedStep.index,
                action = LocalTaskAction.WAIT_FOR_APP,
                description = "Wait for screen tree to stabilize",
                args = buildMap {
                    if (trackedPackage.isNotBlank()) put("expected_package", trackedPackage)
                },
                expectedPackage = trackedPackage,
                requiredCapability = DeviceActionId.SCREEN_READ
            )
            return listOf(waitStep, failedStep)
        }

        return emptyList()
    }

    private suspend fun evaluateFinalGoal(
        handle: CancellationHandle,
        objective: String,
        allSteps: List<LocalTaskStep>,
        completedSteps: List<TaskStepOutcome>,
        expectedFinalPackage: String,
        expectedFinalElement: String
    ): GoalVerificationResult {
        val requiresScreenGoalCheck = expectedFinalPackage.isNotBlank() ||
            expectedFinalElement.isNotBlank() ||
            allSteps.any {
                it.action in setOf(
                    LocalTaskAction.WAIT_FOR_APP,
                    LocalTaskAction.VERIFY_FOREGROUND_APP,
                    LocalTaskAction.CLICK_UI_ELEMENT,
                    LocalTaskAction.TYPE_TEXT,
                    LocalTaskAction.SCROLL_UI
                )
            }
        if (!requiresScreenGoalCheck) {
            return GoalVerificationResult(
                status = if (completedSteps.isNotEmpty()) FinalVerificationStatus.COMPLETED else FinalVerificationStatus.FAILED,
                isVerified = completedSteps.isNotEmpty()
            )
        }

        val synthPlan = UiTaskPlan(
            id = "goal_check",
            objective = objective,
            steps = allSteps.map { it.toPlannedTaskStep() },
            createdAtMs = nowMs(),
            expectedFinalPackage = expectedFinalPackage,
            expectedFinalElement = expectedFinalElement
        )

        val snap = when (val initial = toolExecutor.readScreenRaw()) {
            is ScreenReadResult.Success -> {
                if (!initial.snapshot.isLoadingState()) {
                    initial.snapshot
                } else {
                    when (
                        val waited = UiWaitPolicy.waitForCondition(
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
                        is UiWaitOutcome.Satisfied -> waited.snapshot
                        is UiWaitOutcome.ServiceDisconnected -> return GoalVerificationResult(
                            status = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                            isVerified = false,
                            failureReason = "Final verification unavailable: ${waited.message}"
                        )
                        is UiWaitOutcome.TimedOut -> return GoalVerificationResult(
                            status = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                            isVerified = false,
                            failureReason = "Final verification unavailable: ${waited.reason}"
                        )
                    }
                }
            }
            is ScreenReadResult.Error -> {
                if (expectedFinalPackage.isBlank() && expectedFinalElement.isBlank()) {
                    return GoalVerificationResult(
                        status = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                        isVerified = false,
                        failureReason = "Final verification unavailable: ${initial.message}"
                    )
                }
                return GoalVerificationResult(
                    status = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                    isVerified = false,
                    failureReason = "Final verification unavailable: ${initial.message}"
                )
            }
        }

        return DefaultTaskGoalVerifier(expectedFinalPackage, expectedFinalElement)
            .verifyGoal(objective, snap, synthPlan)
    }

    private fun LocalTaskStep.usesAccessibilityScreen(): Boolean = action in setOf(
        LocalTaskAction.WAIT_FOR_APP,
        LocalTaskAction.VERIFY_FOREGROUND_APP,
        LocalTaskAction.READ_SCREEN,
        LocalTaskAction.FIND_UI_ELEMENT,
        LocalTaskAction.CLICK_UI_ELEMENT,
        LocalTaskAction.TYPE_TEXT,
        LocalTaskAction.SCROLL_UI
    )

    private fun sanitizeStepSummary(step: LocalTaskStep, rawSummary: String): String {
        val rawText = step.args["text"].orEmpty()
        val isSensitive = step.action == LocalTaskAction.TYPE_TEXT &&
            (SensitiveUiActionPolicy.isBlockedCredentialText(step.description, rawText) ||
                (rawText.length in 4..6 && rawText.all { it.isDigit() }))
        val redacted = if (isSensitive && rawText.isNotBlank()) {
            rawSummary.replace(rawText, "[REDACTED]")
        } else {
            rawSummary
        }
        return TaskContextSanitizer.sanitizeSummaryText(redacted)
    }

    private fun hasScreenOrTargetChanged(pre: ScreenSnapshot?, post: ScreenSnapshot): Boolean {
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

    private fun hasScrollChanged(pre: ScreenSnapshot?, post: ScreenSnapshot): Boolean {
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

    private fun isFieldVerified(step: LocalTaskStep, post: ScreenSnapshot, expectedText: String): Boolean {
        val targetViewId = step.args["view_id"].orEmpty()
        val targetHint = step.args["hint"].orEmpty()
        val targetDesc = step.args["content_description"].orEmpty()
        val targetText = step.args["target_text"].orEmpty()

        val matched = post.nodes.firstOrNull { node ->
            (targetViewId.isNotBlank() && node.viewId.contains(targetViewId, ignoreCase = true)) ||
                (targetHint.isNotBlank() && node.hintText.equals(targetHint, ignoreCase = true)) ||
                (targetDesc.isNotBlank() && node.contentDescription.equals(targetDesc, ignoreCase = true)) ||
                (targetText.isNotBlank() && node.text.contains(targetText, ignoreCase = true))
        } ?: post.nodes.firstOrNull { it.isEditable } ?: return false

        val isSensitive = matched.isPassword ||
            SensitiveUiActionPolicy.isBlockedCredentialField(matched) ||
            SensitiveUiActionPolicy.isBlockedCredentialText(targetHint, targetViewId, targetDesc) ||
            (expectedText.length in 4..6 && expectedText.all { it.isDigit() } && SensitiveUiActionPolicy.isBlockedCredentialText(targetHint, targetViewId))

        return if (isSensitive) {
            matched.text.isNotEmpty() || matched.isPassword
        } else {
            matched.text == expectedText || matched.text.contains(expectedText)
        }
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
        val safeObjective = TaskContextSanitizer.sanitizeSummaryText(objective)
        val summary = overrideSummary ?: buildString {
            if (status == TaskExecutionStatus.SUCCEEDED) {
                val total = completedSteps.size + skippedSteps.size
                append("Completed local task: $safeObjective (${completedSteps.size}/$total verified steps). ")
            } else if (status == TaskExecutionStatus.PAUSED) {
                append("Paused local task: $safeObjective. ")
            } else if (status == TaskExecutionStatus.CANCELLED) {
                append("Cancelled local task: $safeObjective. ")
            } else if (status == TaskExecutionStatus.AWAITING_CONFIRMATION) {
                append("Confirmation required before completing local task: $pendingConfirmationDescription. ")
            }
            append("Final verification status: ${finalVerificationStatus.name}. ")
            if (completedSteps.isNotEmpty()) {
                val stepsText = completedSteps.joinToString(" -> ") { outcome ->
                    if (outcome.message.isNotBlank()) {
                        "${outcome.stepIndex}. ${outcome.description} (${outcome.message})"
                    } else {
                        "${outcome.stepIndex}. ${outcome.description}"
                    }
                }
                append("Verified completed steps (${completedSteps.size}): $stepsText. ")
            } else {
                append("Verified completed steps: None. ")
            }
            if (failedStep != null) {
                append("Failed step: Step ${failedStep.stepIndex} (${failedStep.description}): ${failedStep.message}. ")
            }
            if (skippedSteps.isNotEmpty()) {
                append("Skipped steps (${skippedSteps.size}): ${skippedSteps.joinToString(", ") { it.description }}. ")
            }
            if (!verificationFailureReason.isNullOrBlank()) {
                append("Reason: $verificationFailureReason. ")
            }
            if (correctionsUsed > 0) {
                append("(Dynamic recovery attempts used: $correctionsUsed/${LocalDeviceTaskPlanner.MAX_LOCAL_RECOVERY_ATTEMPTS}).")
            }
        }.trim()

        return TaskExecutionReport(
            taskId = taskId,
            objective = objective,
            status = status,
            completedSteps = completedSteps.toList(),
            finalVerificationStatus = finalVerificationStatus,
            failedStep = failedStep,
            skippedSteps = skippedSteps.toList(),
            correctionsUsed = correctionsUsed,
            pendingConfirmationId = pendingConfirmationId,
            pendingConfirmationDescription = pendingConfirmationDescription,
            verificationFailureReason = verificationFailureReason,
            summary = summary
        )
    }
}
