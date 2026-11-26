package com.jarvis.app

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.ai.type.FunctionCallPart
import com.jarvis.app.data.ai.AiStreamEvent
import com.jarvis.app.data.ai.ByoAiClient
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.ai.ToolCallExecutionResult
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisDatabase
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.network.JarvisHttpTools
import com.jarvis.app.data.security.SecureSecretStore
import com.jarvis.app.domain.brain.AccessibilityInteractionBridge
import com.jarvis.app.domain.brain.AccessibilityNavigationBridge
import com.jarvis.app.domain.brain.AccessibilityScreenReaderBridge
import com.jarvis.app.domain.brain.JarvisOrchestrator
import com.jarvis.app.domain.brain.LocalDeviceTaskPlanner
import com.jarvis.app.domain.brain.LocalTaskAction
import com.jarvis.app.domain.brain.LocalTaskPlanResult
import com.jarvis.app.domain.brain.TaskExecutionStatus
import com.jarvis.app.domain.brain.TaskLifecycleState
import com.jarvis.app.domain.brain.TaskPlanErrorCode
import com.jarvis.app.domain.brain.TaskProgressEvent
import com.jarvis.app.domain.brain.TaskStepStatus
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.platform.ClickInspectionCandidate
import com.jarvis.app.platform.ClickPrecheckOutcome
import com.jarvis.app.platform.ClickUiElementResult
import com.jarvis.app.platform.DeviceActionId
import com.jarvis.app.platform.DeviceCapabilityRegistry
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.FindUiElementsResult
import com.jarvis.app.platform.JarvisAccessibilityService
import com.jarvis.app.platform.MatchedUiElement
import com.jarvis.app.platform.NodeBounds
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.ScreenInteractionStatus
import com.jarvis.app.platform.ScreenReadResult
import com.jarvis.app.platform.ScreenReaderEngine
import com.jarvis.app.platform.ScreenSnapshot
import com.jarvis.app.platform.ScrollUiResult
import com.jarvis.app.platform.TvControlClient
import com.jarvis.app.platform.TypeTextPrecheckOutcome
import com.jarvis.app.platform.TypeTextResult
import com.jarvis.app.platform.UiElementQuery
import com.jarvis.app.platform.UiNodeSnapshot
import com.jarvis.app.platform.VolumeStateSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowCameraCharacteristics
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase6bLocalAutomationRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: JarvisDatabase
    private lateinit var repo: JarvisRepository
    private lateinit var configStore: ConfigStore
    private lateinit var secretStore: SecureSecretStore
    private var testNowMs = 6_000_000L

    private class SpyFirebaseAiService(
        private val available: Boolean = false
    ) : FirebaseAiService() {
        val streamCalls = AtomicInteger(0)

        override fun isAvailable(): Boolean = available

        override fun streamWithTools(
            modelName: String,
            systemPrompt: String,
            history: List<Pair<String, String>>,
            userMessage: String,
            attachedImage: android.graphics.Bitmap?,
            pcPaired: Boolean,
            tvPaired: Boolean,
            executeTool: suspend (FunctionCallPart) -> ToolCallExecutionResult
        ): Flow<AiStreamEvent> = flow {
            streamCalls.incrementAndGet()
            emit(AiStreamEvent.Completed("AI spy reply"))
        }
    }

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
        app.getSharedPreferences("jarvis_secure_secrets_v1", Context.MODE_PRIVATE).edit().clear().commit()
        secretStore = SecureSecretStore(app)
    }

    @After
    fun tearDown() {
        app.getSharedPreferences("jarvis_secure_secrets_v1", Context.MODE_PRIVATE).edit().clear().commit()
        JarvisAccessibilityService.resetConnectionStateForTest()
        ShadowAccessibilityNodeInfo.resetObtainedInstances()
        db.close()
    }

    private fun registerFlashCamera(id: String = "0", hasFlash: Boolean = true) {
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadows.shadowOf(characteristics).set(CameraCharacteristics.FLASH_INFO_AVAILABLE, hasFlash)
        Shadows.shadowOf(cm).addCamera(id, characteristics)
    }

    private fun registerLaunchableApp(packageName: String, appLabel: String) {
        val spm = Shadows.shadowOf(app.packageManager)
        val component = ComponentName(packageName, "$packageName.MainActivity")
        val activityInfo = android.content.pm.ActivityInfo().apply {
            this.packageName = packageName
            this.name = "$packageName.MainActivity"
            this.applicationInfo = android.content.pm.ApplicationInfo().apply {
                this.packageName = packageName
                this.nonLocalizedLabel = appLabel
            }
            this.nonLocalizedLabel = appLabel
        }
        Shadow.newInstanceOf(Activity::class.java)
        spm.addOrUpdateActivity(activityInfo)
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            this.activityInfo = activityInfo
            this.nonLocalizedLabel = appLabel
        }
        spm.addResolveInfoForIntent(mainIntent, resolveInfo)
        spm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )
    }

    private fun connectService(): JarvisAccessibilityService {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        service.onServiceConnected()
        return service
    }

    private fun nodeSnap(
        index: Int = 0,
        pkg: String = "com.example.notes",
        className: String = "android.widget.TextView",
        text: String = "",
        contentDesc: String = "",
        hint: String = "",
        viewId: String = "",
        clickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false
    ) = UiNodeSnapshot(
        index = index,
        depth = 0,
        packageName = pkg,
        className = className,
        text = text,
        contentDescription = contentDesc,
        hintText = hint,
        viewId = viewId,
        bounds = NodeBounds(0, index * 100, 500, index * 100 + 80),
        isClickable = clickable,
        isScrollable = scrollable,
        isEditable = editable,
        isPassword = password,
        isVisibleToUser = true,
        isEnabled = true,
        isFocused = false
    )

    private fun snapshotOf(pkg: String, vararg nodes: UiNodeSnapshot) = ScreenSnapshot(
        packageName = pkg,
        source = "test_bridge",
        capturedAtMs = testNowMs,
        nodes = nodes.toList(),
        maxDepthReached = 0,
        truncatedByDepth = false,
        truncatedByNodeLimit = false
    )

    private fun buildOrchestrator(
        spyAi: SpyFirebaseAiService = SpyFirebaseAiService(available = false),
        deviceController: DeviceController = DeviceController(app),
        registry: DeviceCapabilityRegistry = DeviceCapabilityRegistry(app),
        navBridge: AccessibilityNavigationBridge? = null,
        screenBridge: AccessibilityScreenReaderBridge? = null,
        interactionBridge: AccessibilityInteractionBridge? = null
    ): Pair<ToolExecutor, JarvisOrchestrator> {
        val executor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = deviceController,
            firebaseAiService = spyAi,
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            nowMs = { testNowMs },
            appContext = app,
            accessibilityBridge = navBridge,
            screenReaderBridge = screenBridge,
            interactionBridge = interactionBridge,
            capabilityRegistry = registry
        )
        val orch = JarvisOrchestrator(
            repository = repo,
            configStore = configStore,
            secretStore = secretStore,
            firebaseAiService = spyAi,
            byoAiClient = ByoAiClient(),
            toolExecutor = executor,
            capabilityRegistry = registry,
            isFirebaseAiAvailable = { false }
        )
        return executor to orch
    }

    @Test
    fun `6b-1 planner handles multi-step English Hindi Marathi Hinglish commands and pronoun click without AI`() {
        registerFlashCamera(hasFlash = true)
        registerLaunchableApp("com.example.notes", "Notes")
        connectService()
        val registry = DeviceCapabilityRegistry(app)
        val controller = DeviceController(app)
        val planner = LocalDeviceTaskPlanner(
            capabilityRegistry = registry,
            appResolver = { controller.resolveApplication(it) }
        )

        // Open -> wait -> verify foreground (English)
        val res1 = planner.planFromUserRequest("open Notes, wait for it to load, and verify app is in foreground")
        assertTrue(res1 is LocalTaskPlanResult.Planned)
        val p1 = (res1 as LocalTaskPlanResult.Planned).plan
        assertEquals(
            listOf(LocalTaskAction.OPEN_APP, LocalTaskAction.WAIT_FOR_APP, LocalTaskAction.VERIFY_FOREGROUND_APP),
            p1.steps.map { it.action }
        )
        assertEquals("com.example.notes", p1.expectedFinalPackage)

        // Hinglish: read -> find -> click it
        val res2 = planner.planFromUserRequest("screen padho, Save dhundo aur us par click karo")
        assertTrue(res2 is LocalTaskPlanResult.Planned)
        val p2 = (res2 as LocalTaskPlanResult.Planned).plan
        assertEquals(
            listOf(LocalTaskAction.READ_SCREEN, LocalTaskAction.FIND_UI_ELEMENT, LocalTaskAction.CLICK_UI_ELEMENT),
            p2.steps.map { it.action }
        )
        assertEquals("Save", p2.steps[2].args["text"])

        // Marathi: open app -> scroll down -> read screen
        val res3 = planner.planFromUserRequest("नोट्स उघड आणि खाली स्क्रोल कर नंतर स्क्रीन वाच")
        assertTrue(res3 is LocalTaskPlanResult.Planned)
        val p3 = (res3 as LocalTaskPlanResult.Planned).plan
        assertEquals(
            listOf(LocalTaskAction.OPEN_APP, LocalTaskAction.SCROLL_UI, LocalTaskAction.READ_SCREEN),
            p3.steps.map { it.action }
        )
        assertEquals("DOWN", p3.steps[1].args["direction"])

        // Hindi: flashlight on + verify
        val res4 = planner.planFromUserRequest("फ्लैशलाइट ऑन करो और चेक करो")
        assertTrue(res4 is LocalTaskPlanResult.Planned)
        val p4 = (res4 as LocalTaskPlanResult.Planned).plan
        assertEquals(listOf(LocalTaskAction.SET_FLASHLIGHT, LocalTaskAction.VERIFY_FLASHLIGHT), p4.steps.map { it.action })
        assertEquals("true", p4.steps[1].args["expected_on"])

        // Settings chain: open settings -> navigate to wifi settings
        val res5 = planner.planFromUserRequest("open settings, navigate to Wi-Fi settings")
        assertTrue(res5 is LocalTaskPlanResult.Planned)
        val p5 = (res5 as LocalTaskPlanResult.Planned).plan
        assertEquals(listOf(LocalTaskAction.OPEN_SETTINGS, LocalTaskAction.OPEN_SETTINGS), p5.steps.map { it.action })
        assertEquals("system_settings", p5.steps[0].args["action"])
        assertEquals("wifi_settings", p5.steps[1].args["action"])
    }

    @Test
    fun `6b-2 orchestrator executes real multi-step local chains offline with post-action verification and zero AI calls`() = runBlocking {
        registerFlashCamera(hasFlash = true)
        registerLaunchableApp("com.example.notes", "Notes")
        connectService()

        val controller = DeviceController(app)
        var reportedTorch = false
        controller.torchStateVerifierOverride = { _, expected ->
            reportedTorch = expected
            reportedTorch
        }

        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(10)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol / 2).coerceAtLeast(1), 0)

        var screenPhase = 0
        var activePkg = "com.jarvis.app"
        val screenBridge = object : AccessibilityScreenReaderBridge {
            override suspend fun readScreen(): ScreenReadResult {
                return when (screenPhase) {
                    0 -> ScreenReadResult.Success(snapshotOf(activePkg, nodeSnap(text = "Loading...")))
                    1 -> ScreenReadResult.Success(
                        snapshotOf(
                            activePkg,
                            nodeSnap(index = 0, pkg = activePkg, text = "My Notes", clickable = false),
                            nodeSnap(index = 1, pkg = activePkg, text = "Save", viewId = "save_btn", clickable = true),
                            nodeSnap(index = 2, pkg = activePkg, text = "List", scrollable = true)
                        )
                    )
                    else -> ScreenReadResult.Success(
                        snapshotOf(
                            activePkg,
                            nodeSnap(index = 0, pkg = activePkg, text = "Saved Note Item")
                        )
                    )
                }
            }
        }
        val interactionBridge = object : AccessibilityInteractionBridge {
            override suspend fun findElements(query: UiElementQuery): FindUiElementsResult {
                return if (query.text.equals("Save", ignoreCase = true)) {
                    val snap = nodeSnap(index = 1, pkg = activePkg, text = "Save", viewId = "save_btn", clickable = true)
                    FindUiElementsResult(
                        status = ScreenInteractionStatus.SUCCESS,
                        message = "Found Save",
                        packageName = activePkg,
                        matches = listOf(
                            MatchedUiElement(
                                snapshot = snap,
                                fingerprint = "fp_save",
                                displayLabel = "Save",
                                clickableResolved = true,
                                usedClickableAncestor = false,
                                resolvedClickTargetLabel = "Save",
                                isSensitive = false,
                                nodePath = listOf(1),
                                clickPath = listOf(1)
                            )
                        )
                    )
                } else {
                    FindUiElementsResult(ScreenInteractionStatus.NOT_FOUND, "Not found", activePkg)
                }
            }

            override suspend fun inspectForClick(query: UiElementQuery): ClickPrecheckOutcome {
                return ClickPrecheckOutcome.Ready(
                    ClickInspectionCandidate(
                        packageName = activePkg,
                        fingerprint = "fp_save",
                        displayLabel = "Save",
                        viewId = "save_btn",
                        className = "android.widget.Button",
                        usedAncestor = false,
                        isSensitive = false
                    )
                )
            }

            override suspend fun clickElement(query: UiElementQuery): ClickUiElementResult {
                screenPhase = 2 // Screen transitions after click!
                return ClickUiElementResult(
                    status = ScreenInteractionStatus.SUCCESS,
                    message = "Clicked Save",
                    packageName = activePkg,
                    fingerprint = "fp_save"
                )
            }

            override suspend fun inspectForTypeText(query: UiElementQuery, textToEnter: String) =
                TypeTextPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "Unused", activePkg)

            override suspend fun typeText(query: UiElementQuery, textToEnter: String) =
                TypeTextResult(ScreenInteractionStatus.NOT_FOUND, "Unused", activePkg)

            override suspend fun scrollContainer(query: UiElementQuery, direction: String): ScrollUiResult {
                screenPhase = 2
                return ScrollUiResult(ScreenInteractionStatus.SUCCESS, "Scrolled $direction", direction, activePkg)
            }
        }

        val spyAi = SpyFirebaseAiService(available = true)
        val (_, orch) = buildOrchestrator(
            spyAi = spyAi,
            deviceController = controller,
            screenBridge = screenBridge,
            interactionBridge = interactionBridge
        )

        // Chain A: Open Notes -> wait for it to load -> verify app is in foreground
        activePkg = "com.example.notes"
        screenPhase = 1
        val finA = orch.executeTurn("open Notes, wait for it to load, verify app is in foreground", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse("Expected Open+Wait+Verify to succeed, got: ${finA.replyText}", finA.isError)
        assertTrue(finA.replyText.contains("Completed local task"))

        // Chain B: Read screen -> find Save -> click it
        screenPhase = 1
        val finB = orch.executeTurn("read screen, find Save, and click it", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse("Expected Read+Find+Click-it to succeed, got: ${finB.replyText}", finB.isError)
        assertTrue(finB.replyText.contains("3/3 verified steps"))

        // Chain C: Volume up -> verify actual volume
        val finC = orch.executeTurn("volume up and verify volume", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse("Expected volume+verify to succeed, got: ${finC.replyText}", finC.isError)
        assertTrue(finC.replyText.contains("Verified media volume"))

        // Chain D: Flashlight on -> verify flashlight state
        val finD = orch.executeTurn("turn on flashlight and verify state", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse("Expected flashlight+verify to succeed, got: ${finD.replyText}", finD.isError)
        assertTrue(finD.replyText.contains("Verified flashlight is on"))

        // Chain E: Open Settings -> navigate to Wi-Fi settings
        val finE = orch.executeTurn("open settings and navigate to Wi-Fi settings", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse("Expected Settings chain to succeed, got: ${finE.replyText}", finE.isError)
        val launchedActions = Shadows.shadowOf(app).let { shadowApp ->
            generateSequence { shadowApp.nextStartedActivity }.map { it.action }.toList()
        }
        assertTrue(launchedActions.contains(Settings.ACTION_SETTINGS))
        assertTrue(launchedActions.contains(Settings.ACTION_WIFI_SETTINGS))

        // Chain F: Open Notes -> scroll down -> read visible content
        screenPhase = 1
        val finF = orch.executeTurn("open Notes, scroll down, read visible content", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse("Expected Open+Scroll+Read to succeed, got: ${finF.replyText}", finF.isError)
        assertTrue(finF.replyText.contains("3/3 verified steps"))

        assertEquals("Local multi-step automation must NEVER call AI", 0, spyAi.streamCalls.get())
    }

    @Test
    fun `6b-3 mid-execution capability revocation stops safely and preserves completed steps`() = runBlocking {
        registerLaunchableApp("com.example.notes", "Notes")
        connectService()

        val registry = DeviceCapabilityRegistry(app)
        var revokeAccessibilityAfterOpen = true
        val screenBridge = object : AccessibilityScreenReaderBridge {
            override suspend fun readScreen(): ScreenReadResult {
                return ScreenReadResult.Success(
                    snapshotOf("com.example.notes", nodeSnap(text = "Notes Home", scrollable = true))
                )
            }
        }
        val interactionBridge = object : AccessibilityInteractionBridge {
            override suspend fun findElements(query: UiElementQuery) =
                FindUiElementsResult(ScreenInteractionStatus.NOT_FOUND, "n/a", "com.example.notes")
            override suspend fun inspectForClick(query: UiElementQuery) =
                ClickPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "n/a", "com.example.notes")
            override suspend fun clickElement(query: UiElementQuery) =
                ClickUiElementResult(ScreenInteractionStatus.NOT_FOUND, "n/a", "com.example.notes")
            override suspend fun inspectForTypeText(query: UiElementQuery, textToEnter: String) =
                TypeTextPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "n/a", "com.example.notes")
            override suspend fun typeText(query: UiElementQuery, textToEnter: String) =
                TypeTextResult(ScreenInteractionStatus.NOT_FOUND, "n/a", "com.example.notes")
            override suspend fun scrollContainer(query: UiElementQuery, direction: String) =
                ScrollUiResult(ScreenInteractionStatus.SUCCESS, "Scrolled", direction, "com.example.notes")
        }
        val (executor, orch) = buildOrchestrator(
            registry = registry,
            screenBridge = screenBridge,
            interactionBridge = interactionBridge
        )
        val planner = LocalDeviceTaskPlanner(
            capabilityRegistry = registry,
            appResolver = { executor.localDeviceController.resolveApplication(it) }
        )
        val planRes = planner.planFromUserRequest("open Notes, scroll down, read screen")
        assertTrue(planRes is LocalTaskPlanResult.Planned)
        val plan = (planRes as LocalTaskPlanResult.Planned).plan

        val report = executor.localDeviceTaskExecutor.executePlan(plan) { ev ->
            if (ev is TaskProgressEvent.StepFinished && ev.outcome.stepIndex == 1 && revokeAccessibilityAfterOpen) {
                revokeAccessibilityAfterOpen = false
                JarvisAccessibilityService.resetConnectionStateForTest()
            }
        }

        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertEquals(1, report.completedSteps.size)
        assertTrue(report.failedStep?.message?.contains("unavailable", ignoreCase = true) == true)
        assertEquals(1, report.skippedSteps.size)
        assertFalse(orch.let { executor.taskContextStore.hasActiveTask() })
    }

    @Test
    fun `6b-4 post-action verification rejects flashlight mismatch volume mismatch and foreground app mismatch`() = runBlocking {
        registerFlashCamera(hasFlash = true)
        registerLaunchableApp("com.example.notes", "Notes")
        connectService()

        // 1. Flashlight verification mismatch
        val flashController = DeviceController(app)
        flashController.torchStateVerifierOverride = { _, _ ->
            false
        }
        val (_, flashOrch) = buildOrchestrator(deviceController = flashController)
        val flashFin = flashOrch.executeTurn("turn on flashlight and verify state", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue("Mismatching torch verification must fail", flashFin.isError)
        assertFalse(flashFin.replyText.contains("Completed local task"))

        // 2. Wrong foreground app on verify_foreground_app
        val wrongScreenBridge = object : AccessibilityScreenReaderBridge {
            override suspend fun readScreen(): ScreenReadResult =
                ScreenReadResult.Success(snapshotOf("com.other.evilapp", nodeSnap(pkg = "com.other.evilapp", text = "Other")))
        }
        val (_, fgOrch) = buildOrchestrator(screenBridge = wrongScreenBridge)
        val fgFin = fgOrch.executeTurn("open Notes and verify app is in foreground", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue("Foreground mismatch must fail verification", fgFin.isError)
        assertTrue(fgFin.replyText.contains("com.other.evilapp"))

        // 3. Volume mismatch -> independent post-action read shows volume did not increase -> fails cleanly
        val volController = DeviceController(app)
        volController.volumeStateOverride = {
            VolumeStateSnapshot(streamVolume = 5, maxVolume = 15, isMuted = false, isFixed = false)
        }
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        val (_, volOrch) = buildOrchestrator(deviceController = volController)
        val volFin = volOrch.executeTurn("volume up and verify volume", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue("Volume verification mismatch must fail cleanly", volFin.isError)
        assertTrue(volFin.replyText.contains("failed", ignoreCase = true))
    }

    @Test
    fun `6b-5 pause resume and cancel preserve TaskContextStore state without repeating completed actions`() = runBlocking {
        registerFlashCamera(hasFlash = true)
        registerLaunchableApp("com.example.notes", "Notes")
        connectService()

        val executedLog = mutableListOf<String>()
        val currentPkg = "com.example.notes"
        var scrollPage = 0
        val screenBridge = object : AccessibilityScreenReaderBridge {
            override suspend fun readScreen(): ScreenReadResult =
                ScreenReadResult.Success(
                    snapshotOf(currentPkg, nodeSnap(pkg = currentPkg, text = "Note body page $scrollPage", scrollable = true))
                )
        }
        val interactionBridge = object : AccessibilityInteractionBridge {
            override suspend fun findElements(query: UiElementQuery) =
                FindUiElementsResult(ScreenInteractionStatus.NOT_FOUND, "n/a", currentPkg)
            override suspend fun inspectForClick(query: UiElementQuery) =
                ClickPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "n/a", currentPkg)
            override suspend fun clickElement(query: UiElementQuery) =
                ClickUiElementResult(ScreenInteractionStatus.NOT_FOUND, "n/a", currentPkg)
            override suspend fun inspectForTypeText(query: UiElementQuery, textToEnter: String) =
                TypeTextPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "n/a", currentPkg)
            override suspend fun typeText(query: UiElementQuery, textToEnter: String) =
                TypeTextResult(ScreenInteractionStatus.NOT_FOUND, "n/a", currentPkg)
            override suspend fun scrollContainer(query: UiElementQuery, direction: String): ScrollUiResult {
                scrollPage += 1
                executedLog.add("scroll_$direction")
                return ScrollUiResult(ScreenInteractionStatus.SUCCESS, "Scrolled $direction", direction, currentPkg)
            }
        }

        val (executor, orch) = buildOrchestrator(screenBridge = screenBridge, interactionBridge = interactionBridge)
        val planner = LocalDeviceTaskPlanner(
            capabilityRegistry = DeviceCapabilityRegistry(app),
            appResolver = { executor.localDeviceController.resolveApplication(it) }
        )
        val plan = (planner.planFromUserRequest("open Notes, scroll down, scroll up, read visible content") as LocalTaskPlanResult.Planned).plan

        val pausedSignal = CompletableDeferred<Unit>()
        val job = async {
            executor.localDeviceTaskExecutor.executePlan(plan) { event ->
                if (event is TaskProgressEvent.StepFinished && event.outcome.stepIndex == 2) {
                    // Pause after step 2 (scroll down); steps 1 (open Notes) and 2 (scroll down) already completed
                    executor.pauseActiveUiTask()
                    pausedSignal.complete(Unit)
                }
            }
        }
        pausedSignal.await()
        val pausedReport = job.await()
        assertEquals(TaskExecutionStatus.PAUSED, pausedReport.status)
        assertEquals(2, pausedReport.completedSteps.size)
        assertEquals(2, pausedReport.skippedSteps.size)
        assertEquals(TaskLifecycleState.PAUSED, executor.taskContextStore.getActiveContext()?.state)

        // Resume via orchestrator "continue" -> completes steps 3 & 4 without repeating step 1 or step 2!
        val resumeFin = orch.executeTurn("continue", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse("Expected resumed task to succeed, got: ${resumeFin.replyText}", resumeFin.isError)
        assertEquals(listOf("scroll_DOWN", "scroll_UP"), executedLog)
        assertFalse(executor.taskContextStore.hasActiveTask())

        // Verify cancel path clears paused state
        val job2 = async {
            executor.localDeviceTaskExecutor.executePlan(plan.copy(id = "cancel_task_1")) { event ->
                if (event is TaskProgressEvent.StepFinished && event.outcome.stepIndex == 1) {
                    executor.pauseActiveUiTask()
                }
            }
        }
        val rep2 = job2.await()
        assertEquals(TaskExecutionStatus.PAUSED, rep2.status)
        val cancelFin = orch.executeTurn("cancel task", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse(cancelFin.isError)
        assertTrue(cancelFin.replyText.contains("cancelled", ignoreCase = true))
        assertFalse(executor.taskContextStore.hasActiveTask())
    }

    @Test
    fun `6b-6 ambiguous requests return local clarifying questions without calling AI`() = runBlocking {
        val spyAi = SpyFirebaseAiService(available = true)
        val (_, orch) = buildOrchestrator(spyAi = spyAi)

        val ambiguousPrompts = listOf(
            "open app",
            "click the button",
            "scroll",
            "type something",
            "change the volume",
            "flashlight"
        )
        for (prompt in ambiguousPrompts) {
            val fin = orch.executeTurn(prompt, null, emptyList(), BrainConfig())
                .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
            assertFalse("Clarification should not be an error for '$prompt'", fin.isError)
            assertTrue("Expected question mark clarification for '$prompt', got: ${fin.replyText}", fin.replyText.contains("?"))
        }
        assertEquals("Ambiguous local prompts must never call AI", 0, spyAi.streamCalls.get())
    }

    @Test
    fun `6b-7 preserves confirmation gate and sensitive credential protection during local multi-step automation`() = runBlocking {
        registerLaunchableApp("com.example.wallet", "Wallet")
        connectService()

        var screenPhase = 0
        val screenBridge = object : AccessibilityScreenReaderBridge {
            override suspend fun readScreen(): ScreenReadResult = when (screenPhase) {
                0 -> ScreenReadResult.Success(
                    snapshotOf("com.example.wallet", nodeSnap(text = "Delete Account", clickable = true))
                )
                else -> ScreenReadResult.Success(
                    snapshotOf("com.example.wallet", nodeSnap(text = "Account Deleted"))
                )
            }
        }
        val interactionBridge = object : AccessibilityInteractionBridge {
            override suspend fun findElements(query: UiElementQuery) =
                FindUiElementsResult(ScreenInteractionStatus.NOT_FOUND, "n/a", "com.example.wallet")

            override suspend fun inspectForClick(query: UiElementQuery): ClickPrecheckOutcome {
                return ClickPrecheckOutcome.Ready(
                    ClickInspectionCandidate(
                        packageName = "com.example.wallet",
                        fingerprint = "fp_delete",
                        displayLabel = "Delete Account",
                        viewId = "delete_btn",
                        className = "android.widget.Button",
                        usedAncestor = false,
                        isSensitive = true
                    )
                )
            }

            override suspend fun clickElement(query: UiElementQuery): ClickUiElementResult {
                screenPhase = 1
                return ClickUiElementResult(
                    status = ScreenInteractionStatus.SUCCESS,
                    message = "Clicked Delete Account",
                    packageName = "com.example.wallet",
                    fingerprint = "fp_delete"
                )
            }

            override suspend fun inspectForTypeText(query: UiElementQuery, textToEnter: String): TypeTextPrecheckOutcome =
                TypeTextPrecheckOutcome.Failed(
                    ScreenInteractionStatus.PASSWORD_FIELD,
                    "Blocked: cannot inspect or enter text into password/OTP/PIN field.",
                    "com.example.wallet"
                )

            override suspend fun typeText(query: UiElementQuery, textToEnter: String): TypeTextResult =
                TypeTextResult(
                    status = ScreenInteractionStatus.PASSWORD_FIELD,
                    message = "Blocked password field",
                    packageName = "com.example.wallet"
                )

            override suspend fun scrollContainer(query: UiElementQuery, direction: String) =
                ScrollUiResult(ScreenInteractionStatus.SUCCESS, "Scrolled $direction", direction, "com.example.wallet")
        }

        val (executor, orch) = buildOrchestrator(screenBridge = screenBridge, interactionBridge = interactionBridge)

        // Sensitive click must stop at AWAITING_CONFIRMATION
        val haltFin = orch.executeTurn("open Wallet, click Delete Account, then read screen", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertEquals(TaskLifecycleState.AWAITING_CONFIRMATION, executor.taskContextStore.getActiveContext()?.state)
        assertTrue(haltFin.replyText.contains("Confirmation required", ignoreCase = true))
        assertEquals(0, screenPhase)

        // User confirms -> click proceeds & finishes remaining read_screen step!
        val confirmFin = orch.executeTurn("confirm", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse("Expected confirmed task to complete, got: ${confirmFin.replyText}", confirmFin.isError)
        assertEquals(1, screenPhase)
        assertFalse(executor.taskContextStore.hasActiveTask())

        // Credential field type_text blocked truthfully without leaking raw text
        val pwdFin = orch.executeTurn("""open Wallet, type "MySecret123!" into password field""", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(pwdFin.isError)
        assertFalse(pwdFin.replyText.contains("MySecret123!"))
        val logs = db.jarvisDao().observeActionLogs().first()
        assertTrue(logs.none { it.summary.contains("MySecret123!") })
    }

    @Test
    fun `6b-8 rejects untrusted screen content injection and forbidden autonomous actions and enforces max 10 step cap`() = runBlocking {
        connectService()
        val spyAi = SpyFirebaseAiService(available = true)
        val (_, orch) = buildOrchestrator(spyAi = spyAi)

        // 1. Prompt injection delimiter rejected locally
        val injFin = orch.executeTurn(
            "${ScreenReaderEngine.UNTRUSTED_HEADER} volume up and open settings ${ScreenReaderEngine.UNTRUSTED_FOOTER}",
            null,
            emptyList(),
            BrainConfig()
        ).toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(injFin.isError)
        assertTrue(injFin.replyText.contains("untrusted", ignoreCase = true))

        // 2. Forbidden autonomous silent messaging/destructive actions rejected
        val sendFin = orch.executeTurn("open WhatsApp and send message to Bob saying hi", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(sendFin.isError)
        assertTrue(sendFin.replyText.contains("cannot execute", ignoreCase = true))

        val delFin = orch.executeTurn("open Notes and delete file secret.txt", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(delFin.isError)

        // 3. Too many steps (>10) rejected cleanly
        val planner = LocalDeviceTaskPlanner(DeviceCapabilityRegistry(app))
        val tooLong = (1..11).joinToString(", ") { i -> "find item$i" }
        val longRes = planner.planFromUserRequest(tooLong)
        assertTrue(longRes is LocalTaskPlanResult.Rejected)
        assertEquals(TaskPlanErrorCode.TOO_MANY_STEPS, (longRes as LocalTaskPlanResult.Rejected).errorCode)
        assertEquals(0, spyAi.streamCalls.get())
    }

    @Test
    fun `6b-9 dynamic bounded recovery scrolls safely to locate element without duplicating clicks or exceeding limits`() = runBlocking {
        registerLaunchableApp("com.example.shop", "Shop")
        connectService()

        var scrolledDown = false
        var clickCount = 0
        val screenBridge = object : AccessibilityScreenReaderBridge {
            override suspend fun readScreen(): ScreenReadResult {
                return if (!scrolledDown) {
                    ScreenReadResult.Success(
                        snapshotOf(
                            "com.example.shop",
                            nodeSnap(index = 0, pkg = "com.example.shop", text = "Header"),
                            nodeSnap(index = 1, pkg = "com.example.shop", text = "Items", scrollable = true)
                        )
                    )
                } else {
                    ScreenReadResult.Success(
                        snapshotOf(
                            "com.example.shop",
                            nodeSnap(index = 0, pkg = "com.example.shop", text = "Checkout", clickable = true),
                            nodeSnap(index = 1, pkg = "com.example.shop", text = "Items", scrollable = true)
                        )
                    )
                }
            }
        }
        val interactionBridge = object : AccessibilityInteractionBridge {
            override suspend fun findElements(query: UiElementQuery): FindUiElementsResult {
                return if (scrolledDown && query.text.equals("Checkout", ignoreCase = true)) {
                    val snap = nodeSnap(index = 0, pkg = "com.example.shop", text = "Checkout", clickable = true)
                    FindUiElementsResult(
                        ScreenInteractionStatus.SUCCESS,
                        "Found Checkout",
                        "com.example.shop",
                        listOf(
                            MatchedUiElement(
                                snapshot = snap,
                                fingerprint = "fp_checkout",
                                displayLabel = "Checkout",
                                clickableResolved = true,
                                usedClickableAncestor = false,
                                resolvedClickTargetLabel = "Checkout",
                                isSensitive = false,
                                nodePath = listOf(0),
                                clickPath = listOf(0)
                            )
                        )
                    )
                } else {
                    FindUiElementsResult(ScreenInteractionStatus.NOT_FOUND, "Checkout not visible", "com.example.shop")
                }
            }

            override suspend fun inspectForClick(query: UiElementQuery) =
                ClickPrecheckOutcome.Ready(
                    ClickInspectionCandidate(
                        packageName = "com.example.shop",
                        fingerprint = "fp_checkout",
                        displayLabel = "Checkout",
                        viewId = "checkout_btn",
                        className = "android.widget.Button",
                        usedAncestor = false,
                        isSensitive = false
                    )
                )

            override suspend fun clickElement(query: UiElementQuery): ClickUiElementResult {
                clickCount += 1
                scrolledDown = true
                return ClickUiElementResult(ScreenInteractionStatus.SUCCESS, "Clicked Checkout", "com.example.shop", "fp_checkout")
            }

            override suspend fun inspectForTypeText(query: UiElementQuery, textToEnter: String) =
                TypeTextPrecheckOutcome.Failed(ScreenInteractionStatus.NOT_FOUND, "n/a", "com.example.shop")

            override suspend fun typeText(query: UiElementQuery, textToEnter: String) =
                TypeTextResult(ScreenInteractionStatus.NOT_FOUND, "n/a", "com.example.shop")

            override suspend fun scrollContainer(query: UiElementQuery, direction: String): ScrollUiResult {
                scrolledDown = true
                return ScrollUiResult(ScreenInteractionStatus.SUCCESS, "Scrolled $direction", direction, "com.example.shop")
            }
        }

        val (executor, orch) = buildOrchestrator(screenBridge = screenBridge, interactionBridge = interactionBridge)
        val events = orch.executeTurn("open Shop, find Checkout, and read visible content", null, emptyList(), BrainConfig()).toList()
        val fin = events.filterIsInstance<TurnProgressEvent.Finished>().last()

        assertFalse("Expected bounded scroll recovery to recover Checkout find, got: ${fin.replyText}", fin.isError)
        assertTrue(scrolledDown)
        assertEquals("Read-only find recovery must not trigger click", 0, clickCount)
        assertTrue(fin.replyText.contains("Dynamic recovery attempts used: 1/2"))
        assertFalse(executor.taskContextStore.hasActiveTask())
    }
}
