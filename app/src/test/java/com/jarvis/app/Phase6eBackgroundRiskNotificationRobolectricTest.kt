package com.jarvis.app

import android.Manifest
import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.speech.RecognitionService
import android.view.accessibility.AccessibilityEvent
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
import com.jarvis.app.domain.brain.ActionRiskLevel
import com.jarvis.app.domain.brain.ActionRiskPolicy
import com.jarvis.app.domain.brain.ConfirmationTurnOutcome
import com.jarvis.app.domain.brain.DirectActionMatch
import com.jarvis.app.domain.brain.DirectCommandMatcher
import com.jarvis.app.domain.brain.JarvisOrchestrator
import com.jarvis.app.domain.brain.PlannedTaskAction
import com.jarvis.app.domain.brain.PlannedTaskStep
import com.jarvis.app.domain.brain.ToolConfirmationPolicy
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.domain.brain.UiTaskPlan
import com.jarvis.app.domain.brain.isConfirmationRequired
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.domain.model.HudStatus
import com.jarvis.app.platform.AccessibilityWindowSource
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.JarvisAccessibilityService
import com.jarvis.app.platform.JarvisAssistantRuntime
import com.jarvis.app.platform.JarvisNotificationListener
import com.jarvis.app.platform.NotificationSensitivityFilter
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.ScreenReadResult
import com.jarvis.app.platform.ScreenReaderEngine
import com.jarvis.app.platform.SensitiveUiActionPolicy
import com.jarvis.app.platform.TvControlClient
import com.jarvis.app.platform.VoiceAssistantForegroundService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase6eBackgroundRiskNotificationRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: JarvisDatabase
    private lateinit var repo: JarvisRepository
    private lateinit var configStore: ConfigStore
    private lateinit var secretStore: SecureSecretStore
    private lateinit var deviceController: DeviceController
    private lateinit var executor: ToolExecutor
    private lateinit var orchestrator: JarvisOrchestrator
    private var testNowMs = 5_000_000L

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        JarvisAccessibilityService.resetConnectionStateForTest()
        JarvisNotificationListener.resetConnectionStateForTest()
        ShadowAccessibilityNodeInfo.resetObtainedInstances()

        db = Room.inMemoryDatabaseBuilder(app, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = JarvisRepository(db.jarvisDao())
        configStore = ConfigStore(app)
        app.getSharedPreferences("jarvis_secure_secrets_v1", Context.MODE_PRIVATE).edit().clear().commit()
        secretStore = SecureSecretStore(app)
        val firebaseAiService = FirebaseAiService()
        deviceController = DeviceController(app)

        executor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = deviceController,
            firebaseAiService = firebaseAiService,
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            nowMs = { testNowMs },
            appContext = app
        )
        orchestrator = JarvisOrchestrator(
            repository = repo,
            configStore = configStore,
            secretStore = secretStore,
            firebaseAiService = firebaseAiService,
            byoAiClient = ByoAiClient(),
            toolExecutor = executor
        )
    }

    @After
    fun tearDown() {
        runCatching { JarvisAssistantRuntime.getInstance(app).stopAssistant() }
        JarvisAccessibilityService.resetConnectionStateForTest()
        JarvisNotificationListener.resetConnectionStateForTest()
        ShadowAccessibilityNodeInfo.resetObtainedInstances()
        app.getSharedPreferences("jarvis_secure_secrets_v1", Context.MODE_PRIVATE).edit().clear().commit()
        db.close()
    }

    @Suppress("DEPRECATION")
    private fun makeNode(
        pkg: String = "com.example.target",
        className: String = "android.widget.TextView",
        text: String? = null,
        contentDescription: String? = null,
        hintText: String? = null,
        viewId: String? = null,
        bounds: Rect = Rect(10, 20, 400, 100),
        clickable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        password: Boolean = false
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
        node.isVisibleToUser = true
        node.isEnabled = true
        return node
    }

    @Suppress("DEPRECATION")
    private fun attachActiveWindow(
        service: JarvisAccessibilityService,
        root: AccessibilityNodeInfo
    ) {
        val windowInfo = AccessibilityWindowInfo.obtain()
        val shadowWin = Shadows.shadowOf(windowInfo)
        shadowWin.setType(AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowWin.setActive(true)
        shadowWin.setFocused(true)
        shadowWin.setRoot(root)
        Shadows.shadowOf(service).setWindows(listOf(windowInfo))
    }

    @Suppress("DEPRECATION")
    private fun installPackageLabel(pkg: String, label: String) {
        val pkgInfo = PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply {
                packageName = pkg
                nonLocalizedLabel = label
            }
        }
        Shadows.shadowOf(app.packageManager).installPackage(pkgInfo)
    }

    @Suppress("DEPRECATION")
    private fun buildStatusBarNotification(
        id: Int,
        pkg: String,
        title: String,
        text: String,
        postTime: Long,
        visibility: Int = Notification.VISIBILITY_PRIVATE,
        ongoing: Boolean = false,
        flags: Int = 0
    ): StatusBarNotification {
        val notification = Notification.Builder(app)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setVisibility(visibility)
            .build().apply {
                this.flags = this.flags or flags or if (ongoing) Notification.FLAG_ONGOING_EVENT else 0
            }
        return StatusBarNotification(
            pkg,
            pkg,
            id,
            null,
            1000,
            0,
            0,
            notification,
            Process.myUserHandle(),
            postTime
        )
    }

    private fun prepareRuntimeWithMic(): JarvisAssistantRuntime {
        val runtime = JarvisAssistantRuntime.getInstance(app)
        val targets = listOfNotNull(app, runtime.context.applicationContext as? Application)
            .distinctBy { System.identityHashCode(it) }
        val resolveInfo = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = "com.google.android.googlequicksearchbox"
                name = "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
            }
        }
        targets.forEach { targetApp ->
            Shadows.shadowOf(targetApp).grantPermissions(Manifest.permission.RECORD_AUDIO)
            @Suppress("DEPRECATION")
            Shadows.shadowOf(targetApp.packageManager).addResolveInfoForIntent(
                Intent(RecognitionService.SERVICE_INTERFACE),
                resolveInfo
            )
        }
        runtime.stopAssistant()
        ShadowLooper.idleMainLooper()
        return runtime
    }

    @Test
    fun `background screen reading works when voice UI is hidden and refreshes live on window scroll and focus events`() = runBlocking {
        val runtime = prepareRuntimeWithMic()
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        service.onServiceConnected()
        JarvisAccessibilityService.nowMsProvider = { testNowMs }

        runtime.startInAppVoiceMode()
        runtime.hideVoiceOnlyUiKeepSession()
        runtime.onActivityStopped()
        ShadowLooper.idleMainLooper()

        assertFalse(runtime.isVoiceOnlyUiActive.value)
        assertTrue(runtime.isVoiceSessionActive.value)
        assertTrue(JarvisAccessibilityService.isConnectedNow())

        // Window state change event populates live snapshot & conversation context
        val firstRoot = makeNode(pkg = "com.whatsapp", text = "Chat with Rohan")
        Shadows.shadowOf(firstRoot).addChild(
            makeNode(pkg = "com.whatsapp", text = "Kal milte hain", clickable = true)
        )
        attachActiveWindow(service, firstRoot)

        val windowEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = "com.whatsapp"
        }
        service.onAccessibilityEvent(windowEvent)

        val live1 = JarvisAccessibilityService.liveSnapshotState.value
        assertTrue(live1.connected)
        assertEquals("com.whatsapp", live1.activePackage)
        assertTrue(live1.hasAccessibleText)
        assertEquals("com.whatsapp", runtime.getConversationContextManagerForTest().snapshot().currentForegroundApp?.packageName)

        // Advance time beyond throttle and fire scroll + focus events in another app
        testNowMs += 250L
        val scrolledRoot = makeNode(pkg = "com.google.android.youtube", text = "Trending Videos")
        Shadows.shadowOf(scrolledRoot).addChild(
            makeNode(pkg = "com.google.android.youtube", text = "Android 15 Tips", clickable = true)
        )
        attachActiveWindow(service, scrolledRoot)
        service.onAccessibilityEvent(
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED).apply {
                packageName = "com.google.android.youtube"
            }
        )

        testNowMs += 250L
        val focusEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED).apply {
            packageName = "com.google.android.youtube"
        }
        service.onAccessibilityEvent(focusEvent)

        val live2 = JarvisAccessibilityService.liveSnapshotState.value
        assertEquals("com.google.android.youtube", live2.activePackage)
        assertEquals(AccessibilityEvent.TYPE_VIEW_FOCUSED, live2.lastEventType)
        assertEquals("com.google.android.youtube", runtime.getConversationContextManagerForTest().snapshot().currentForegroundApp?.packageName)

        // Password fields are never exposed even during background reading
        testNowMs += 250L
        val authRoot = makeNode(pkg = "com.example.bank", text = "Login Screen")
        Shadows.shadowOf(authRoot).addChild(
            makeNode(pkg = "com.example.bank", text = "MySecretPass99!", password = true, editable = true)
        )
        attachActiveWindow(service, authRoot)
        service.onAccessibilityEvent(
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED).apply {
                packageName = "com.example.bank"
            }
        )
        val authRes = executor.readScreen()
        assertTrue(authRes.ok)
        val dataBlock = authRes.payload["data"]?.jsonPrimitive?.content.orEmpty()
        assertFalse(dataBlock.contains("MySecretPass99!"))
        assertTrue(dataBlock.contains(ScreenReaderEngine.PASSWORD_MASK))
    }

    @Test
    @Config(sdk = [28])
    fun `android 9 pie background reading truthfully reports screens with no accessible text and preserves foreground service`() = runBlocking {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        service.onServiceConnected()

        // Screen with structural views only and zero accessible labels/text
        val blankContainer = makeNode(
            pkg = "com.example.canvasgame",
            className = "android.widget.FrameLayout",
            text = null,
            contentDescription = null,
            hintText = null
        )
        Shadows.shadowOf(blankContainer).addChild(
            makeNode(
                pkg = "com.example.canvasgame",
                className = "android.view.SurfaceView",
                text = "",
                contentDescription = "",
                hintText = ""
            )
        )
        attachActiveWindow(service, blankContainer)

        val readRes = executor.readScreen()
        assertTrue(readRes.ok)
        assertEquals(false, readRes.payload["has_accessible_text"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(0, readRes.payload["readable_text_count"]?.jsonPrimitive?.intOrNull)
        assertTrue(
            "Must truthfully report lack of accessible text instead of inventing content",
            readRes.summary.contains("exposes no accessible text", ignoreCase = true)
        )

        // Verify VoiceAssistantForegroundService builds persistent notification on Android 9
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val fgs = Robolectric.buildService(VoiceAssistantForegroundService::class.java).create().get()
        val notif = fgs.buildNotification(HudStatus.LISTENING)
        assertNotNull(notif)
        assertTrue((notif.flags and Notification.FLAG_ONGOING_EVENT) != 0)
    }

    @Test
    fun `background device control preserves task context across ui hiding and checks live connection before acting`() = runBlocking {
        val runtime = prepareRuntimeWithMic()
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        service.onServiceConnected()

        runtime.startInAppVoiceMode()
        val taskStore = runtime.getTaskContextStoreForTest()
        val started = taskStore.createTask(
            UiTaskPlan(
                id = "task_bg_1",
                objective = "Scroll YouTube feed",
                steps = listOf(
                    PlannedTaskStep(
                        index = 0,
                        action = PlannedTaskAction.SCROLL_UI,
                        description = "Scroll down",
                        args = mapOf("direction" to JsonPrimitive("down")),
                        expectedPackage = "com.google.android.youtube"
                    )
                ),
                createdAtMs = testNowMs,
                expectedFinalPackage = "com.google.android.youtube"
            )
        )
        assertNotNull(started)
        runtime.getConversationContextManagerForTest().onForegroundAppObserved("com.google.android.youtube", "YouTube")

        // Hide VoiceOnlyAssistantScreen and simulate background transition (onActivityStopped)
        runtime.hideVoiceOnlyUiKeepSession()
        runtime.onActivityStopped()
        ShadowLooper.idleMainLooper()

        assertTrue(JarvisAccessibilityService.isConnectedNow())
        assertTrue(taskStore.hasActiveTask())
        assertEquals("com.google.android.youtube", runtime.getConversationContextManagerForTest().snapshot().currentForegroundApp?.packageName)

        // Disconnect AccessibilityService -> subsequent accessibility actions fail cleanly without crashing
        service.onUnbind(null)
        assertFalse(JarvisAccessibilityService.isConnectedNow())
        val navWhileDisconnected = executor.execute("accessibility_navigate", mapOf("action" to JsonPrimitive("back"))) {}
        assertFalse(navWhileDisconnected.ok)
        assertTrue(navWhileDisconnected.summary.contains("Accessibility", ignoreCase = true))
    }

    @Test
    fun `smart confirmation policy separates low medium and high risk actions and avoids friction on benign actions`() = runBlocking {
        // 1. LOW risk actions execute immediately without confirmation
        val lowRiskCalls = listOf(
            "device_control" to mapOf("action" to JsonPrimitive("wifi_settings")),
            "device_control" to mapOf("action" to JsonPrimitive("bluetooth_settings")),
            "device_control" to mapOf("action" to JsonPrimitive("volume_up")),
            "device_control" to mapOf("action" to JsonPrimitive("flashlight_on")),
            "device_control" to mapOf("action" to JsonPrimitive("dnd_off")),
            "open_application" to mapOf("name" to JsonPrimitive("YouTube")),
            "manage_schedule" to mapOf("action" to JsonPrimitive("add"), "task" to JsonPrimitive("Call Priya")),
            "customize_screen" to mapOf("action" to JsonPrimitive("set_look"), "value" to JsonPrimitive("modern")),
            "pc_task" to mapOf("kind" to JsonPrimitive("browser"), "goal" to JsonPrimitive("open stackoverflow.com"))
        )
        for ((tool, args) in lowRiskCalls) {
            val assessment = ActionRiskPolicy.assess(tool, args)
            assertEquals("Expected LOW risk for $tool($args)", ActionRiskLevel.LOW, assessment.level)
            assertFalse("Expected no confirmation for $tool($args)", assessment.requiresConfirmation)
            val res = executor.execute(tool, args) {}
            assertFalse("Tool $tool($args) should not pause for confirmation", res.isConfirmationRequired())
        }

        // 2. Benign UI click & Settings search type_text should be LOW risk (no nuisance prompts)
        val lowClick = ActionRiskPolicy.assess(
            "click_ui_element",
            mapOf("_bound_package" to JsonPrimitive("com.android.settings"), "text" to JsonPrimitive("Wi-Fi"))
        )
        assertEquals(ActionRiskLevel.LOW, lowClick.level)
        assertFalse(lowClick.requiresConfirmation)

        val lowSettingsSearch = ActionRiskPolicy.assess(
            "type_text",
            mapOf(
                "_bound_package" to JsonPrimitive("com.android.settings"),
                "hint" to JsonPrimitive("Search settings"),
                "text" to JsonPrimitive("bluetooth")
            )
        )
        assertEquals(ActionRiskLevel.LOW, lowSettingsSearch.level)
        assertFalse(lowSettingsSearch.requiresConfirmation)
        assertFalse(
            SensitiveUiActionPolicy.isSensitiveInputText(
                "Search settings",
                packageName = "com.android.settings"
            )
        )

        // 3. MEDIUM risk actions require confirmation cleanly
        val dndAssessment = ActionRiskPolicy.assess("device_control", mapOf("action" to JsonPrimitive("dnd_on")))
        assertEquals(ActionRiskLevel.MEDIUM, dndAssessment.level)
        assertTrue(dndAssessment.requiresConfirmation)
        val dndRes = executor.execute("device_control", mapOf("action" to JsonPrimitive("dnd_on"))) {}
        assertTrue(dndRes.isConfirmationRequired())
        assertEquals("MEDIUM", dndRes.payload["risk_level"]?.jsonPrimitive?.content)
        executor.clearPendingConfirmation()

        // 4. HIGH risk actions always require confirmation
        val highRiskCases = listOf(
            "delete_file" to mapOf("path" to JsonPrimitive("secret.txt")),
            "uninstall_app" to mapOf("package" to JsonPrimitive("com.example.app")),
            "send_message" to mapOf("to" to JsonPrimitive("Mom"), "body" to JsonPrimitive("On my way")),
            "forget_fact" to mapOf("text" to JsonPrimitive("everything")),
            "manage_schedule" to mapOf("action" to JsonPrimitive("clear")),
            "control_interface" to mapOf("action" to JsonPrimitive("clear_chat")),
            "pc_task" to mapOf("kind" to JsonPrimitive("shell"), "goal" to JsonPrimitive("rm -rf ~/Downloads/old")),
            "click_ui_element" to mapOf(
                "_bound_package" to JsonPrimitive("com.android.settings"),
                "text" to JsonPrimitive("Force stop")
            ),
            "click_ui_element" to mapOf(
                "_bound_package" to JsonPrimitive("com.google.android.packageinstaller"),
                "text" to JsonPrimitive("Allow")
            ),
            "click_ui_element" to mapOf(
                "_bound_package" to JsonPrimitive("com.example.shop"),
                "text" to JsonPrimitive("Pay Now")
            ),
            "click_ui_element" to mapOf(
                "_bound_package" to JsonPrimitive("com.android.vending"),
                "text" to JsonPrimitive("Disable Play Protect")
            )
        )
        for ((tool, args) in highRiskCases) {
            val assessment = ActionRiskPolicy.assess(tool, args)
            assertEquals("Expected HIGH risk for $tool($args)", ActionRiskLevel.HIGH, assessment.level)
            assertTrue("Expected confirmation for $tool($args)", assessment.requiresConfirmation)
        }

        // Bound confirmation integrity check: single-use & exact binding
        val delReq = executor.execute("delete_file", mapOf("path" to JsonPrimitive("notes.txt"))) {}
        assertTrue(delReq.isConfirmationRequired())
        assertEquals("HIGH", delReq.payload["risk_level"]?.jsonPrimitive?.content)
        val confirmOutcome = executor.handleConfirmationInput("haan confirm karo") {}
        assertTrue(confirmOutcome is ConfirmationTurnOutcome.Executed)
        assertNull(executor.getPendingConfirmation())
    }

    @Test
    fun `notification reading prompts for access when disconnected and filters otp pin secret and excluded apps without disk persistence`() = runBlocking {
        // 1. Disconnected state returns actionable prompt + Settings action without crashing
        val disconnectedRes = executor.execute("read_notifications", emptyMap()) {}
        assertFalse(disconnectedRes.ok)
        assertEquals("PERMISSION_DENIED", disconnectedRes.payload["error_code"]?.jsonPrimitive?.content)
        assertEquals(
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            disconnectedRes.payload["settings_action"]?.jsonPrimitive?.content
        )

        // 2. Connect listener with mixed safe, OTP, secret, and excluded-app notifications
        installPackageLabel("com.whatsapp", "WhatsApp")
        installPackageLabel("com.google.android.gm", "Gmail")
        installPackageLabel("com.bank.secure", "SecureBank")

        val listener = Robolectric.buildService(JarvisNotificationListener::class.java).create().get()
        listener.onListenerConnected()
        assertTrue(JarvisNotificationListener.isConnectedNow())

        JarvisNotificationListener.activeNotificationsProviderForTest = {
            arrayOf(
                buildStatusBarNotification(
                    id = 1,
                    pkg = "com.whatsapp",
                    title = "Priya",
                    text = "Dinner at 8 PM?",
                    postTime = 3000L
                ),
                buildStatusBarNotification(
                    id = 2,
                    pkg = "com.bank.secure",
                    title = "SecureBank",
                    text = "Your OTP is 482910. Do not share this code.",
                    postTime = 4000L
                ),
                buildStatusBarNotification(
                    id = 3,
                    pkg = "com.google.android.gm",
                    title = "Private Vault",
                    text = "Hidden lockscreen message",
                    postTime = 5000L,
                    visibility = Notification.VISIBILITY_SECRET
                ),
                buildStatusBarNotification(
                    id = 4,
                    pkg = "com.google.android.gm",
                    title = "Team Sync",
                    text = "Sprint review moved to 4 PM",
                    postTime = 2000L
                )
            )
        }

        // Verify filter accuracy directly
        assertTrue(NotificationSensitivityFilter.isSensitive("Bank", "Your OTP is 482910", ""))
        assertTrue(NotificationSensitivityFilter.isSensitive("UPI", "Enter your UPI PIN to continue", ""))
        assertTrue(NotificationSensitivityFilter.isSensitive("Auth", "Your verification code 9921 is valid for 5 min", ""))
        assertTrue(NotificationSensitivityFilter.isSensitive("Alert", "Secret", "", Notification.VISIBILITY_SECRET))
        assertFalse(NotificationSensitivityFilter.isSensitive("Priya", "Dinner at 8 PM?", ""))

        // Execute read_notifications
        val readAllRes = executor.execute("read_notifications", emptyMap()) {}
        assertTrue(readAllRes.ok)
        assertEquals(2, readAllRes.payload["notification_count"]?.jsonPrimitive?.intOrNull)
        assertEquals(2, readAllRes.payload["hidden_sensitive_count"]?.jsonPrimitive?.intOrNull)
        assertTrue(readAllRes.summary.contains("Priya"))
        assertTrue(readAllRes.summary.contains("Team Sync"))
        assertFalse("OTP must never appear in notification readout", readAllRes.summary.contains("482910"))
        assertFalse("VISIBILITY_SECRET body must never appear", readAllRes.summary.contains("Hidden lockscreen message"))

        // Privacy guarantee: notification message bodies must NEVER be persisted in Room action logs
        val actionLogs = repo.actionLogs.first()
        val notifLog = actionLogs.firstOrNull { it.toolName == "read_notifications" }
        assertNotNull(notifLog)
        assertFalse(notifLog!!.summary.contains("Dinner at 8 PM?"))
        assertFalse(notifLog.summary.contains("Sprint review moved to 4 PM"))
        assertFalse(notifLog.summary.contains("482910"))

        // 3. Excluded app filtering
        configStore.updateBrainConfig {
            it.copy(excludedNotificationPackages = setOf("com.whatsapp"))
        }
        val excludedRes = executor.execute("read_notifications", emptyMap()) {}
        assertTrue(excludedRes.ok)
        assertEquals(1, excludedRes.payload["notification_count"]?.jsonPrimitive?.intOrNull)
        assertEquals(1, excludedRes.payload["excluded_count"]?.jsonPrimitive?.intOrNull)
        assertFalse(excludedRes.summary.contains("Dinner at 8 PM?"))
        assertTrue(excludedRes.summary.contains("Team Sync"))

        // 4. Disable toggle blocks notification reading completely
        configStore.updateBrainConfig {
            it.copy(notificationReadingEnabled = false)
        }
        val disabledRes = executor.execute("read_notifications", emptyMap()) {}
        assertFalse(disabledRes.ok)
        assertEquals("NOTIFICATION_READING_DISABLED", disabledRes.payload["error_code"]?.jsonPrimitive?.content)

        // 5. Disconnecting listener clears connection state
        listener.onListenerDisconnected()
        assertFalse(JarvisNotificationListener.isConnectedNow())
    }

    @Test
    fun `multilingual local notification commands work offline in English Hinglish Hindi and Marathi without AI key`() = runBlocking {
        installPackageLabel("com.whatsapp", "WhatsApp")
        val listener = Robolectric.buildService(JarvisNotificationListener::class.java).create().get()
        listener.onListenerConnected()
        JarvisNotificationListener.activeNotificationsProviderForTest = {
            arrayOf(
                buildStatusBarNotification(
                    id = 10,
                    pkg = "com.whatsapp",
                    title = "Aarav",
                    text = "Reached home safely",
                    postTime = 6000L
                )
            )
        }

        // Verify DirectCommandMatcher parses English, Hinglish, Hindi, Marathi locally
        val hinglishMatch = DirectCommandMatcher.match("Mere notifications padho") as? DirectActionMatch.ToolCall
        assertNotNull(hinglishMatch)
        assertEquals("read_notifications", hinglishMatch!!.toolName)

        val latestMatch = DirectCommandMatcher.match("Latest notification kiski hai?") as? DirectActionMatch.ToolCall
        assertNotNull(latestMatch)
        assertEquals("read_notifications", latestMatch!!.toolName)
        assertEquals("true", latestMatch.args["latest_only"])

        val appMatch = DirectCommandMatcher.match("WhatsApp notification sunao") as? DirectActionMatch.ToolCall
        assertNotNull(appMatch)
        assertEquals("read_notifications", appMatch!!.toolName)
        assertEquals("WhatsApp", appMatch.args["app"])

        val marathiMatch = DirectCommandMatcher.match("माझे नोटिफिकेशन्स वाच") as? DirectActionMatch.ToolCall
        assertNotNull(marathiMatch)
        assertEquals("read_notifications", marathiMatch!!.toolName)

        // Panel navigation still opens notification panel without confusion
        val panelMatch = DirectCommandMatcher.match("open notification panel") as? DirectActionMatch.ToolCall
        assertNotNull(panelMatch)
        assertEquals("accessibility_navigate", panelMatch!!.toolName)

        // End-to-end orchestrator execution with NO AI API keys (BYO + Firebase unavailable)
        val noKeyConfig = BrainConfig(activeProvider = "auto")
        val hinglishEvents = orchestrator.executeTurn(
            rawUserInput = "Mere notifications padho",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = noKeyConfig
        ).toList()
        val hinglishReply = hinglishEvents.filterIsInstance<TurnProgressEvent.Finished>().first().replyText
        assertTrue(hinglishReply.contains("Aarav"))
        assertTrue(hinglishReply.contains("notifications", ignoreCase = true))

        val marathiEvents = orchestrator.executeTurn(
            rawUserInput = "माझे नोटिफिकेशन्स वाच",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = noKeyConfig
        ).toList()
        val marathiReply = marathiEvents.filterIsInstance<TurnProgressEvent.Finished>().first().replyText
        assertTrue(marathiReply.contains("Aarav"))
        assertTrue(marathiReply.contains("नोटिफिकेशन्स"))
    }
}
