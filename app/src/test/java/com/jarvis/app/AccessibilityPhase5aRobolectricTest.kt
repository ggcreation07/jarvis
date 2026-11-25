package com.jarvis.app

import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisDatabase
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.network.JarvisHttpTools
import com.jarvis.app.domain.brain.AccessibilityScreenReaderBridge
import com.jarvis.app.domain.brain.FinalVerificationStatus
import com.jarvis.app.domain.brain.RawTaskStep
import com.jarvis.app.domain.brain.TaskExecutionStatus
import com.jarvis.app.domain.brain.TaskPlanValidationResult
import com.jarvis.app.domain.brain.ToolConfirmationPolicy
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.domain.brain.UiTaskExecutionEngine
import com.jarvis.app.domain.brain.UiTaskPlanner
import com.jarvis.app.platform.AccessibilityWindowSource
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.JarvisAccessibilityService
import com.jarvis.app.platform.NodeBounds
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.ScreenInteractionEngine
import com.jarvis.app.platform.ScreenInteractionStatus
import com.jarvis.app.platform.ScreenReadErrorCode
import com.jarvis.app.platform.ScreenReadResult
import com.jarvis.app.platform.ScreenReaderEngine
import com.jarvis.app.platform.ScreenReadinessDetector
import com.jarvis.app.platform.ScreenSnapshot
import com.jarvis.app.platform.TvControlClient
import com.jarvis.app.platform.UiElementQuery
import com.jarvis.app.platform.UiNodeSnapshot
import com.jarvis.app.platform.UiWaitConfig
import com.jarvis.app.platform.UiWaitOutcome
import com.jarvis.app.platform.UiWaitPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
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
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityPhase5aRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: JarvisDatabase
    private lateinit var repo: JarvisRepository
    private lateinit var configStore: ConfigStore
    private lateinit var executor: ToolExecutor
    private var testNowMs = 900_000L

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
        executor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = FirebaseAiService(),
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
        inputType: Int = 0,
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
        node.inputType = inputType
        node.isVisibleToUser = visibleToUser
        node.isEnabled = enabled
        return node
    }

    private fun snapNode(
        index: Int = 0,
        depth: Int = 0,
        pkg: String = "com.example.shop",
        cls: String = "android.widget.TextView",
        text: String = "",
        desc: String = "",
        viewId: String = "",
        bounds: NodeBounds = NodeBounds(0, 0, 100, 50),
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false
    ) = UiNodeSnapshot(
        index = index,
        depth = depth,
        packageName = pkg,
        className = cls,
        text = text,
        contentDescription = desc,
        hintText = "",
        viewId = viewId,
        bounds = bounds,
        isClickable = clickable,
        isScrollable = scrollable,
        isEditable = editable,
        isPassword = false,
        isVisibleToUser = true,
        isEnabled = true,
        isFocused = false
    )

    private class FakeWindowSource(
        override val ownPackages: Set<String> = setOf("com.jarvis.app"),
        var settingsEnabled: Boolean = true,
        var connected: Boolean = true,
        val activeRootProvider: (Int) -> AccessibilityNodeInfo? = { null },
        val fallbackRootsProvider: (Int) -> List<AccessibilityNodeInfo> = { emptyList() }
    ) : AccessibilityWindowSource {
        var calls = 0
            private set

        override fun isSettingsEnabled(): Boolean = settingsEnabled
        override fun isServiceConnected(): Boolean = connected

        override fun obtainActiveRoot(): AccessibilityNodeInfo? {
            calls += 1
            return activeRootProvider(calls)
        }

        override fun obtainFallbackWindowRoots(): List<AccessibilityNodeInfo> =
            fallbackRootsProvider(calls)
    }

    private fun connectService(): JarvisAccessibilityService {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        service.onServiceConnected()
        return service
    }

    @Suppress("DEPRECATION")
    private fun attachWindows(
        service: JarvisAccessibilityService,
        windows: List<AccessibilityWindowInfo>
    ) {
        Shadows.shadowOf(service).setWindows(windows)
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
        attachWindows(service, listOf(win))
    }

    @Test
    fun `5a-1 delayed screen rendering waits boundedly and verifies without repeating click`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val nextBtn = makeNode(
            pkg = "com.example.shop",
            className = "android.widget.Button",
            text = "Open Catalog",
            viewId = "com.example.shop:id/open_catalog",
            clickable = true
        )
        var clickCount = 0
        var transitionPollsAfterClick = 0

        Shadows.shadowOf(nextBtn).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                clickCount += 1
                true
            } else {
                false
            }
        }
        Shadows.shadowOf(root).addChild(nextBtn)
        attachActiveExternalWindow(service, root)

        val delayedReader = object : AccessibilityScreenReaderBridge {
            override suspend fun readScreen(): ScreenReadResult {
                return if (clickCount == 0) {
                    service.readScreen(maxAttempts = 1, retryDelayMs = 0L)
                } else {
                    transitionPollsAfterClick += 1
                    when {
                        // Simulate delayed screen rendering: 2 transient null-root polls then final rendered screen
                        transitionPollsAfterClick <= 2 -> ScreenReadResult.Error(
                            code = ScreenReadErrorCode.UNAVAILABLE_NULL_ROOT,
                            message = "Transitioning window"
                        )
                        else -> ScreenReadResult.Success(
                            ScreenSnapshot(
                                packageName = "com.example.shop",
                                source = "delayed",
                                capturedAtMs = testNowMs,
                                nodes = listOf(
                                    snapNode(0, 0, text = "Catalog Loaded", viewId = "com.example.shop:id/catalog_header")
                                ),
                                maxDepthReached = 0,
                                truncatedByDepth = false,
                                truncatedByNodeLimit = false
                            )
                        )
                    }
                }
            }
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
            nowMs = { testNowMs },
            appContext = app,
            screenReaderBridge = delayedReader
        )
        val engine = UiTaskExecutionEngine(
            toolExecutor = customExecutor,
            waitConfig = UiWaitConfig(pollIntervalMs = 5L, actionSettleTimeoutMs = 40L, loadingTimeoutMs = 500L, maxPolls = 15)
        )
        val plan = (UiTaskPlanner.validatePlan(
            objective = "Open catalog",
            rawSteps = listOf(RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("open_catalog")))),
            expectedFinalPackage = "com.example.shop",
            expectedFinalElement = "Catalog Loaded"
        ) as TaskPlanValidationResult.Valid).plan

        val report = engine.executePlan(plan)
        assertEquals(TaskExecutionStatus.SUCCEEDED, report.status)
        assertEquals(FinalVerificationStatus.COMPLETED, report.finalVerificationStatus)
        assertEquals(1, clickCount)
        assertTrue(transitionPollsAfterClick >= 3)
    }

    @Test
    fun `5a-2 detects loading indicators and waits before clicking or finalizing`() = runBlocking {
        var stepCalls = 0
        var clickAttempts = 0
        val source = FakeWindowSource(activeRootProvider = { call ->
            stepCalls = call
            if (call <= 2) {
                val loadingRoot = makeNode(pkg = "com.example.shop", className = "android.widget.FrameLayout")
                val spinner = makeNode(
                    pkg = "com.example.shop",
                    className = "android.widget.ProgressBar",
                    viewId = "com.example.shop:id/loading_spinner"
                )
                val loadingLabel = makeNode(
                    pkg = "com.example.shop",
                    text = "Loading..."
                )
                Shadows.shadowOf(loadingRoot).addChild(spinner)
                Shadows.shadowOf(loadingRoot).addChild(loadingLabel)
                loadingRoot
            } else {
                val readyRoot = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
                val btn = makeNode(
                    pkg = "com.example.shop",
                    className = "android.widget.Button",
                    text = "Continue",
                    viewId = "com.example.shop:id/continue_btn",
                    clickable = true
                )
                Shadows.shadowOf(btn).setOnPerformActionListener { action, _ ->
                    if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                        clickAttempts += 1
                        true
                    } else false
                }
                Shadows.shadowOf(readyRoot).addChild(btn)
                readyRoot
            }
        })

        val interactionEngine = ScreenInteractionEngine(
            windowSource = source,
            maxAttempts = 1,
            retryDelayMs = 0L,
            waitConfig = UiWaitConfig(pollIntervalMs = 5L, loadingTimeoutMs = 500L, maxPolls = 10)
        )
        val clickRes = interactionEngine.clickElement(UiElementQuery(viewId = "continue_btn"))
        assertEquals(ScreenInteractionStatus.SUCCESS, clickRes.status)
        assertEquals(3, stepCalls)
        assertEquals(1, clickAttempts)

        // Also check ScreenSnapshot context helpers
        val loadingSnapshot = ScreenSnapshot(
            packageName = "com.example.shop",
            source = "test",
            capturedAtMs = testNowMs,
            nodes = listOf(
                snapNode(0, 0, cls = "com.google.android.material.progressindicator.CircularProgressIndicator"),
                snapNode(1, 1, text = "Fetching")
            ),
            maxDepthReached = 1,
            truncatedByDepth = false,
            truncatedByNodeLimit = false
        )
        assertTrue(loadingSnapshot.isLoadingState())
        assertTrue(loadingSnapshot.loadingIndicators().contains("CircularProgressIndicator"))
        assertTrue(loadingSnapshot.toUntrustedDataBlock().contains("context=loading"))
    }

    @Test
    fun `5a-3 handles transient accessibility tree changes until tree stabilizes`() = runBlocking {
        var readSeq = 0
        val reader = ScreenReaderEngine(
            windowSource = FakeWindowSource(activeRootProvider = {
                readSeq += 1
                val root = makeNode(pkg = "com.example.anim", className = "android.widget.FrameLayout")
                val slidingCard = when (readSeq) {
                    1 -> makeNode(
                        pkg = "com.example.anim",
                        text = "Banner",
                        bounds = Rect(0, 100, 500, 200)
                    )
                    2 -> makeNode(
                        pkg = "com.example.anim",
                        text = "Banner",
                        bounds = Rect(0, 180, 500, 280)
                    )
                    else -> makeNode(
                        pkg = "com.example.anim",
                        text = "Banner",
                        bounds = Rect(0, 240, 500, 340)
                    )
                }
                Shadows.shadowOf(root).addChild(slidingCard)
                root
            }),
            maxAttempts = 1,
            retryDelayMs = 0L
        )

        val waitOutcome = reader.waitForReadyScreen(
            config = UiWaitConfig(pollIntervalMs = 5L, loadingTimeoutMs = 500L, maxPolls = 10),
            requireNonLoading = true,
            requireStable = true
        )
        assertTrue("Expected Satisfied after tree stabilized, got $waitOutcome", waitOutcome is UiWaitOutcome.Satisfied)
        val satisfied = waitOutcome as UiWaitOutcome.Satisfied
        // Polls 1 & 2 had shifting bounds; Poll 3 & Poll 4 matched -> stabilized on Poll 4
        assertEquals(4, satisfied.polls)
        assertEquals(240, satisfied.snapshot.nodes[1].bounds.top)
    }

    @Test
    fun `5a-4 handles null accessibility roots and empty stub root fallback safely`() = runBlocking {
        // Case A: Empty active root stub falls back cleanly to external fallback window root
        val emptyStub = makeNode(pkg = "com.example.overlay", className = "android.widget.FrameLayout")
        val realFallback = makeNode(pkg = "com.example.notes", className = "android.widget.LinearLayout")
        val saveBtn = makeNode(
            pkg = "com.example.notes",
            className = "android.widget.Button",
            text = "Save",
            viewId = "com.example.notes:id/save",
            clickable = true
        )
        Shadows.shadowOf(realFallback).addChild(saveBtn)

        val fallbackEngine = ScreenReaderEngine(
            windowSource = FakeWindowSource(
                activeRootProvider = { emptyStub },
                fallbackRootsProvider = { listOf(realFallback) }
            ),
            maxAttempts = 1,
            retryDelayMs = 0L
        )
        val res = fallbackEngine.readScreen()
        assertTrue(res is ScreenReadResult.Success)
        val snap = (res as ScreenReadResult.Success).snapshot
        assertEquals("com.example.notes", snap.packageName)
        assertEquals("foreground_windows", snap.source)

        // Case B: Persistent null roots return structured TimedOut error cleanly
        val nullReader = ScreenReaderEngine(
            windowSource = FakeWindowSource(activeRootProvider = { null }),
            maxAttempts = 1,
            retryDelayMs = 0L
        )
        var fakeClock = 0L
        val timedOut = nullReader.waitForReadyScreen(
            config = UiWaitConfig(pollIntervalMs = 1L, loadingTimeoutMs = 50L, maxPolls = 4)
        )
        fakeClock += 1
        assertTrue(timedOut is UiWaitOutcome.TimedOut)
        val errTimeout = timedOut as UiWaitOutcome.TimedOut
        assertEquals(ScreenReadErrorCode.UNAVAILABLE_NULL_ROOT, errTimeout.lastError?.code)
        assertEquals(4, errTimeout.polls)
    }

    @Test
    fun `5a-5 reads and clicks deep WebView nodes while blocking WebView password fields`() = runBlocking {
        val root = makeNode(pkg = "com.example.browser", className = "android.widget.FrameLayout")
        val webView = makeNode(pkg = "com.example.browser", className = "android.webkit.WebView")
        Shadows.shadowOf(root).addChild(webView)

        // Build nested WebView DOM down to depth 15 (> default MAX_TRAVERSAL_DEPTH = 12, within 12 + 6 = 18)
        var current = webView
        for (depth in 2..14) {
            val container = makeNode(pkg = "com.example.browser", className = "android.view.View")
            Shadows.shadowOf(current).addChild(container)
            current = container
        }

        var webClicked = false
        val ariaButton = makeNode(
            pkg = "com.example.browser",
            className = "android.widget.Button",
            text = "",
            contentDescription = "Download PDF",
            clickable = true
        )
        Shadows.shadowOf(ariaButton).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                webClicked = true
                true
            } else false
        }
        val webPassword = makeNode(
            pkg = "com.example.browser",
            className = "android.widget.EditText",
            text = "WebSecret#99",
            hintText = "Passphrase",
            viewId = "web_pass",
            editable = true,
            password = false,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
        Shadows.shadowOf(current).addChild(ariaButton)
        Shadows.shadowOf(current).addChild(webPassword)

        val recycled = mutableListOf<AccessibilityNodeInfo>()
        val reader = ScreenReaderEngine(
            windowSource = FakeWindowSource(activeRootProvider = { root }),
            maxAttempts = 1,
            retryDelayMs = 0L,
            onNodeRecycled = { recycled.add(it) }
        )
        val readRes = reader.readScreen() as ScreenReadResult.Success
        assertTrue(readRes.snapshot.hasWebView())
        assertEquals(15, readRes.snapshot.maxDepthReached)
        val block = readRes.snapshot.toUntrustedDataBlock()
        assertTrue(block.contains("context=webview"))
        assertFalse("WebView password value must be masked", block.contains("WebSecret#99"))
        assertTrue(readRes.snapshot.nodes.any { it.isPassword && it.text == ScreenReaderEngine.PASSWORD_MASK })

        // Rebuild tree for interaction check (since nodes above were recycled)
        val root2 = makeNode(pkg = "com.example.browser", className = "android.widget.FrameLayout")
        val webView2 = makeNode(pkg = "com.example.browser", className = "android.webkit.WebView")
        Shadows.shadowOf(root2).addChild(webView2)
        var cur2 = webView2
        for (depth in 2..14) {
            val container = makeNode(pkg = "com.example.browser", className = "android.view.View")
            Shadows.shadowOf(cur2).addChild(container)
            cur2 = container
        }
        val ariaBtn2 = makeNode(
            pkg = "com.example.browser",
            className = "android.widget.Button",
            text = "",
            contentDescription = "Download PDF",
            clickable = true
        )
        Shadows.shadowOf(ariaBtn2).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                webClicked = true
                true
            } else false
        }
        val webPass2 = makeNode(
            pkg = "com.example.browser",
            className = "android.widget.EditText",
            text = "WebSecret#99",
            viewId = "web_pass",
            editable = true,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
        Shadows.shadowOf(cur2).addChild(ariaBtn2)
        Shadows.shadowOf(cur2).addChild(webPass2)

        val interaction = ScreenInteractionEngine(
            windowSource = FakeWindowSource(activeRootProvider = { root2 }),
            maxAttempts = 1,
            retryDelayMs = 0L
        )
        // WebView aria-label matching via text query works deeply at depth 15
        val clickRes = interaction.clickElement(UiElementQuery(text = "Download PDF"))
        assertEquals(ScreenInteractionStatus.SUCCESS, clickRes.status)
        assertTrue(webClicked)

        // Rebuild for password block check
        val root3 = makeNode(pkg = "com.example.browser", className = "android.widget.FrameLayout")
        val webView3 = makeNode(pkg = "com.example.browser", className = "android.webkit.WebView")
        Shadows.shadowOf(root3).addChild(webView3)
        val webPass3 = makeNode(
            pkg = "com.example.browser",
            className = "android.widget.EditText",
            text = "WebSecret#99",
            viewId = "web_pass",
            editable = true,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
        Shadows.shadowOf(webView3).addChild(webPass3)
        val interactionPass = ScreenInteractionEngine(
            windowSource = FakeWindowSource(activeRootProvider = { root3 }),
            maxAttempts = 1,
            retryDelayMs = 0L
        )
        val blockedPass = interactionPass.typeText(UiElementQuery(viewId = "web_pass"), "123456")
        assertEquals(ScreenInteractionStatus.PASSWORD_FIELD, blockedPass.status)
    }

    @Test
    fun `5a-6 resolves nested scroll containers innermost first and falls back to outer at boundary`() = runBlocking {
        // Case A: NestedScrollView -> RecyclerView: scrolls inner RecyclerView first
        val root1 = makeNode(pkg = "com.example.feed", className = "android.widget.FrameLayout")
        val outer1 = makeNode(
            pkg = "com.example.feed",
            className = "androidx.core.widget.NestedScrollView",
            viewId = "com.example.feed:id/outer_nested",
            scrollable = true
        )
        val inner1 = makeNode(
            pkg = "com.example.feed",
            className = "androidx.recyclerview.widget.RecyclerView",
            viewId = "com.example.feed:id/inner_recycler",
            scrollable = true
        )
        val actions1 = mutableListOf<String>()
        Shadows.shadowOf(outer1).setOnPerformActionListener { _, _ -> actions1.add("outer"); true }
        Shadows.shadowOf(inner1).setOnPerformActionListener { _, _ -> actions1.add("inner"); true }
        Shadows.shadowOf(outer1).addChild(inner1)
        Shadows.shadowOf(root1).addChild(outer1)

        val engine1 = ScreenInteractionEngine(
            windowSource = FakeWindowSource(activeRootProvider = { root1 }),
            maxAttempts = 1,
            retryDelayMs = 0L
        )
        val nestedRes1 = engine1.scrollContainer(UiElementQuery(), "DOWN")
        assertEquals(ScreenInteractionStatus.SUCCESS, nestedRes1.status)
        assertEquals(listOf("inner"), actions1)

        // Case B: Inner RecyclerView at scroll boundary (returns false) -> safely falls back to outer NestedScrollView
        val root2 = makeNode(pkg = "com.example.feed", className = "android.widget.FrameLayout")
        val outer2 = makeNode(
            pkg = "com.example.feed",
            className = "androidx.core.widget.NestedScrollView",
            viewId = "com.example.feed:id/outer_nested",
            scrollable = true
        )
        val inner2 = makeNode(
            pkg = "com.example.feed",
            className = "androidx.recyclerview.widget.RecyclerView",
            viewId = "com.example.feed:id/inner_recycler",
            scrollable = true
        )
        val actions2 = mutableListOf<String>()
        Shadows.shadowOf(outer2).setOnPerformActionListener { _, _ -> actions2.add("outer"); true }
        Shadows.shadowOf(inner2).setOnPerformActionListener { _, _ -> actions2.add("inner_false"); false }
        Shadows.shadowOf(outer2).addChild(inner2)
        Shadows.shadowOf(root2).addChild(outer2)

        val engine2 = ScreenInteractionEngine(
            windowSource = FakeWindowSource(activeRootProvider = { root2 }),
            maxAttempts = 1,
            retryDelayMs = 0L
        )
        val nestedRes2 = engine2.scrollContainer(UiElementQuery(), "DOWN")
        assertEquals(ScreenInteractionStatus.SUCCESS, nestedRes2.status)
        assertEquals(listOf("inner_false", "outer"), actions2)

        // Case C: Sibling scrollers (non-nested) still return AMBIGUOUS cleanly
        val root3 = makeNode(pkg = "com.example.feed", className = "android.widget.LinearLayout")
        val sibA = makeNode(pkg = "com.example.feed", className = "android.widget.ScrollView", viewId = "a", scrollable = true)
        val sibB = makeNode(pkg = "com.example.feed", className = "android.widget.ScrollView", viewId = "b", scrollable = true)
        Shadows.shadowOf(root3).addChild(sibA)
        Shadows.shadowOf(root3).addChild(sibB)
        val engine3 = ScreenInteractionEngine(
            windowSource = FakeWindowSource(activeRootProvider = { root3 }),
            maxAttempts = 1,
            retryDelayMs = 0L
        )
        val siblingRes = engine3.scrollContainer(UiElementQuery(), "DOWN")
        assertEquals(ScreenInteractionStatus.AMBIGUOUS, siblingRes.status)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `5a-7 prioritizes active system and permission dialogs over background app windows and keeps confirmation gate`() = runBlocking {
        val service = connectService()

        // Background TYPE_APPLICATION window (inactive)
        val bgAppRoot = makeNode(pkg = "com.example.camera", className = "android.widget.FrameLayout")
        val bgText = makeNode(pkg = "com.example.camera", text = "Take Photo")
        Shadows.shadowOf(bgAppRoot).addChild(bgText)
        val bgWin = AccessibilityWindowInfo.obtain()
        Shadows.shadowOf(bgWin).apply {
            setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            setActive(false)
            setFocused(false)
            setRoot(bgAppRoot)
        }

        // Foreground active TYPE_SYSTEM permission dialog
        val permRoot = makeNode(
            pkg = "com.google.android.permissioncontroller",
            className = "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"
        )
        val allowBtn = makeNode(
            pkg = "com.google.android.permissioncontroller",
            className = "android.widget.Button",
            text = "While using the app",
            viewId = "com.android.permissioncontroller:id/permission_allow_foreground_only_button",
            clickable = true
        )
        var permClicked = false
        Shadows.shadowOf(allowBtn).setOnPerformActionListener { _, _ -> permClicked = true; true }
        Shadows.shadowOf(permRoot).addChild(allowBtn)

        val sysWin = AccessibilityWindowInfo.obtain()
        Shadows.shadowOf(sysWin).apply {
            setType(AccessibilityWindowInfo.TYPE_SYSTEM)
            setActive(true)
            setFocused(true)
            setRoot(permRoot)
        }

        attachWindows(service, listOf(bgWin, sysWin))

        val readRes = service.readScreen(maxAttempts = 1, retryDelayMs = 0L) as ScreenReadResult.Success
        assertEquals("com.google.android.permissioncontroller", readRes.snapshot.packageName)
        assertTrue(readRes.snapshot.isSystemDialog())
        assertTrue(readRes.snapshot.isPermissionScreen())

        // Attempting to click a permission grant button MUST require explicit confirmation (never auto-granted)
        val execRes = executor.execute(
            "click_ui_element",
            mapOf("view_id" to JsonPrimitive("permission_allow_foreground_only_button"))
        ) {}
        assertFalse(permClicked)
        assertEquals(
            ToolConfirmationPolicy.STATUS_CONFIRMATION_REQUIRED,
            execRes.payload["status"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `5a-8 aborts wait immediately when accessibility service disconnects during waiting`() = runBlocking {
        var polls = 0
        val source = FakeWindowSource(
            activeRootProvider = {
                polls += 1
                val loadingRoot = makeNode(pkg = "com.example.app", className = "android.widget.FrameLayout")
                val spinner = makeNode(pkg = "com.example.app", className = "android.widget.ProgressBar")
                Shadows.shadowOf(loadingRoot).addChild(spinner)
                loadingRoot
            }
        )
        val reader = ScreenReaderEngine(windowSource = source, maxAttempts = 1, retryDelayMs = 0L)
        val outcome = UiWaitPolicy.waitForCondition(
            timeoutMs = 2000L,
            pollIntervalMs = 5L,
            maxPolls = 20,
            requireNonLoading = true,
            readFresh = {
                if (polls >= 2) {
                    source.connected = false
                }
                reader.readScreen()
            },
            condition = { true }
        )
        assertTrue("Expected ServiceDisconnected outcome, got $outcome", outcome is UiWaitOutcome.ServiceDisconnected)
        val dc = outcome as UiWaitOutcome.ServiceDisconnected
        assertEquals(ScreenReadErrorCode.SERVICE_ENABLED_BUT_DISCONNECTED, dc.code)
        assertEquals(3, dc.polls)
    }

    @Test
    fun `5a-9 cancellation during polling aborts wait cleanly and updates TaskContextStore`() = runBlocking {
        val service = connectService()
        val loadingRoot = makeNode(pkg = "com.example.shop", className = "android.widget.FrameLayout")
        val spinner = makeNode(pkg = "com.example.shop", className = "android.widget.ProgressBar")
        Shadows.shadowOf(loadingRoot).addChild(spinner)
        attachActiveExternalWindow(service, loadingRoot)

        var reads = 0
        val slowLoadingReader = object : AccessibilityScreenReaderBridge {
            override suspend fun readScreen(): ScreenReadResult {
                reads += 1
                return ScreenReadResult.Success(
                    ScreenSnapshot(
                        packageName = "com.example.shop",
                        source = "loading",
                        capturedAtMs = testNowMs,
                        nodes = listOf(
                            snapNode(0, 0, cls = "android.widget.ProgressBar"),
                            snapNode(1, 1, text = "Loading...")
                        ),
                        maxDepthReached = 1,
                        truncatedByDepth = false,
                        truncatedByNodeLimit = false
                    )
                )
            }
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
            nowMs = { testNowMs },
            appContext = app,
            screenReaderBridge = slowLoadingReader
        )
        val engine = UiTaskExecutionEngine(
            toolExecutor = customExecutor,
            waitConfig = UiWaitConfig(pollIntervalMs = 20L, loadingTimeoutMs = 5000L, maxPolls = 200)
        )
        val plan = (UiTaskPlanner.validatePlan(
            objective = "Click Submit after load",
            rawSteps = listOf(RawTaskStep("click_ui_element", mapOf("text" to JsonPrimitive("Pay"))))
        ) as TaskPlanValidationResult.Valid).plan

        val deferred = async { engine.executePlan(plan) }
        delay(50L)
        assertTrue(engine.cancelActiveTask())
        val report = deferred.await()
        assertEquals(TaskExecutionStatus.CANCELLED, report.status)
        assertFalse(engine.taskContextStore.hasActiveTask())
        assertEquals(null, engine.taskContextStore.getActiveContext())
        assertTrue(engine.taskContextStore.getLastExecutionSummary().orEmpty().contains("cancel", ignoreCase = true))
        assertTrue(reads >= 1)

        // Direct external coroutine job cancellation inside UiWaitPolicy must throw CancellationException
        val waitJob = async {
            UiWaitPolicy.waitForCondition(
                timeoutMs = 5000L,
                pollIntervalMs = 20L,
                maxPolls = 200,
                readFresh = { slowLoadingReader.readScreen() },
                condition = { true }
            )
        }
        delay(35L)
        waitJob.cancel()
        var threwCancellation = false
        try {
            waitJob.await()
        } catch (_: CancellationException) {
            threwCancellation = true
        }
        assertTrue(threwCancellation)
    }

    @Test
    fun `5a-10 handles persistent loading timeout cleanly without blind action or false success`() = runBlocking {
        var clickCount = 0
        val persistentLoadingBridge = object : AccessibilityScreenReaderBridge {
            override suspend fun readScreen(): ScreenReadResult =
                ScreenReadResult.Success(
                    ScreenSnapshot(
                        packageName = "com.example.bank",
                        source = "loading",
                        capturedAtMs = testNowMs,
                        nodes = listOf(
                            snapNode(0, 0, pkg = "com.example.bank", cls = "android.widget.ProgressBar"),
                            snapNode(1, 1, pkg = "com.example.bank", text = "Please wait...")
                        ),
                        maxDepthReached = 1,
                        truncatedByDepth = false,
                        truncatedByNodeLimit = false
                    )
                )
        }
        val service = connectService()
        val root = makeNode(pkg = "com.example.bank", className = "android.widget.LinearLayout")
        val btn = makeNode(pkg = "com.example.bank", text = "Open Profile", viewId = "profile_btn", clickable = true)
        Shadows.shadowOf(btn).setOnPerformActionListener { _, _ -> clickCount += 1; true }
        Shadows.shadowOf(root).addChild(btn)
        attachActiveExternalWindow(service, root)

        val customExecutor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = FirebaseAiService(),
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            nowMs = { testNowMs },
            appContext = app,
            screenReaderBridge = persistentLoadingBridge
        )
        val engine = UiTaskExecutionEngine(
            toolExecutor = customExecutor,
            waitConfig = UiWaitConfig(pollIntervalMs = 5L, actionSettleTimeoutMs = 20L, loadingTimeoutMs = 40L, maxPolls = 4)
        )
        val plan = (UiTaskPlanner.validatePlan(
            objective = "Open Profile",
            rawSteps = listOf(RawTaskStep("click_ui_element", mapOf("view_id" to JsonPrimitive("profile_btn"))))
        ) as TaskPlanValidationResult.Valid).plan

        val report = engine.executePlan(plan)
        assertEquals(TaskExecutionStatus.FAILED, report.status)
        assertEquals("LOADING_TIMEOUT", report.failedStep?.resultCode)
        assertEquals(0, clickCount)
        assertTrue(report.failedStep?.message?.contains("loading state", ignoreCase = true) == true)
    }

    @Test
    @Config(sdk = [28])
    @Suppress("DEPRECATION")
    fun `5a-11 android 9 pie compatibility handles WebView nested scroll dialog priority and zero unrecycled nodes`() = runBlocking {
        val service = connectService()

        // Active external window with WebView button + NestedScrollView->RecyclerView on API 28
        val root = makeNode(pkg = "com.example.pieapp", className = "android.widget.FrameLayout")
        val webView = makeNode(pkg = "com.example.pieapp", className = "android.webkit.WebView")
        val webBtn = makeNode(
            pkg = "com.example.pieapp",
            className = "android.widget.Button",
            text = "",
            contentDescription = "Accept Terms",
            clickable = true
        )
        var webClicked = false
        Shadows.shadowOf(webBtn).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                webClicked = true
                true
            } else false
        }
        Shadows.shadowOf(webView).addChild(webBtn)
        Shadows.shadowOf(root).addChild(webView)

        val win = AccessibilityWindowInfo.obtain()
        Shadows.shadowOf(win).apply {
            setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            setActive(true)
            setFocused(true)
            setRoot(root)
        }
        attachWindows(service, listOf(win))

        val clickRes = service.clickUiElement(
            UiElementQuery(text = "Accept Terms"),
            maxAttempts = 1,
            retryDelayMs = 0L
        )
        assertEquals(ScreenInteractionStatus.SUCCESS, clickRes.status)
        assertTrue(webClicked)

        // Strict API 28 node recycling audit on standalone tree
        ShadowAccessibilityNodeInfo.resetObtainedInstances()
        val pieRoot = makeNode(pkg = "com.example.pieapp", text = "Pie Root")
        val recycled = mutableListOf<AccessibilityNodeInfo>()
        val pieEngine = ScreenReaderEngine(
            windowSource = FakeWindowSource(activeRootProvider = { pieRoot }),
            maxAttempts = 1,
            retryDelayMs = 0L,
            onNodeRecycled = { recycled.add(it) }
        )
        assertTrue(pieEngine.readScreen() is ScreenReadResult.Success)
        assertEquals(1, recycled.size)
        assertFalse(
            "Android 9 (API 28) must leave zero unrecycled AccessibilityNodeInfo instances",
            ShadowAccessibilityNodeInfo.areThereUnrecycledNodes(false)
        )
    }

    @Test
    fun `5a-12 never duplicates click or type action while screen is loading`() = runBlocking {
        val service = connectService()
        val root = makeNode(pkg = "com.example.shop", className = "android.widget.LinearLayout")
        val submitSearchBtn = makeNode(
            pkg = "com.example.shop",
            className = "android.widget.Button",
            text = "Search",
            viewId = "com.example.shop:id/search_go",
            clickable = true
        )
        val searchBox = makeNode(
            pkg = "com.example.shop",
            className = "android.widget.EditText",
            text = "",
            hintText = "Search items",
            viewId = "com.example.shop:id/search_input",
            editable = true
        )

        var clickCount = 0
        var typeCount = 0
        Shadows.shadowOf(submitSearchBtn).setOnPerformActionListener { action, _ ->
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                clickCount += 1
                true
            } else false
        }
        Shadows.shadowOf(searchBox).setOnPerformActionListener { action, args ->
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                typeCount += 1
                searchBox.text = args?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                true
            } else false
        }
        Shadows.shadowOf(root).addChild(searchBox)
        Shadows.shadowOf(root).addChild(submitSearchBtn)
        attachActiveExternalWindow(service, root)

        var postClickReads = 0
        val readerBridge = object : AccessibilityScreenReaderBridge {
            override suspend fun readScreen(): ScreenReadResult {
                return if (typeCount == 0) {
                    service.readScreen(maxAttempts = 1, retryDelayMs = 0L)
                } else if (clickCount == 0) {
                    ScreenReadResult.Success(
                        ScreenSnapshot(
                            packageName = "com.example.shop",
                            source = "typed",
                            capturedAtMs = testNowMs,
                            nodes = listOf(
                                snapNode(0, 0, cls = "android.widget.EditText", text = "laptop", viewId = "com.example.shop:id/search_input", editable = true),
                                snapNode(1, 0, cls = "android.widget.Button", text = "Search", viewId = "com.example.shop:id/search_go", clickable = true)
                            ),
                            maxDepthReached = 0,
                            truncatedByDepth = false,
                            truncatedByNodeLimit = false
                        )
                    )
                } else {
                    postClickReads += 1
                    if (postClickReads <= 3) {
                        // Loading spinner shown for 3 verification polls after clicking Search
                        ScreenReadResult.Success(
                            ScreenSnapshot(
                                packageName = "com.example.shop",
                                source = "loading",
                                capturedAtMs = testNowMs,
                                nodes = listOf(
                                    snapNode(0, 0, cls = "android.widget.ProgressBar"),
                                    snapNode(1, 0, text = "Loading...")
                                ),
                                maxDepthReached = 0,
                                truncatedByDepth = false,
                                truncatedByNodeLimit = false
                            )
                        )
                    } else {
                        ScreenReadResult.Success(
                            ScreenSnapshot(
                                packageName = "com.example.shop",
                                source = "results",
                                capturedAtMs = testNowMs,
                                nodes = listOf(
                                    snapNode(0, 0, text = "Results for laptop", viewId = "com.example.shop:id/results_title")
                                ),
                                maxDepthReached = 0,
                                truncatedByDepth = false,
                                truncatedByNodeLimit = false
                            )
                        )
                    }
                }
            }
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
            nowMs = { testNowMs },
            appContext = app,
            screenReaderBridge = readerBridge
        )
        val engine = UiTaskExecutionEngine(
            toolExecutor = customExecutor,
            waitConfig = UiWaitConfig(pollIntervalMs = 5L, actionSettleTimeoutMs = 30L, loadingTimeoutMs = 500L, maxPolls = 15)
        )
        val plan = (UiTaskPlanner.validatePlan(
            objective = "Search for laptop",
            rawSteps = listOf(
                RawTaskStep(
                    "type_text",
                    mapOf("view_id" to JsonPrimitive("search_input"), "text" to JsonPrimitive("laptop"))
                ),
                RawTaskStep(
                    "click_ui_element",
                    mapOf("view_id" to JsonPrimitive("search_go"))
                )
            ),
            expectedFinalPackage = "com.example.shop",
            expectedFinalElement = "Results for laptop"
        ) as TaskPlanValidationResult.Valid).plan

        val report = engine.executePlan(plan)
        assertEquals(TaskExecutionStatus.SUCCEEDED, report.status)
        assertEquals(FinalVerificationStatus.COMPLETED, report.finalVerificationStatus)
        assertEquals("Type action must execute strictly once", 1, typeCount)
        assertEquals("Click action must execute strictly once even during loading", 1, clickCount)
        assertTrue(postClickReads >= 4)
        // Untrusted screen content invariant remains intact
        assertTrue(ScreenReadinessDetector.detectLoadingIndicators(
            ScreenSnapshot("com.example.shop", "x", 0L, listOf(snapNode(cls = "android.widget.ProgressBar")), 0, false, false)
        ).isNotEmpty())
    }
}
