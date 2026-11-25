package com.jarvis.app

import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisDatabase
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.network.JarvisHttpTools
import com.jarvis.app.domain.brain.FinalVerificationStatus
import com.jarvis.app.domain.brain.GoalVerificationResult
import com.jarvis.app.domain.brain.PlannedTaskAction
import com.jarvis.app.domain.brain.PlannedTaskStep
import com.jarvis.app.domain.brain.RawTaskStep
import com.jarvis.app.domain.brain.TaskExecutionStatus
import com.jarvis.app.domain.brain.TaskGoalVerifier
import com.jarvis.app.domain.brain.TaskLifecycleState
import com.jarvis.app.domain.brain.TaskPlanValidationResult
import com.jarvis.app.domain.brain.TaskProgressEvent
import com.jarvis.app.domain.brain.TaskStepStatus
import com.jarvis.app.domain.brain.ToolConfirmationPolicy
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.domain.brain.UiTaskExecutionEngine
import com.jarvis.app.domain.brain.UiTaskPlan
import com.jarvis.app.domain.brain.UiTaskPlanner
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.JarvisAccessibilityService
import com.jarvis.app.platform.NodeBounds
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.ScreenInteractionStatus
import com.jarvis.app.platform.ScreenReadResult
import com.jarvis.app.platform.ScreenReaderEngine
import com.jarvis.app.platform.ScreenSnapshot
import com.jarvis.app.platform.TvControlClient
import com.jarvis.app.platform.UiNodeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class AccessibilityPhase4dRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: JarvisDatabase
    private lateinit var repo: JarvisRepository
    private lateinit var configStore: ConfigStore
    private lateinit var executor: ToolExecutor
    private var testNowMs = 800_000L

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
    fun `4d-1 click action returns true but screen does not change fails verification`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val btn = makeNode(pkg = "com.example.shop", text = "Refresh", viewId = "com.example.shop:id/btn_refresh", clickable = true)
        // Action is accepted (returns true), but the screen does not change at all
        Shadows.shadowOf(btn).setOnPerformActionListener { action, _ -> action == AccessibilityNodeInfo.ACTION_CLICK }
        Shadows.shadowOf(root).addChild(btn)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Refresh content",
            rawSteps = listOf(RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("btn_refresh"))))
        ) as TaskPlanValidationResult.Valid).plan

        val report = executor.uiTaskExecutionEngine.executePlan(plan)
        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertEquals(FinalVerificationStatus.FAILED, report.finalVerificationStatus)
        assertNotNull(report.failedStep)
        assertFalse(report.failedStep!!.verified)
        assertEquals(ScreenInteractionStatus.ACTION_FAILED.name, report.failedStep!!.resultCode)
        assertTrue(report.summary.contains("unchanged"))
    }

    @Test
    fun `4d-2 successful click verification when screen or target state changes`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val btn = makeNode(pkg = "com.example.shop", text = "Expand", viewId = "com.example.shop:id/btn_expand", clickable = true)
        Shadows.shadowOf(btn).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                val expandedView = makeNode(pkg = "com.example.shop", text = "Expanded Content", viewId = "com.example.shop:id/expanded_text")
                Shadows.shadowOf(root).addChild(expandedView)
                true
            } else false
        }
        Shadows.shadowOf(root).addChild(btn)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Expand details",
            rawSteps = listOf(RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("btn_expand"))))
        ) as TaskPlanValidationResult.Valid).plan

        val report = executor.uiTaskExecutionEngine.executePlan(plan)
        assertEquals(TaskExecutionStatus.SUCCEEDED, report.status)
        assertEquals(FinalVerificationStatus.COMPLETED, report.finalVerificationStatus)
        assertEquals(1, report.completedSteps.size)
        assertTrue(report.completedSteps[0].verified)
    }

    @Test
    fun `4d-3 text input verification on fresh accessibility hierarchy`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.notes", className = "android.widget.LinearLayout")
        val field = makeNode(
            pkg = "com.example.notes",
            className = "android.widget.EditText",
            hintText = "Title",
            viewId = "com.example.notes:id/note_title",
            editable = true,
            clickable = true
        )
        Shadows.shadowOf(field).setOnPerformActionListener { action, args: Bundle? ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                val txt = args?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString()
                field.text = txt
                true
            } else false
        }
        Shadows.shadowOf(root).addChild(field)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Enter note title",
            rawSteps = listOf(
                RawTaskStep(
                    "type_text",
                    mapOf("view_id" to JsonPrimitive("note_title"), "text" to JsonPrimitive("Weekly Meeting"))
                )
            )
        ) as TaskPlanValidationResult.Valid).plan

        val report = executor.uiTaskExecutionEngine.executePlan(plan)
        assertEquals(TaskExecutionStatus.SUCCEEDED, report.status)
        assertEquals(FinalVerificationStatus.COMPLETED, report.finalVerificationStatus)
        assertEquals(1, report.completedSteps.size)
        assertTrue(report.completedSteps[0].verified)
        assertEquals("Weekly Meeting", field.text.toString())
    }

    @Test
    fun `4d-4 sensitive text redaction never exposes sensitive typed values in reports`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.wallet", className = "android.widget.LinearLayout")
        val memoField = makeNode(
            pkg = "com.example.wallet",
            className = "android.widget.EditText",
            hintText = "Memo or reference",
            viewId = "com.example.wallet:id/memo_field",
            editable = true,
            clickable = true
        )
        Shadows.shadowOf(memoField).setOnPerformActionListener { action, args: Bundle? ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                val txt = args?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString()
                memoField.text = txt
                true
            } else false
        }
        Shadows.shadowOf(root).addChild(memoField)
        attachActiveExternalWindow(service, root)

        val secretPin = "987654"
        val plan = (UiTaskPlanner.validatePlan(
            objective = "Enter secret code",
            rawSteps = listOf(
                RawTaskStep(
                    "type_text",
                    mapOf("view_id" to JsonPrimitive("memo_field"), "text" to JsonPrimitive(secretPin))
                )
            )
        ) as TaskPlanValidationResult.Valid).plan

        val report = executor.uiTaskExecutionEngine.executePlan(plan)
        assertEquals(TaskExecutionStatus.SUCCEEDED, report.status)
        assertFalse("Secret PIN must never appear in report summary", report.summary.contains(secretPin))
        assertEquals(1, report.completedSteps.size)
        assertTrue(report.completedSteps[0].verified)
        assertFalse("Secret PIN must never appear in step message", report.completedSteps[0].message.contains(secretPin))
        assertFalse("Secret PIN must never appear in step description", report.completedSteps[0].description.contains(secretPin))
        assertEquals("987654", memoField.text.toString())
    }

    @Test
    fun `4d-5 scroll verification detects boundary and verifies content shift`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.feed", className = "android.widget.LinearLayout")
        val list = makeNode(
            pkg = "com.example.feed",
            className = "android.widget.ListView",
            viewId = "com.example.feed:id/feed_list",
            scrollable = true
        )
        val item1 = makeNode(pkg = "com.example.feed", text = "Item 1", bounds = Rect(10, 10, 200, 60))
        var shiftApplied = false
        Shadows.shadowOf(list).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                if (shiftApplied) {
                    item1.setBoundsInScreen(Rect(10, -50, 200, 0))
                }
                true
            } else false
        }
        Shadows.shadowOf(list).addChild(item1)
        Shadows.shadowOf(root).addChild(list)
        attachActiveExternalWindow(service, root)

        // Case A: Boundary scroll (performAction=true, but bounds and items unchanged) -> fails verification
        shiftApplied = false
        val planFail = (UiTaskPlanner.validatePlan(
            objective = "Scroll feed at boundary",
            rawSteps = listOf(RawTaskStep("scroll_ui", mapOf("direction" to JsonPrimitive("DOWN"))))
        ) as TaskPlanValidationResult.Valid).plan

        val reportFail = executor.uiTaskExecutionEngine.executePlan(planFail)
        assertEquals(TaskExecutionStatus.FAILED, reportFail.status)
        assertEquals(ScreenInteractionStatus.ACTION_FAILED.name, reportFail.failedStep?.resultCode)
        assertFalse(reportFail.failedStep?.verified ?: true)

        // Case B: Content shifted -> succeeds verification
        shiftApplied = true
        val planSuccess = (UiTaskPlanner.validatePlan(
            objective = "Scroll feed with shift",
            rawSteps = listOf(RawTaskStep("scroll_ui", mapOf("direction" to JsonPrimitive("DOWN"))))
        ) as TaskPlanValidationResult.Valid).plan

        val reportSuccess = executor.uiTaskExecutionEngine.executePlan(planSuccess)
        assertEquals(TaskExecutionStatus.SUCCEEDED, reportSuccess.status)
        assertTrue(reportSuccess.completedSteps[0].verified)
    }

    @Test
    fun `4d-6 unexpected screen changes detected via fresh hierarchy and halts task`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val item = makeNode(pkg = "com.example.shop", text = "Product", viewId = "com.example.shop:id/prod")
        Shadows.shadowOf(root).addChild(item)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Shop flow",
            rawSteps = listOf(
                RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("prod"))),
                RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("prod")))
            )
        ) as TaskPlanValidationResult.Valid).plan

        val report = executor.uiTaskExecutionEngine.executePlan(plan) { event ->
            if (event is TaskProgressEvent.StepFinished && event.outcome.stepIndex == 1) {
                // Unexpected error dialog overlay takes foreground
                val errorDialogRoot = makeNode(pkg = "com.example.system", className = "android.app.Dialog")
                val errorText = makeNode(pkg = "com.example.system", text = "Network Error", viewId = "com.example.system:id/error_msg")
                Shadows.shadowOf(errorDialogRoot).addChild(errorText)
                attachActiveExternalWindow(service, errorDialogRoot)
            }
        }

        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertEquals(ScreenInteractionStatus.SCREEN_CHANGED.name, report.failedStep?.resultCode)
        assertEquals(FinalVerificationStatus.PARTIALLY_COMPLETED, report.finalVerificationStatus)
    }

    @Test
    fun `4d-7 app switching detected during verification halts task safely`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val btn = makeNode(pkg = "com.example.shop", text = "View Cart", viewId = "com.example.shop:id/cart_btn", clickable = true)
        Shadows.shadowOf(btn).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                // Unexpected switch to Home Launcher
                val launcherRoot = makeNode(pkg = "com.google.android.apps.nexuslauncher", className = "android.widget.FrameLayout")
                attachActiveExternalWindow(service, launcherRoot)
                true
            } else false
        }
        Shadows.shadowOf(root).addChild(btn)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Open cart details",
            rawSteps = listOf(RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("cart_btn")))),
            expectedFinalPackage = "com.example.shop"
        ) as TaskPlanValidationResult.Valid).plan

        val report = executor.uiTaskExecutionEngine.executePlan(plan)
        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertEquals(ScreenInteractionStatus.SCREEN_CHANGED.name, report.failedStep?.resultCode)
    }

    @Test
    fun `4d-8 maximum 2 verification attempts per action enforced without blind repetition`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        var clickCount = 0
        val btn = makeNode(pkg = "com.example.shop", text = "Filter Items", viewId = "com.example.shop:id/btn_filter", clickable = true)
        Shadows.shadowOf(btn).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                clickCount++
                true
            } else false
        }
        Shadows.shadowOf(root).addChild(btn)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Filter product catalog",
            rawSteps = listOf(RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("btn_filter"))))
        ) as TaskPlanValidationResult.Valid).plan

        val report = executor.uiTaskExecutionEngine.executePlan(plan)
        assertEquals(TaskExecutionStatus.FAILED, report.status)
        // Click action was executed only once; never blindly repeated
        assertEquals(1, clickCount)
        assertEquals(2, report.failedStep?.verificationAttempts)
        assertTrue(report.failedStep?.message?.contains("2 verification attempts") ?: false)
    }

    @Test
    fun `4d-9 final goal mismatch prevents false completion and reports failure`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val btn = makeNode(pkg = "com.example.shop", text = "Next", viewId = "com.example.shop:id/btn_next", clickable = true)
        Shadows.shadowOf(btn).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                val step2 = makeNode(pkg = "com.example.shop", text = "Intermediate Screen", viewId = "com.example.shop:id/step2")
                Shadows.shadowOf(root).addChild(step2)
                true
            } else false
        }
        Shadows.shadowOf(root).addChild(btn)
        attachActiveExternalWindow(service, root)

        // Goal requires "OrderConfirmed" element on final screen, which is absent
        val plan = (UiTaskPlanner.validatePlan(
            objective = "Complete checkout and verify confirmation",
            rawSteps = listOf(RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("btn_next")))),
            expectedFinalElement = "OrderConfirmed"
        ) as TaskPlanValidationResult.Valid).plan

        val report = executor.uiTaskExecutionEngine.executePlan(plan)
        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertEquals(FinalVerificationStatus.FAILED, report.finalVerificationStatus)
        assertTrue(report.summary.contains("OrderConfirmed"))
    }

    @Test
    fun `4d-10 partial completion preserves completed failed and skipped step history`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val item = makeNode(pkg = "com.example.shop", text = "Item A", viewId = "com.example.shop:id/item_a")
        val staticBtn = makeNode(pkg = "com.example.shop", text = "Static Action", viewId = "com.example.shop:id/static_btn", clickable = true)
        Shadows.shadowOf(root).addChild(item)
        Shadows.shadowOf(root).addChild(staticBtn)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "3-step task",
            rawSteps = listOf(
                RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("item_a"))),
                RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("static_btn"))),
                RawTaskStep("read_screen")
            )
        ) as TaskPlanValidationResult.Valid).plan

        val report = executor.uiTaskExecutionEngine.executePlan(plan)
        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertEquals(FinalVerificationStatus.PARTIALLY_COMPLETED, report.finalVerificationStatus)
        assertEquals(1, report.completedSteps.size)
        assertEquals(1, report.completedSteps[0].stepIndex)
        assertNotNull(report.failedStep)
        assertEquals(2, report.failedStep!!.stepIndex)
        assertEquals(1, report.skippedSteps.size)
        assertEquals(3, report.skippedSteps[0].index)
    }

    @Test
    fun `4d-11 verification unavailable reported truthfully when screen is unreadable`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val btn = makeNode(pkg = "com.example.shop", text = "Proceed", viewId = "com.example.shop:id/btn_proceed", clickable = true)
        Shadows.shadowOf(btn).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                val nextScreen = makeNode(pkg = "com.example.shop", text = "Done", viewId = "com.example.shop:id/done")
                Shadows.shadowOf(root).addChild(nextScreen)
                true
            } else false
        }
        Shadows.shadowOf(root).addChild(btn)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Finish and verify",
            rawSteps = listOf(RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("btn_proceed"))))
        ) as TaskPlanValidationResult.Valid).plan

        var goalCheckRan = false
        val disconnectGoalVerifier = TaskGoalVerifier { _, _, _ ->
            goalCheckRan = true
            GoalVerificationResult(
                status = FinalVerificationStatus.VERIFICATION_UNAVAILABLE,
                isVerified = false,
                failureReason = "Accessibility service connection lost during goal verification."
            )
        }
        val customEngine = UiTaskExecutionEngine(executor, goalVerifier = disconnectGoalVerifier)

        val report = customEngine.executePlan(plan)
        assertTrue(goalCheckRan)
        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertEquals(FinalVerificationStatus.VERIFICATION_UNAVAILABLE, report.finalVerificationStatus)
        assertTrue(report.summary.contains("VERIFICATION_UNAVAILABLE"))
    }

    @Test
    fun `4d-12 confirmation gate preservation during high-impact actions`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val payBtn = makeNode(pkg = "com.example.shop", text = "Pay Now $50", viewId = "com.example.shop:id/pay_now", clickable = true)
        Shadows.shadowOf(root).addChild(payBtn)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Submit payment",
            rawSteps = listOf(RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("pay_now"))))
        ) as TaskPlanValidationResult.Valid).plan

        val report = executor.uiTaskExecutionEngine.executePlan(plan)
        assertEquals(TaskExecutionStatus.AWAITING_CONFIRMATION, report.status)
        assertEquals(FinalVerificationStatus.FAILED, report.finalVerificationStatus)
        assertTrue(report.pendingConfirmationId.isNotBlank())
        assertEquals(0, Shadows.shadowOf(payBtn).performedActions.size)
    }

    @Test
    fun `4d-13 cancellation during verification stops task immediately and cleans up`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val btn = makeNode(pkg = "com.example.shop", text = "Process", viewId = "com.example.shop:id/btn_proc", clickable = true)
        val clickStarted = CompletableDeferred<Unit>()
        val unblockClick = CompletableDeferred<Unit>()

        Shadows.shadowOf(btn).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                clickStarted.complete(Unit)
                runBlocking { unblockClick.await() }
                true
            } else false
        }
        Shadows.shadowOf(root).addChild(btn)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Cancellable verification task",
            rawSteps = listOf(RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("btn_proc"))))
        ) as TaskPlanValidationResult.Valid).plan

        val job = async { executor.uiTaskExecutionEngine.executePlan(plan) }
        clickStarted.await()
        assertTrue(executor.cancelActiveUiTask())
        unblockClick.complete(Unit)

        val report = job.await()
        assertEquals(TaskExecutionStatus.CANCELLED, report.status)
        assertFalse(executor.taskContextStore.hasActiveTask())
    }

    @Test
    fun `4d-14 no false success reports when verification is unavailable or unverified`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val btn = makeNode(pkg = "com.example.shop", text = "Unverified Action", viewId = "com.example.shop:id/unverified", clickable = true)
        Shadows.shadowOf(root).addChild(btn)
        attachActiveExternalWindow(service, root)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Zero proof task",
            rawSteps = listOf(RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("unverified"))))
        ) as TaskPlanValidationResult.Valid).plan

        val report = executor.uiTaskExecutionEngine.executePlan(plan)
        assertNotEquals("Must never claim SUCCEEDED without verified state change", TaskExecutionStatus.SUCCEEDED, report.status)
        assertNotEquals("Must never claim COMPLETED without verification", FinalVerificationStatus.COMPLETED, report.finalVerificationStatus)
        assertTrue(report.summary.contains("Final verification status: FAILED"))
    }
}
