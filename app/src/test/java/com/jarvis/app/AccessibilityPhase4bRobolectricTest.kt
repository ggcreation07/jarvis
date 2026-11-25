package com.jarvis.app

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.data.ai.ByoAiClient
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisDatabase
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.network.JarvisHttpTools
import com.jarvis.app.data.security.SecureSecretStore
import com.jarvis.app.domain.brain.AccessibilityInteractionBridge
import com.jarvis.app.domain.brain.AccessibilityScreenReaderBridge
import com.jarvis.app.domain.brain.JarvisOrchestrator
import com.jarvis.app.domain.brain.PlannedTaskAction
import com.jarvis.app.domain.brain.RawTaskStep
import com.jarvis.app.domain.brain.TaskExecutionStatus
import com.jarvis.app.domain.brain.TaskPlanCorrector
import com.jarvis.app.domain.brain.TaskPlanValidationResult
import com.jarvis.app.domain.brain.TaskProgressEvent
import com.jarvis.app.domain.brain.TaskStepStatus
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.domain.brain.UiTaskExecutionEngine
import com.jarvis.app.domain.brain.UiTaskPlanner
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.platform.ClickPrecheckOutcome
import com.jarvis.app.platform.ClickUiElementResult
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.FindUiElementsResult
import com.jarvis.app.platform.JarvisAccessibilityService
import com.jarvis.app.platform.NodeBounds
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.ScreenInteractionStatus
import com.jarvis.app.platform.ScreenReadErrorCode
import com.jarvis.app.platform.ScreenReadResult
import com.jarvis.app.platform.ScreenReaderEngine
import com.jarvis.app.platform.ScreenSnapshot
import com.jarvis.app.platform.ScrollUiResult
import com.jarvis.app.platform.TvControlClient
import com.jarvis.app.platform.TypeTextPrecheckOutcome
import com.jarvis.app.platform.TypeTextResult
import com.jarvis.app.platform.UiElementQuery
import com.jarvis.app.platform.UiNodeSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class AccessibilityPhase4bRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: JarvisDatabase
    private lateinit var repo: JarvisRepository
    private lateinit var configStore: ConfigStore
    private lateinit var executor: ToolExecutor
    private var testNowMs = 600_000L

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
    fun `4b-1 unexpected screen change triggers re-read and stops task safely`() = runBlocking {
        val service = connectService()
        val rootA = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val btnA = makeNode(pkg = "com.example.shop", text = "Checkout", viewId = "com.example.shop:id/checkout_btn", clickable = true)
        Shadows.shadowOf(rootA).addChild(btnA)
        attachActiveExternalWindow(service, rootA)

        val plan = (UiTaskPlanner.validatePlan(
            objective = "Checkout item",
            rawSteps = listOf(
                RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("checkout_btn"))),
                RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("checkout_btn")))
            )
        ) as TaskPlanValidationResult.Valid).plan

        var screenSwapped = false
        val report = executor.uiTaskExecutionEngine.executePlan(plan) { event ->
            if (event is TaskProgressEvent.StepFinished && event.outcome.stepIndex == 1) {
                val rootPopup = makeNode(pkg = "com.example.popup", className = "android.widget.LinearLayout")
                val popupTitle = makeNode(pkg = "com.example.popup", text = "Ad PopUp", viewId = "com.example.popup:id/ad_title")
                Shadows.shadowOf(rootPopup).addChild(popupTitle)
                attachActiveExternalWindow(service, rootPopup)
                screenSwapped = true
            }
        }
        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertEquals(ScreenInteractionStatus.SCREEN_CHANGED.name, report.failedStep?.resultCode)
        assertTrue(screenSwapped)
        assertEquals(1, report.completedSteps.size)
    }

    @Test
    fun `4b-2 target disappearing re-reads screen and attempts bounded replan without blind repetition`() = runBlocking {
        var attempts = 0
        val dynamicCorrector = TaskPlanCorrector { failedStep, failure, snapshot, _ ->
            attempts++
            assertEquals("find_ui_element", failedStep.action.toolName)
            assertEquals(ScreenInteractionStatus.NOT_FOUND.name, failure.resultCode)
            RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("fallback_search")))
        }

        val stubBridge = object : AccessibilityInteractionBridge {
            override suspend fun findElements(query: UiElementQuery): FindUiElementsResult =
                FindUiElementsResult(ScreenInteractionStatus.NOT_FOUND, "Target missing", "com.example.shop")
            override suspend fun inspectForClick(query: UiElementQuery): ClickPrecheckOutcome =
                ClickPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun clickElement(query: UiElementQuery): ClickUiElementResult =
                ClickUiElementResult(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun inspectForTypeText(query: UiElementQuery, textToEnter: String): TypeTextPrecheckOutcome =
                TypeTextPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun typeText(query: UiElementQuery, textToEnter: String): TypeTextResult =
                TypeTextResult(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun scrollContainer(query: UiElementQuery, direction: String): ScrollUiResult =
                ScrollUiResult(ScreenInteractionStatus.NOT_FOUND, "N/A")
        }
        val customExecutor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = FirebaseAiService(),
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            appContext = app,
            interactionBridge = stubBridge,
            screenReaderBridge = object : AccessibilityScreenReaderBridge {
                override suspend fun readScreen(): ScreenReadResult =
                    ScreenReadResult.Success(ScreenSnapshot("com.example.shop", "stub", testNowMs, emptyList(), 0, false, false))
            }
        )
        val engine = UiTaskExecutionEngine(customExecutor, planCorrector = dynamicCorrector)
        val plan = (UiTaskPlanner.validatePlan(
            objective = "Find primary search",
            rawSteps = listOf(RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("primary_search"))))
        ) as TaskPlanValidationResult.Valid).plan

        val report = engine.executePlan(plan)
        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertEquals(1, report.correctionsUsed)
        assertEquals(2, attempts)
    }

    @Test
    fun `4b-3 app closing and accessibility disconnect stop task safely`() = runBlocking {
        JarvisAccessibilityService.resetConnectionStateForTest()
        val plan = (UiTaskPlanner.validatePlan(
            objective = "Read screen while disconnected",
            rawSteps = listOf(RawTaskStep("read_screen"))
        ) as TaskPlanValidationResult.Valid).plan

        val disconnectedExecutor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = FirebaseAiService(),
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            appContext = app
        )
        val report = disconnectedExecutor.uiTaskExecutionEngine.executePlan(plan)
        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertNotNull(report.failedStep)
    }

    @Test
    fun `4b-4 maximum 2 recovery replans per task enforced`() = runBlocking {
        var correctionCount = 0
        val infiniteCorrector = TaskPlanCorrector { _, _, _, _ ->
            correctionCount++
            RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("alt_$correctionCount")))
        }
        val stubBridge = object : AccessibilityInteractionBridge {
            override suspend fun findElements(query: UiElementQuery): FindUiElementsResult =
                FindUiElementsResult(ScreenInteractionStatus.NOT_FOUND, "Not found", "com.example.app")
            override suspend fun inspectForClick(query: UiElementQuery): ClickPrecheckOutcome =
                ClickPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun clickElement(query: UiElementQuery): ClickUiElementResult =
                ClickUiElementResult(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun inspectForTypeText(query: UiElementQuery, textToEnter: String): TypeTextPrecheckOutcome =
                TypeTextPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun typeText(query: UiElementQuery, textToEnter: String): TypeTextResult =
                TypeTextResult(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun scrollContainer(query: UiElementQuery, direction: String): ScrollUiResult =
                ScrollUiResult(ScreenInteractionStatus.NOT_FOUND, "N/A")
        }
        val customExecutor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = FirebaseAiService(),
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            appContext = app,
            interactionBridge = stubBridge
        )
        val engine = UiTaskExecutionEngine(customExecutor, planCorrector = infiniteCorrector)
        val plan = (UiTaskPlanner.validatePlan(
            objective = "Max corrections test",
            rawSteps = listOf(RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("missing"))))
        ) as TaskPlanValidationResult.Valid).plan

        val report = engine.executePlan(plan)
        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertEquals(UiTaskPlanner.MAX_PLAN_CORRECTIONS, report.correctionsUsed)
        assertEquals(2, correctionCount)
    }

    @Test
    fun `4b-5 completed steps preserved and duplicate or oscillation replans rejected`() = runBlocking {
        var callCount = 0
        val loopingCorrector = TaskPlanCorrector { failedStep, _, _, _ ->
            callCount++
            RawTaskStep(failedStep.action.toolName, failedStep.args)
        }
        val stubBridge = object : AccessibilityInteractionBridge {
            override suspend fun findElements(query: UiElementQuery): FindUiElementsResult =
                FindUiElementsResult(ScreenInteractionStatus.NOT_FOUND, "Not found", "com.example.app")
            override suspend fun inspectForClick(query: UiElementQuery): ClickPrecheckOutcome =
                ClickPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun clickElement(query: UiElementQuery): ClickUiElementResult =
                ClickUiElementResult(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun inspectForTypeText(query: UiElementQuery, textToEnter: String): TypeTextPrecheckOutcome =
                TypeTextPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun typeText(query: UiElementQuery, textToEnter: String): TypeTextResult =
                TypeTextResult(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun scrollContainer(query: UiElementQuery, direction: String): ScrollUiResult =
                ScrollUiResult(ScreenInteractionStatus.NOT_FOUND, "N/A")
        }
        val customExecutor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = FirebaseAiService(),
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            appContext = app,
            interactionBridge = stubBridge
        )
        val engine = UiTaskExecutionEngine(customExecutor, planCorrector = loopingCorrector)
        val plan = (UiTaskPlanner.validatePlan(
            objective = "No blind retries",
            rawSteps = listOf(RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("target"))))
        ) as TaskPlanValidationResult.Valid).plan

        val report = engine.executePlan(plan)
        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertEquals(0, report.correctionsUsed)
    }

    @Test
    fun `4b-6 confirmation gates revalidation and untrusted screen protections preserved during recovery`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.secure", className = "android.widget.LinearLayout")
        val passwordField = makeNode(
            pkg = "com.example.secure",
            hintText = "Password",
            viewId = "com.example.secure:id/pwd",
            editable = true,
            password = true
        )
        Shadows.shadowOf(root).addChild(passwordField)
        attachActiveExternalWindow(service, root)

        val res = executor.execute(
            "type_text",
            mapOf("view_id" to JsonPrimitive("pwd"), "text" to JsonPrimitive("Secret123"))
        ) {}
        assertFalse(res.ok)
        assertEquals(ScreenInteractionStatus.PASSWORD_FIELD.name, res.payload["result_code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `4b-7 cancellation during recovery stops task immediately`() = runBlocking {
        val step1Entered = CompletableDeferred<Unit>()
        val unblockStep1 = CompletableDeferred<Unit>()
        val bridge = object : AccessibilityInteractionBridge {
            override suspend fun findElements(query: UiElementQuery): FindUiElementsResult {
                step1Entered.complete(Unit)
                unblockStep1.await()
                return FindUiElementsResult(ScreenInteractionStatus.NOT_FOUND, "Not found", "com.example.app")
            }
            override suspend fun inspectForClick(query: UiElementQuery): ClickPrecheckOutcome =
                ClickPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun clickElement(query: UiElementQuery): ClickUiElementResult =
                ClickUiElementResult(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun inspectForTypeText(query: UiElementQuery, textToEnter: String): TypeTextPrecheckOutcome =
                TypeTextPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun typeText(query: UiElementQuery, textToEnter: String): TypeTextResult =
                TypeTextResult(ScreenInteractionStatus.NOT_FOUND, "N/A")
            override suspend fun scrollContainer(query: UiElementQuery, direction: String): ScrollUiResult =
                ScrollUiResult(ScreenInteractionStatus.NOT_FOUND, "N/A")
        }
        val customExecutor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = FirebaseAiService(),
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            appContext = app,
            interactionBridge = bridge
        )
        val plan = (UiTaskPlanner.validatePlan(
            objective = "Cancel during failure",
            rawSteps = listOf(RawTaskStep("find_ui_element", mapOf("view_id" to JsonPrimitive("missing"))))
        ) as TaskPlanValidationResult.Valid).plan

        val job = async { customExecutor.uiTaskExecutionEngine.executePlan(plan) }
        step1Entered.await()
        assertTrue(customExecutor.cancelActiveUiTask())
        unblockStep1.complete(Unit)

        val report = job.await()
        assertEquals(TaskExecutionStatus.CANCELLED, report.status)
    }
}
