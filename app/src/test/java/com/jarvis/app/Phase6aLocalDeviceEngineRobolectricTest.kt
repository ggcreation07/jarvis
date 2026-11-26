package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.ai.type.FunctionCallPart
import com.jarvis.app.data.ai.AiStreamEvent
import com.jarvis.app.data.ai.ByoAiClient
import com.jarvis.app.data.ai.DefaultProviderEndpoints
import com.jarvis.app.data.ai.DynamicModelInfo
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.ai.ProviderEndpoints
import com.jarvis.app.data.ai.ToolCallExecutionResult
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisDatabase
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.network.JarvisHttpTools
import com.jarvis.app.data.security.SecureSecretStore
import com.jarvis.app.domain.brain.AccessibilityInteractionBridge
import com.jarvis.app.domain.brain.AccessibilityNavigationBridge
import com.jarvis.app.domain.brain.AccessibilityScreenReaderBridge
import com.jarvis.app.domain.brain.DirectCommandMatcher
import com.jarvis.app.domain.brain.JarvisOrchestrator
import com.jarvis.app.domain.brain.LocalCommandMatch
import com.jarvis.app.domain.brain.LocalDeviceCommandEngine
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.platform.CapabilityAvailabilityStatus
import com.jarvis.app.platform.ClickInspectionCandidate
import com.jarvis.app.platform.ClickPrecheckOutcome
import com.jarvis.app.platform.ClickUiElementResult
import com.jarvis.app.platform.DeviceActionId
import com.jarvis.app.platform.DeviceCapabilityRegistry
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.FindUiElementsResult
import com.jarvis.app.platform.JarvisAccessibilityService
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
import com.jarvis.app.platform.VoiceController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase6aLocalDeviceEngineRobolectricTest {

    private lateinit var app: Application
    private lateinit var db: JarvisDatabase
    private lateinit var repo: JarvisRepository
    private lateinit var configStore: ConfigStore
    private lateinit var secretStore: SecureSecretStore
    private var testNowMs = 5_000_000L

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
        db.close()
    }

    private fun registerFlashCamera(id: String = "0", hasFlash: Boolean) {
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadowChars = Shadows.shadowOf(characteristics)
        shadowChars.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, hasFlash)
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
        val actBuilder = org.robolectric.shadow.api.Shadow.newInstanceOf(Activity::class.java)
        assertNotNull(actBuilder)
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

    @Test
    fun `6a-1 api key absent executes local commands cleanly and refuses fake success on AI queries`() = runBlocking {
        registerFlashCamera("0", hasFlash = true)
        registerLaunchableApp("com.google.android.youtube", "YouTube")

        val navActions = mutableListOf<String>()
        val navBridge = object : AccessibilityNavigationBridge {
            override fun isSettingsEnabled(): Boolean = true
            override fun isServiceConnected(): Boolean = true
            override fun performAction(action: String): Boolean {
                navActions.add(action)
                return true
            }
        }
        val screenBridge = object : AccessibilityScreenReaderBridge {
            override suspend fun readScreen(): ScreenReadResult = ScreenReadResult.Success(
                ScreenSnapshot(
                    packageName = "com.example.notes",
                    source = "accessibility_active_window",
                    capturedAtMs = testNowMs,
                    nodes = listOf(
                        UiNodeSnapshot(
                            index = 0,
                            depth = 0,
                            packageName = "com.example.notes",
                            className = "android.widget.TextView",
                            text = "Shopping List",
                            contentDescription = "",
                            hintText = "",
                            viewId = "title",
                            bounds = NodeBounds(0, 0, 100, 40),
                            isClickable = false,
                            isScrollable = false,
                            isEditable = false,
                            isPassword = false,
                            isVisibleToUser = true,
                            isEnabled = true,
                            isFocused = false
                        )
                    ),
                    maxDepthReached = 0,
                    truncatedByDepth = false,
                    truncatedByNodeLimit = false
                )
            )
        }

        val spyAi = SpyFirebaseAiService(available = false)
        val executor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = spyAi,
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            nowMs = { testNowMs },
            appContext = app,
            accessibilityBridge = navBridge,
            screenReaderBridge = screenBridge
        )
        val registry = DeviceCapabilityRegistry(app)
        val orchestrator = JarvisOrchestrator(
            repository = repo,
            configStore = configStore,
            secretStore = secretStore,
            firebaseAiService = spyAi,
            byoAiClient = ByoAiClient(),
            toolExecutor = executor,
            capabilityRegistry = registry,
            isFirebaseAiAvailable = { false }
        )

        // 1. Time works locally without AI key
        val timeFin = orchestrator.executeTurn("अभी क्या समय है?", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse(timeFin.isError)
        assertTrue(timeFin.activities.isNotEmpty())

        // 2. Flashlight works locally in Hindi
        val flashFin = orchestrator.executeTurn("फ्लैशलाइट ऑन करो", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse(flashFin.isError)
        assertTrue(flashFin.replyText.contains("Flashlight turned on", ignoreCase = true))

        // 3. Open app works locally in Marathi
        val appFin = orchestrator.executeTurn("यूट्यूब उघडा", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse(appFin.isError)
        assertEquals("com.google.android.youtube", Shadows.shadowOf(app).nextStartedActivity?.`package`)

        // 4. Back navigation works locally in Hinglish
        val backFin = orchestrator.executeTurn("back jao", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse(backFin.isError)
        assertEquals(listOf("back"), navActions)

        // 5. Screen reading works locally with untrusted framing
        val readFin = orchestrator.executeTurn("read screen", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse(readFin.isError)
        assertTrue(readFin.replyText.contains(ScreenReaderEngine.UNTRUSTED_HEADER))
        assertTrue(readFin.replyText.contains("Shopping List"))

        // 6. General knowledge prompt when NO AI provider & NO API key -> honest failure
        val aiAutoFin = orchestrator.executeTurn("Write a poem about Mars", null, emptyList(), BrainConfig(activeProvider = "auto"))
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(aiAutoFin.isError)
        assertTrue(aiAutoFin.replyText.contains("AI provider", ignoreCase = true))
        assertTrue(aiAutoFin.replyText.contains("without any API key", ignoreCase = true))

        // 7. Missing BYO API key still reports clear configuration message
        val aiGroqFin = orchestrator.executeTurn(
            "Explain relativity",
            null,
            emptyList(),
            BrainConfig(activeProvider = "groq", activeModel = "llama-3.3-70b-versatile")
        ).toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(aiGroqFin.isError)
        assertTrue(aiGroqFin.replyText.contains("No API key saved for groq"))

        assertEquals(0, spyAi.streamCalls.get())
    }

    @Test
    fun `6a-2 local commands take priority even when AI provider and API key are fully configured`() = runBlocking {
        registerFlashCamera("0", hasFlash = true)
        secretStore.setSecret(SecureSecretStore.KEY_GROQ, "gsk_test_configured")
        configStore.saveProviderModelsCache(
            "groq",
            listOf(DynamicModelInfo(id = "llama-3.3-70b-versatile", providerId = "groq"))
        )

        val byoCalls = AtomicInteger(0)
        val spyEndpoints = object : ProviderEndpoints by DefaultProviderEndpoints {
            override fun groqChatUrl(): String {
                byoCalls.incrementAndGet()
                return "http://127.0.0.1:1"
            }
        }
        val spyAi = SpyFirebaseAiService(available = true)
        val executor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = spyAi,
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            appContext = app
        )
        val registry = DeviceCapabilityRegistry(app)
        val orchestrator = JarvisOrchestrator(
            repository = repo,
            configStore = configStore,
            secretStore = secretStore,
            firebaseAiService = spyAi,
            byoAiClient = ByoAiClient(endpoints = spyEndpoints),
            toolExecutor = executor,
            capabilityRegistry = registry,
            isFirebaseAiAvailable = { true }
        )

        val groqCfg = BrainConfig(activeProvider = "groq", activeModel = "llama-3.3-70b-versatile")
        val autoCfg = BrainConfig(activeProvider = "auto")

        val flashRes = orchestrator.executeTurn("torch chalu karo", null, emptyList(), groqCfg)
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse(flashRes.isError)

        val settingsRes = orchestrator.executeTurn("वाईफाई सेटिंग्स खोलो", null, emptyList(), autoCfg)
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse(settingsRes.isError)
        assertEquals(Settings.ACTION_WIFI_SETTINGS, Shadows.shadowOf(app).nextStartedActivity?.action)

        assertEquals(0, spyAi.streamCalls.get())
        assertEquals(0, byoCalls.get())
    }

    @Test
    fun `6a-3 device capability registry reflects dynamic hardware permission and service changes`() {
        var ttsReady = false
        val speechAvail = true
        var offlineConfirmed = false
        val registry = DeviceCapabilityRegistry(
            context = app,
            ttsReadyProvider = { ttsReady },
            speechAvailableProvider = { speechAvail },
            offlineSpeechConfirmedProvider = { offlineConfirmed },
            nowMs = { testNowMs }
        )

        // Initially no flash camera, no audio permission, no DND access, no accessibility
        val initial = registry.refresh()
        assertEquals(Build.VERSION.SDK_INT, initial.apiLevel)
        assertFalse(initial.flashlightAvailable)
        assertEquals(CapabilityAvailabilityStatus.UNSUPPORTED_HARDWARE, initial.statusFor(DeviceActionId.FLASHLIGHT)?.status)
        assertEquals(CapabilityAvailabilityStatus.PERMISSION_DENIED, initial.statusFor(DeviceActionId.SPEECH_RECOGNITION)?.status)
        assertEquals(CapabilityAvailabilityStatus.PERMISSION_DENIED, initial.statusFor(DeviceActionId.OFFLINE_SPEECH_RECOGNITION)?.status)
        assertEquals(CapabilityAvailabilityStatus.PERMISSION_DENIED, initial.statusFor(DeviceActionId.DO_NOT_DISTURB)?.status)
        assertEquals(CapabilityAvailabilityStatus.SERVICE_DISCONNECTED, initial.statusFor(DeviceActionId.SCREEN_READ)?.status)
        assertEquals(CapabilityAvailabilityStatus.RESTRICTED_SYSTEM_ACTION, initial.statusFor(DeviceActionId.WIFI_DIRECT_TOGGLE)?.status)
        assertEquals(CapabilityAvailabilityStatus.RESTRICTED_SYSTEM_ACTION, initial.statusFor(DeviceActionId.BLUETOOTH_DIRECT_TOGGLE)?.status)
        assertEquals(CapabilityAvailabilityStatus.RESTRICTED_SYSTEM_ACTION, initial.statusFor(DeviceActionId.MOBILE_DATA_TOGGLE)?.status)
        assertEquals(CapabilityAvailabilityStatus.UNAVAILABLE, initial.statusFor(DeviceActionId.TEXT_TO_SPEECH)?.status)

        // Grant flash camera, RECORD_AUDIO, DND policy, TTS ready, but keep offline STT unconfirmed
        registerFlashCamera("0", hasFlash = true)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(nm).setNotificationPolicyAccessGranted(true)
        ttsReady = true

        val granted = registry.refresh()
        assertTrue(granted.flashlightAvailable)
        assertEquals(CapabilityAvailabilityStatus.AVAILABLE, granted.statusFor(DeviceActionId.FLASHLIGHT)?.status)
        assertEquals(CapabilityAvailabilityStatus.AVAILABLE, granted.statusFor(DeviceActionId.SPEECH_RECOGNITION)?.status)
        // Must NOT falsely claim offline recognition works when Android hasn't confirmed it
        assertEquals(CapabilityAvailabilityStatus.UNAVAILABLE, granted.statusFor(DeviceActionId.OFFLINE_SPEECH_RECOGNITION)?.status)
        assertEquals(CapabilityAvailabilityStatus.AVAILABLE, granted.statusFor(DeviceActionId.DO_NOT_DISTURB)?.status)
        assertEquals(CapabilityAvailabilityStatus.AVAILABLE, granted.statusFor(DeviceActionId.TEXT_TO_SPEECH)?.status)

        // Confirm offline STT -> AVAILABLE
        offlineConfirmed = true
        assertEquals(
            CapabilityAvailabilityStatus.AVAILABLE,
            registry.refresh().statusFor(DeviceActionId.OFFLINE_SPEECH_RECOGNITION)?.status
        )

        // Revoke RECORD_AUDIO & DND -> immediately updates on refresh
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        Shadows.shadowOf(nm).setNotificationPolicyAccessGranted(false)
        val revoked = registry.refresh()
        assertEquals(CapabilityAvailabilityStatus.PERMISSION_DENIED, revoked.statusFor(DeviceActionId.SPEECH_RECOGNITION)?.status)
        assertEquals(CapabilityAvailabilityStatus.PERMISSION_DENIED, revoked.statusFor(DeviceActionId.OFFLINE_SPEECH_RECOGNITION)?.status)
        assertEquals(CapabilityAvailabilityStatus.PERMISSION_DENIED, revoked.statusFor(DeviceActionId.DO_NOT_DISTURB)?.status)
        assertEquals(CapabilityAvailabilityStatus.PERMISSION_DENIED, registry.capabilities.value.statusFor(DeviceActionId.SPEECH_RECOGNITION)?.status)
    }

    @Test
    fun `6a-4 unavailable hardware disconnected accessibility and restricted toggles fail safely without pretending`() = runBlocking {
        val spyAi = SpyFirebaseAiService(available = false)
        val executor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = spyAi,
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            appContext = app
        )
        val registry = DeviceCapabilityRegistry(app)
        val orchestrator = JarvisOrchestrator(
            repository = repo,
            configStore = configStore,
            secretStore = secretStore,
            firebaseAiService = spyAi,
            byoAiClient = ByoAiClient(),
            toolExecutor = executor,
            capabilityRegistry = registry,
            isFirebaseAiAvailable = { false }
        )

        // 1. Missing flashlight hardware
        val noFlash = orchestrator.executeTurn("turn on flashlight", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(noFlash.isError)
        assertTrue(noFlash.replyText.contains("No camera flash", ignoreCase = true))

        // 2. Disconnected accessibility service for navigation / read / scroll / click / type
        for (cmd in listOf("go back", "read screen", "scroll down", "click Next", "type 'abc' into Search")) {
            val res = orchestrator.executeTurn(cmd, null, emptyList(), BrainConfig())
                .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
            assertTrue("Expected error for '$cmd' when accessibility disconnected", res.isError)
            assertTrue("Expected Accessibility guidance for '$cmd': ${res.replyText}", res.replyText.contains("Accessibility", ignoreCase = true))
        }

        // 3. Restricted Wi-Fi / Bluetooth / Mobile Data toggles open safe settings & report restricted (ok=false)
        val wifiFin = orchestrator.executeTurn("वाईफाई चालू करो", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(wifiFin.isError)
        assertTrue(wifiFin.replyText.contains("restrict", ignoreCase = true))
        assertEquals(Settings.ACTION_WIFI_SETTINGS, Shadows.shadowOf(app).nextStartedActivity?.action)

        val btFin = orchestrator.executeTurn("bluetooth on karo", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(btFin.isError)
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, Shadows.shadowOf(app).nextStartedActivity?.action)

        val dataFin = orchestrator.executeTurn("मोबाइल डेटा बंद करा", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(dataFin.isError)
        assertEquals(Settings.ACTION_WIRELESS_SETTINGS, Shadows.shadowOf(app).nextStartedActivity?.action)
    }

    @Test
    fun `6a-5 actual execution verification for volume and do not disturb`() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(10)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol / 2).coerceAtLeast(1), 0)
        val beforeVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)

        val controller = DeviceController(app)

        // Volume up: checks actual post-adjustment state and never claims success if unchanged
        val upOutcome = controller.adjustVolume("up")
        val afterUp = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (afterUp > beforeVol) {
            assertTrue(upOutcome.ok)
            assertTrue(upOutcome.summary.contains("Turned media volume up"))
        } else {
            assertFalse(upOutcome.ok)
        }

        // Direct percentage set verifies target volume matches
        val pctOutcome = controller.adjustVolume("set", levelPercent = 60)
        val expectedTarget = ((60 / 100f) * maxVol).toInt()
        assertTrue(pctOutcome.ok)
        assertEquals(expectedTarget, am.getStreamVolume(AudioManager.STREAM_MUSIC))

        // DND denied -> opens DND settings & returns ok = false
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Shadows.shadowOf(nm).setNotificationPolicyAccessGranted(false)
        val dndDenied = controller.setDoNotDisturb(true)
        assertFalse(dndDenied.ok)
        assertEquals(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, Shadows.shadowOf(app).nextStartedActivity?.action)

        // DND granted -> applies & verifies filter change
        Shadows.shadowOf(nm).setNotificationPolicyAccessGranted(true)
        val dndGranted = controller.setDoNotDisturb(true)
        assertTrue(dndGranted.ok)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, nm.currentInterruptionFilter)
    }

    @Test
    fun `6a-6 multilingual hindi marathi english hinglish command matching and DirectCommandMatcher compatibility`() {
        val spyAi = SpyFirebaseAiService(available = false)
        val executor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = spyAi,
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            appContext = app
        )
        val engine = LocalDeviceCommandEngine(executor)

        // Preserve Phase 2 assertion: DirectCommandMatcher still returns null for "read screen"
        assertNull(DirectCommandMatcher.match("read screen"))

        val expectedTools = listOf(
            // Volume
            "volume up" to "device_control",
            "awaaz badhao" to "device_control",
            "आवाज़ बढ़ाओ" to "device_control",
            "आवाज वाढवा" to "device_control",
            "आवाज कमी करा" to "device_control",
            "वॉल्यूम म्यूट करा" to "device_control",
            "अनम्यूट करो" to "device_control",
            // Flashlight
            "turn on flashlight" to "device_control",
            "torch jalao" to "device_control",
            "टॉर्च चालू करो" to "device_control",
            "फ्लॅशलाइट बंद करा" to "device_control",
            // Open apps
            "open Spotify" to "open_application",
            "WhatsApp kholo" to "open_application",
            "व्हाट्सएप खोलो" to "open_application",
            "क्रोम उघडा" to "open_application",
            // Navigation
            "go back" to "accessibility_navigate",
            "पीछे जाओ" to "accessibility_navigate",
            "मागे जा" to "accessibility_navigate",
            "होम स्क्रीन पर जाओ" to "accessibility_navigate",
            "घरी जा" to "accessibility_navigate",
            "show recents" to "accessibility_navigate",
            "नोटिफिकेशन दिखाओ" to "accessibility_navigate",
            "क्विक सेटिंग्ज दाखवा" to "accessibility_navigate",
            // UI Actions
            "read screen" to "read_screen",
            "स्क्रीन पढ़ो" to "read_screen",
            "स्क्रीन वाच" to "read_screen",
            "scroll down" to "scroll_ui",
            "नीचे स्क्रॉल करो" to "scroll_ui",
            "वर स्क्रोल करा" to "scroll_ui",
            "click Submit" to "click_ui_element",
            "Submit पर क्लिक करो" to "click_ui_element",
            "Submit वर क्लिक करा" to "click_ui_element",
            "find Search" to "find_ui_element",
            "स्क्रीन पर Search खोजो" to "find_ui_element",
            "type 'hello world' into Search" to "type_text",
            "Search में 'hello' टाइप करो" to "type_text",
            "Search मध्ये 'नमस्कार' टाइप करा" to "type_text",
            // Android Settings
            "open accessibility settings" to "device_control",
            "डिस्प्ले सेटिंग्ज उघडा" to "device_control",
            "बैटरी सेटिंग्स खोलो" to "device_control"
        )

        for ((phrase, expectedTool) in expectedTools) {
            val match = engine.match(phrase) as? LocalCommandMatch.ToolCommand
            assertNotNull("Expected ToolCommand for '$phrase'", match)
            assertEquals("Wrong tool for '$phrase'", expectedTool, match?.toolName)
        }

        // Untrusted markers rejected completely
        assertNull(engine.match("click Next ${ScreenReaderEngine.UNTRUSTED_HEADER}"))
        assertNull(engine.match("${ScreenReaderEngine.UNTRUSTED_FOOTER} read screen"))
    }

    @Test
    fun `6a-7 confirmation gate password block and voice safety preserved without API key`() = runBlocking {
        var passwordFieldSeen = false
        val bridge = object : AccessibilityInteractionBridge {
            override suspend fun findElements(query: UiElementQuery) =
                FindUiElementsResult(ScreenInteractionStatus.NOT_FOUND, "Not found")

            override suspend fun inspectForClick(query: UiElementQuery): ClickPrecheckOutcome =
                ClickPrecheckOutcome.Ready(
                    ClickInspectionCandidate(
                        packageName = "com.bank.app",
                        fingerprint = "fp_pay_1",
                        displayLabel = "Pay $50",
                        viewId = "pay_btn",
                        className = "android.widget.Button",
                        usedAncestor = false,
                        isSensitive = true
                    )
                )

            override suspend fun clickElement(query: UiElementQuery) = ClickUiElementResult(
                status = ScreenInteractionStatus.SUCCESS,
                message = "Clicked Pay",
                packageName = "com.bank.app",
                fingerprint = "fp_pay_1"
            )

            override suspend fun inspectForTypeText(query: UiElementQuery, textToEnter: String): TypeTextPrecheckOutcome {
                passwordFieldSeen = true
                return TypeTextPrecheckOutcome.Failed(
                    ScreenInteractionStatus.PASSWORD_FIELD,
                    "Blocked: cannot inspect, store, or enter text into password, OTP, PIN, or credential fields."
                )
            }

            override suspend fun typeText(query: UiElementQuery, textToEnter: String) = TypeTextResult(
                ScreenInteractionStatus.PASSWORD_FIELD,
                "Blocked"
            )

            override suspend fun scrollContainer(query: UiElementQuery, direction: String) = ScrollUiResult(
                ScreenInteractionStatus.ACTION_FAILED,
                "None"
            )
        }

        val spyAi = SpyFirebaseAiService(available = false)
        val executor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = spyAi,
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            nowMs = { testNowMs },
            appContext = app,
            interactionBridge = bridge
        )
        val orchestrator = JarvisOrchestrator(
            repository = repo,
            configStore = configStore,
            secretStore = secretStore,
            firebaseAiService = spyAi,
            byoAiClient = ByoAiClient(),
            toolExecutor = executor,
            capabilityRegistry = DeviceCapabilityRegistry(app),
            isFirebaseAiAvailable = { false }
        )

        // 1. Sensitive UI click pauses at ToolConfirmationGate (no API key needed), and respects Cancel
        val payTurn = orchestrator.executeTurn("click Pay", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse(payTurn.isError)
        assertTrue(payTurn.replyText.contains("Confirmation", ignoreCase = true) || executor.getPendingConfirmation() != null)
        assertNotNull(executor.getPendingConfirmation())

        val cancelTurn = orchestrator.executeTurn("cancel", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse(cancelTurn.isError)
        assertNull(executor.getPendingConfirmation())

        // 2. Password field blocked immediately even in local mode
        val pwTurn = orchestrator.executeTurn("type '123456' into Password", null, emptyList(), BrainConfig())
            .toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(passwordFieldSeen)
        assertTrue(pwTurn.isError)
        assertTrue(pwTurn.replyText.contains("password", ignoreCase = true))

        // 3. Offline STT is not falsely claimed when not confirmed by Android
        val voiceController = VoiceController(app)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            assertFalse(voiceController.isOfflineSpeechRecognitionConfirmed())
        }
        assertEquals(0, spyAi.streamCalls.get())
    }
}
