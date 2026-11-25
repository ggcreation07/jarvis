package com.jarvis.app

import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisDatabase
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.network.JarvisHttpTools
import com.jarvis.app.data.security.SecureSecretStore
import com.jarvis.app.domain.brain.JarvisOrchestrator
import com.jarvis.app.domain.brain.PlannedTaskAction
import com.jarvis.app.domain.brain.PlannedTaskStep
import com.jarvis.app.domain.brain.RawTaskStep
import com.jarvis.app.domain.brain.TaskContextSanitizer
import com.jarvis.app.domain.brain.TaskContextStore
import com.jarvis.app.domain.brain.TaskExecutionStatus
import com.jarvis.app.domain.brain.TaskFollowUpClassifier
import com.jarvis.app.domain.brain.TaskFollowUpIntent
import com.jarvis.app.domain.brain.TaskLifecycleState
import com.jarvis.app.domain.brain.TaskLifecycleStateMachine
import com.jarvis.app.domain.brain.TaskPlanValidationResult
import com.jarvis.app.domain.brain.TaskStepOutcome
import com.jarvis.app.domain.brain.TaskStepStatus
import com.jarvis.app.domain.brain.ToolConfirmationPolicy
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.domain.brain.UiTaskExecutionEngine
import com.jarvis.app.domain.brain.UiTaskPlanner
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.JarvisAccessibilityService
import com.jarvis.app.platform.NodeBounds
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.ScreenInteractionStatus
import com.jarvis.app.platform.ScreenReaderEngine
import com.jarvis.app.platform.ScreenSnapshot
import com.jarvis.app.platform.TvControlClient
import com.jarvis.app.platform.UiNodeSnapshot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityPhase4cRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: JarvisDatabase
    private lateinit var repo: JarvisRepository
    private lateinit var configStore: ConfigStore
    private lateinit var executor: ToolExecutor
    private var testNowMs = 700_000L

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        JarvisAccessibilityService.resetConnectionStateForTest()
        ShadowAccessibilityNodeInfo.resetObtainedInstances()

        db = Room.inMemoryDatabaseBuilder(app, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = JarvisRepository(db.jarvisDao())
        configStore = ConfigStore(app)
        val firebaseAiService = FirebaseAiService()
        executor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = firebaseAiService,
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            nowMs = { testNowMs },
            appContext = app
        )
    }

    @After
    fun tearDown() {
        executor.cancelActiveUiTask()
        executor.taskContextStore.clear()
        JarvisAccessibilityService.resetConnectionStateForTest()
        ShadowAccessibilityNodeInfo.resetObtainedInstances()
        db.close()
    }

    @Suppress("DEPRECATION")
    private fun makeNode(
        pkg: String = "com.example.shop",
        className: String = "android.widget.TextView",
        text: String? = null,
        contentDescription: String? = null,
        hintText: String? = null,
        viewId: String? = null,
        bounds: Rect = Rect(10, 20, 400, 110),
        clickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false,
        visibleToUser: Boolean = true,
        enabled: Boolean = true
    ): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain()
        node.packageName = pkg
        node.className = className
        node.text = text
        node.contentDescription = contentDescription
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hintText != null) {
            node.hintText = hintText
        }
        if (viewId != null) {
            node.viewIdResourceName = viewId
        }
        node.setBoundsInScreen(bounds)
        node.isClickable = clickable
        node.isScrollable = scrollable
        node.isEditable = editable
        node.isPassword = password
        node.isVisibleToUser = visibleToUser
        node.isEnabled = enabled
        return node
    }

    @Suppress("DEPRECATION")
    private fun attachActiveExternalWindow(
        service: JarvisAccessibilityService,
        root: AccessibilityNodeInfo
    ) {
        val win = AccessibilityWindowInfo.obtain()
        val shadowWin = Shadows.shadowOf(win)
        shadowWin.setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowWin.setActive(true)
        shadowWin.setRoot(root)
        Shadows.shadowOf(service).setWindows(listOf(win))
    }

    private fun connectService(): JarvisAccessibilityService {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        service.onServiceConnected()
        return service
    }

    @Test
    fun `4c-1 task context creation and updates during execution`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val item = makeNode(pkg = "com.example.shop", text = "Cart Item", viewId = "com.example.shop:id/cart_item")
        val detailsBtn = makeNode(pkg = "com.example.shop", text = "Details", viewId = "com.example.shop:id/details_btn", clickable = true)
        Shadows.shadowOf(detailsBtn).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                val detailView = makeNode(pkg = "com.example.shop", text = "Detail View", viewId = "com.example.shop:id/detail_view")
                Shadows.shadowOf(root).addChild(detailView)
                true
            } else false
        }
        Shadows.shadowOf(root).addChild(item)
        Shadows.shadowOf(root).addChild(detailsBtn)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Inspect and view details",
            rawSteps = listOf(
                RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("cart_item"))),
                RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("details_btn")))
            )
        ) as TaskPlanValidationResult.Valid).plan

        var inspectedContextAtStep1 = false
        val report = executor.uiTaskExecutionEngine.executePlan(plan) { event ->
            if (event is com.jarvis.app.domain.brain.TaskProgressEvent.StepStarted && event.stepIndex == 1) {
                val ctx = executor.taskContextStore.getActiveContext()
                assertNotNull(ctx)
                assertEquals(TaskLifecycleState.RUNNING, ctx?.state)
                assertEquals(1, ctx?.currentStep?.index)
                assertEquals(1, ctx?.remainingSteps?.size)
                inspectedContextAtStep1 = true
            }
        }

        assertTrue(inspectedContextAtStep1)
        assertEquals(TaskExecutionStatus.SUCCEEDED, report.status)
        assertEquals(2, report.completedSteps.size)
        // Memory cleaned up on terminal completion
        assertNull(executor.taskContextStore.getActiveContext())
        assertNotNull(executor.taskContextStore.getLastExecutionSummary())
    }

    @Test
    fun `4c-2 completed steps preservation during multi-step execution`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val title = makeNode(pkg = "com.example.shop", text = "Shop Home", viewId = "com.example.shop:id/title")
        val btn1 = makeNode(pkg = "com.example.shop", text = "Tab 1", viewId = "com.example.shop:id/tab1", clickable = true)
        val btn2 = makeNode(pkg = "com.example.shop", text = "Tab 2", viewId = "com.example.shop:id/tab2", clickable = true)
        Shadows.shadowOf(btn1).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                val tab1Content = makeNode(pkg = "com.example.shop", text = "Tab 1 Content", viewId = "com.example.shop:id/tab1_content")
                Shadows.shadowOf(root).addChild(tab1Content)
                true
            } else false
        }
        Shadows.shadowOf(btn2).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                val tab2Content = makeNode(pkg = "com.example.shop", text = "Tab 2 Content", viewId = "com.example.shop:id/tab2_content")
                Shadows.shadowOf(root).addChild(tab2Content)
                true
            } else false
        }
        Shadows.shadowOf(root).addChild(title)
        Shadows.shadowOf(root).addChild(btn1)
        Shadows.shadowOf(root).addChild(btn2)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Tab navigation",
            rawSteps = listOf(
                RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("title"))),
                RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("tab1"))),
                RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("tab2")))
            )
        ) as TaskPlanValidationResult.Valid).plan

        val executedIndices = mutableListOf<Int>()
        val report = executor.uiTaskExecutionEngine.executePlan(plan) { event ->
            if (event is com.jarvis.app.domain.brain.TaskProgressEvent.StepStarted) {
                executedIndices.add(event.stepIndex)
            }
        }

        assertEquals(TaskExecutionStatus.SUCCEEDED, report.status)
        assertEquals(listOf(1, 2, 3), executedIndices)
        assertEquals(3, report.completedSteps.size)
    }

    @Test
    fun `4c-3 pause and resume preserves context and re-reads live screen`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val item = makeNode(pkg = "com.example.shop", text = "Product A", viewId = "com.example.shop:id/prod_a")
        val nextBtn = makeNode(pkg = "com.example.shop", text = "Next", viewId = "com.example.shop:id/next_btn", clickable = true)
        Shadows.shadowOf(nextBtn).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                val page2 = makeNode(pkg = "com.example.shop", text = "Page 2", viewId = "com.example.shop:id/page_2")
                Shadows.shadowOf(root).addChild(page2)
                true
            } else false
        }
        Shadows.shadowOf(root).addChild(item)
        Shadows.shadowOf(root).addChild(nextBtn)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Inspect and proceed",
            rawSteps = listOf(
                RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("prod_a"))),
                RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("next_btn")))
            )
        ) as TaskPlanValidationResult.Valid).plan

        val report1 = executor.uiTaskExecutionEngine.executePlan(plan) { event ->
            if (event is com.jarvis.app.domain.brain.TaskProgressEvent.StepFinished && event.outcome.stepIndex == 1) {
                executor.pauseActiveUiTask()
            }
        }

        assertEquals(TaskExecutionStatus.PAUSED, report1.status)
        val active = executor.taskContextStore.getActiveContext()
        assertNotNull(active)
        assertEquals(TaskLifecycleState.PAUSED, active?.state)
        assertEquals(1, active?.completedSteps?.size)
        assertEquals(1, active?.remainingSteps?.size)

        // Resume active paused task
        val report2 = executor.resumeActiveUiTask()
        assertEquals(TaskExecutionStatus.SUCCEEDED, report2.status)
        assertEquals(2, report2.completedSteps.size)
        assertNull(executor.taskContextStore.getActiveContext())
    }

    @Test
    fun `4c-4 confirmation and context revalidation`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val item = makeNode(pkg = "com.example.shop", text = "Item 1", viewId = "com.example.shop:id/item1")
        val payBtn = makeNode(pkg = "com.example.shop", text = "Pay Now", viewId = "com.example.shop:id/pay_btn", clickable = true)
        Shadows.shadowOf(root).addChild(item)
        Shadows.shadowOf(root).addChild(payBtn)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Order payment",
            rawSteps = listOf(
                RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("item1"))),
                RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("pay_btn")))
            )
        ) as TaskPlanValidationResult.Valid).plan

        val report = executor.uiTaskExecutionEngine.executePlan(plan)
        assertEquals(TaskExecutionStatus.AWAITING_CONFIRMATION, report.status)
        val ctx = executor.taskContextStore.getActiveContext()
        assertNotNull(ctx)
        assertEquals(TaskLifecycleState.AWAITING_CONFIRMATION, ctx?.state)
        assertEquals(1, ctx?.completedSteps?.size)
        assertEquals("com.example.shop", ctx?.lastVerifiedPackage)
        assertTrue(report.pendingConfirmationId.isNotBlank())
    }

    @Test
    fun `4c-5 unexpected app switching detected during resume safely halts task`() = runBlocking {
        val service = connectService()
        val rootShop = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val item = makeNode(pkg = "com.example.shop", text = "Item", viewId = "com.example.shop:id/item")
        Shadows.shadowOf(rootShop).addChild(item)
        attachActiveExternalWindow(service, rootShop)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Shop task",
            rawSteps = listOf(
                RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("item"))),
                RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("item")))
            )
        ) as TaskPlanValidationResult.Valid).plan

        executor.uiTaskExecutionEngine.executePlan(plan) { event ->
            if (event is com.jarvis.app.domain.brain.TaskProgressEvent.StepFinished && event.outcome.stepIndex == 1) {
                executor.pauseActiveUiTask()
            }
        }
        val pausedCtx = executor.taskContextStore.getActiveContext()
        assertNotNull(pausedCtx)
        assertEquals(TaskLifecycleState.PAUSED, pausedCtx?.state)

        // Foreign application takes foreground
        val rootOther = makeNode(pkg = "com.other.app", className = "android.widget.LinearLayout")
        attachActiveExternalWindow(service, rootOther)

        val resumeReport = executor.resumeActiveUiTask()
        assertEquals(TaskExecutionStatus.FAILED, resumeReport.status)
        assertEquals(ScreenInteractionStatus.SCREEN_CHANGED.name, resumeReport.failedStep?.resultCode)
        assertNull(executor.taskContextStore.getActiveContext())
    }

    @Test
    fun `4c-6 cancellation and memory cleanup`() = runBlocking {
        val plan = (UiTaskPlanner.validatePlan(
            objective = "Cancel test",
            rawSteps = listOf(RawTaskStep("read_screen"))
        ) as TaskPlanValidationResult.Valid).plan

        executor.taskContextStore.createTask(plan)
        assertTrue(executor.taskContextStore.hasActiveTask())

        assertTrue(executor.cancelActiveUiTask())
        assertFalse(executor.taskContextStore.hasActiveTask())
        assertNull(executor.taskContextStore.getActiveContext())
        assertNotNull(executor.taskContextStore.getLastExecutionSummary())
    }

    @Test
    fun `4c-7 sensitive data exclusion from task context memory`() = runBlocking {
        val rawStep = RawTaskStep(
            action = "type_text",
            args = mapOf(
                "hint" to JsonPrimitive("Password"),
                "text" to JsonPrimitive("Secret123456")
            ),
            description = "Enter password"
        )
        val validated = UiTaskPlanner.validatePlan("Sensitive test", listOf(rawStep)) as TaskPlanValidationResult.Valid
        val ctx = executor.taskContextStore.createTask(validated.plan)

        val storedStep = ctx.plannedSteps.first()
        val storedText = (storedStep.args["text"] as? JsonPrimitive)?.content
        assertEquals("[REDACTED]", storedText)
        assertFalse(storedStep.description.contains("Secret123456"))

        // Also verify snapshot node sanitization
        val node = UiNodeSnapshot(
            index = 1,
            depth = 1,
            packageName = "com.example.bank",
            className = "android.widget.EditText",
            text = "SensitiveCredentialText",
            contentDescription = "",
            hintText = "PIN Code",
            viewId = "com.example.bank:id/pin",
            bounds = NodeBounds(0, 0, 100, 50),
            isClickable = true,
            isScrollable = false,
            isEditable = true,
            isPassword = true,
            isVisibleToUser = true,
            isEnabled = true,
            isFocused = true
        )
        val snap = ScreenSnapshot("com.example.bank", "test", testNowMs, listOf(node), 1, false, false)
        val sanitizedSnap = TaskContextSanitizer.sanitizeSnapshot(snap)
        assertEquals("", sanitizedSnap?.nodes?.first()?.text)
        assertEquals("", sanitizedSnap?.nodes?.first()?.hintText)
    }

    @Test
    fun `4c-8 invalid lifecycle transitions strictly rejected`() {
        assertFalse(TaskLifecycleStateMachine.isValidTransition(TaskLifecycleState.CREATED, TaskLifecycleState.PAUSED))
        assertFalse(TaskLifecycleStateMachine.isValidTransition(TaskLifecycleState.COMPLETED, TaskLifecycleState.RUNNING))
        assertFalse(TaskLifecycleStateMachine.isValidTransition(TaskLifecycleState.CANCELLED, TaskLifecycleState.RUNNING))
        assertFalse(TaskLifecycleStateMachine.isValidTransition(TaskLifecycleState.FAILED, TaskLifecycleState.PAUSED))

        try {
            TaskLifecycleStateMachine.validateTransition(TaskLifecycleState.COMPLETED, TaskLifecycleState.RUNNING)
            fail("Expected IllegalStateException for invalid transition")
        } catch (_: IllegalStateException) {}
    }

    @Test
    fun `4c-9 duplicate active task prevention`() = runBlocking {
        val plan1 = (UiTaskPlanner.validatePlan(
            objective = "Plan 1",
            rawSteps = listOf(RawTaskStep("read_screen"))
        ) as TaskPlanValidationResult.Valid).plan

        val plan2 = (UiTaskPlanner.validatePlan(
            objective = "Plan 2",
            rawSteps = listOf(RawTaskStep("read_screen"))
        ) as TaskPlanValidationResult.Valid).plan

        executor.taskContextStore.createTask(plan1)
        assertTrue(executor.taskContextStore.hasActiveTask())

        try {
            executor.taskContextStore.createTask(plan2)
            fail("Expected IllegalStateException when creating duplicate active task")
        } catch (_: IllegalStateException) {}

        val rep = executor.uiTaskExecutionEngine.executePlan(plan2)
        assertEquals(TaskExecutionStatus.ALREADY_RUNNING, rep.status)
    }

    @Test
    fun `4c-10 follow-up instruction routing`() = runBlocking {
        assertEquals(TaskFollowUpIntent.CONTINUE, TaskFollowUpClassifier.classify("continue"))
        assertEquals(TaskFollowUpIntent.CONTINUE, TaskFollowUpClassifier.classify("next step karo"))
        assertEquals(TaskFollowUpIntent.CONTINUE, TaskFollowUpClassifier.classify("aage badho"))
        assertEquals(TaskFollowUpIntent.CONTINUE, TaskFollowUpClassifier.classify("pudhe ja"))

        assertEquals(TaskFollowUpIntent.CHECK_PREVIOUS, TaskFollowUpClassifier.classify("pichla step dobara check karo"))
        assertEquals(TaskFollowUpIntent.CHECK_PREVIOUS, TaskFollowUpClassifier.classify("check previous step"))
        assertEquals(TaskFollowUpIntent.CHECK_PREVIOUS, TaskFollowUpClassifier.classify("verify last step"))

        assertEquals(TaskFollowUpIntent.CANCEL, TaskFollowUpClassifier.classify("task cancel karo"))
        assertEquals(TaskFollowUpIntent.CANCEL, TaskFollowUpClassifier.classify("stop task"))
        assertEquals(TaskFollowUpIntent.CANCEL, TaskFollowUpClassifier.classify("cancel task"))

        assertEquals(TaskFollowUpIntent.PAUSE, TaskFollowUpClassifier.classify("task pause karo"))
        assertEquals(TaskFollowUpIntent.PAUSE, TaskFollowUpClassifier.classify("pause task"))

        assertEquals(TaskFollowUpIntent.NOT_FOLLOW_UP, TaskFollowUpClassifier.classify("what is the weather today"))

        // Orchestrator routing without active task
        val orchestrator = JarvisOrchestrator(
            repository = repo,
            configStore = configStore,
            secretStore = SecureSecretStore(app),
            firebaseAiService = FirebaseAiService(),
            byoAiClient = com.jarvis.app.data.ai.ByoAiClient(),
            toolExecutor = executor
        )
        val brainConfig = BrainConfig()

        val cancelEvents = orchestrator.executeTurn("task cancel karo", null, emptyList(), brainConfig).toList()
        val cancelFinish = cancelEvents.filterIsInstance<TurnProgressEvent.Finished>().first()
        assertEquals("No active task to cancel.", cancelFinish.replyText)

        val continueEvents = orchestrator.executeTurn("next step karo", null, emptyList(), brainConfig).toList()
        val continueFinish = continueEvents.filterIsInstance<TurnProgressEvent.Finished>().first()
        assertEquals("No active task to continue.", continueFinish.replyText)

        val checkEvents = orchestrator.executeTurn("pichla step dobara check karo", null, emptyList(), brainConfig).toList()
        val checkFinish = checkEvents.filterIsInstance<TurnProgressEvent.Finished>().first()
        assertEquals("No previous task step to check.", checkFinish.replyText)
    }

    @Test
    fun `4c-11 no persistent storage used for task context`() = runBlocking {
        val plan = (UiTaskPlanner.validatePlan(
            objective = "In memory only",
            rawSteps = listOf(RawTaskStep("read_screen"))
        ) as TaskPlanValidationResult.Valid).plan

        executor.taskContextStore.createTask(plan)
        assertTrue(executor.taskContextStore.hasActiveTask())

        // Confirm database does not store task context items
        val facts = repo.getFactsOnce()
        assertTrue(facts.isEmpty())

        executor.taskContextStore.clear()
        assertFalse(executor.taskContextStore.hasActiveTask())
        assertNull(executor.taskContextStore.getActiveContext())
        assertNull(executor.taskContextStore.getLastExecutionSummary())
    }
}
