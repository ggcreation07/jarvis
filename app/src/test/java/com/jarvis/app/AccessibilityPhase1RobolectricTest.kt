package com.jarvis.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.speech.RecognitionService
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisDatabase
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.network.JarvisHttpTools
import com.jarvis.app.domain.brain.AccessibilityNavigationBridge
import com.jarvis.app.domain.brain.DirectActionMatch
import com.jarvis.app.domain.brain.DirectCommandMatcher
import com.jarvis.app.domain.brain.ToolConfirmationPolicy
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.JarvisAccessibilityService
import com.jarvis.app.platform.JarvisAssistantRuntime
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.TvControlClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityPhase1RobolectricTest {

    private lateinit var app: Application
    private lateinit var db: JarvisDatabase
    private lateinit var repo: JarvisRepository
    private lateinit var configStore: ConfigStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        JarvisAccessibilityService.resetConnectionStateForTest()
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")

        db = Room.inMemoryDatabaseBuilder(app, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = JarvisRepository(db.jarvisDao())
        configStore = ConfigStore(app)
    }

    @After
    fun tearDown() {
        JarvisAccessibilityService.resetConnectionStateForTest()
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        db.close()
    }

    private fun buildExecutor(bridge: AccessibilityNavigationBridge? = null): ToolExecutor {
        return ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = FirebaseAiService(),
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient(),
            appContext = app,
            accessibilityBridge = bridge
        )
    }

    private fun expectedComponentFlat(): String =
        ComponentName(app, JarvisAccessibilityService::class.java).flattenToString()

    private fun expectedComponentShort(): String =
        ComponentName(app, JarvisAccessibilityService::class.java).flattenToShortString()

    private fun pumpMainUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) {
                ShadowLooper.idleMainLooper()
                return
            }
            ShadowLooper.idleMainLooper(25L, TimeUnit.MILLISECONDS)
            Thread.sleep(5L)
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `isEnabled requires both ACCESSIBILITY_ENABLED and enabled service component`() {
        assertFalse(JarvisAccessibilityService.isEnabled(app))

        // Component listed, but master ACCESSIBILITY_ENABLED == 0 -> must be false
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Settings.Secure.putString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            expectedComponentFlat()
        )
        assertFalse(JarvisAccessibilityService.isEnabled(app))

        // Master ACCESSIBILITY_ENABLED == 1, but different service listed -> must be false
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.other.pkg/.OtherAccessibilityService"
        )
        assertFalse(JarvisAccessibilityService.isEnabled(app))

        // Both enabled (flattened name inside colon-separated list) -> true
        Settings.Secure.putString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.other.pkg/.OtherService:${expectedComponentFlat()}"
        )
        assertTrue(JarvisAccessibilityService.isEnabled(app))

        // Short component name also recognized
        Settings.Secure.putString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            expectedComponentShort()
        )
        assertTrue(JarvisAccessibilityService.isEnabled(app))
    }

    @Test
    fun `service lifecycle sets connected only on onServiceConnected and clears on unbind and destroy`() {
        assertFalse(JarvisAccessibilityService.isConnected)
        assertFalse(JarvisAccessibilityService.isConnectedNow())
        assertFalse(JarvisAccessibilityService.isConnectedState.value)
        assertNull(JarvisAccessibilityService.instance)

        val controller = Robolectric.buildService(JarvisAccessibilityService::class.java)
        val service = controller.create().get()

        // Creating the service alone must NOT mark it connected
        assertFalse(JarvisAccessibilityService.isConnected)
        assertFalse(JarvisAccessibilityService.isConnectedNow())
        assertNull(JarvisAccessibilityService.instance)

        // Only onServiceConnected() marks it connected
        service.onServiceConnected()
        assertTrue(JarvisAccessibilityService.isConnected)
        assertTrue(JarvisAccessibilityService.isConnectedNow())
        assertTrue(JarvisAccessibilityService.isConnectedState.value)
        assertSame(service, JarvisAccessibilityService.instance)

        // onUnbind() clears instance and connected state
        service.onUnbind(Intent())
        assertFalse(JarvisAccessibilityService.isConnected)
        assertFalse(JarvisAccessibilityService.isConnectedNow())
        assertFalse(JarvisAccessibilityService.isConnectedState.value)
        assertNull(JarvisAccessibilityService.instance)

        // Reconnect then onDestroy() also clears instance and connected state
        service.onServiceConnected()
        assertTrue(JarvisAccessibilityService.isConnectedNow())
        controller.destroy()
        assertFalse(JarvisAccessibilityService.isConnected)
        assertFalse(JarvisAccessibilityService.isConnectedNow())
        assertFalse(JarvisAccessibilityService.isConnectedState.value)
        assertNull(JarvisAccessibilityService.instance)
    }

    @Test
    fun `runtime exposes separate settings-enabled vs connected status and survives disconnection during voice mode`() {
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

        // Settings enabled, but service NOT connected
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            expectedComponentFlat()
        )
        runtime.refreshServiceStatus()
        assertTrue(runtime.accessibilityEnabled.value)
        assertFalse(runtime.accessibilityConnected.value)

        // Start voice mode, connect service, then disconnect while voice mode is active
        assertTrue(runtime.activateVoiceOnlyUi(Intent.ACTION_ASSIST))
        ShadowLooper.idleMainLooper()
        assertTrue(runtime.isVoiceSessionActive.value)

        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        service.onServiceConnected()
        pumpMainUntil { runtime.accessibilityConnected.value }
        assertTrue(runtime.accessibilityConnected.value)

        // Disconnection must clear accessibilityConnected without crashing or stopping voice session
        service.onUnbind(Intent())
        pumpMainUntil { !runtime.accessibilityConnected.value }
        assertFalse(runtime.accessibilityConnected.value)
        assertTrue(runtime.accessibilityEnabled.value)
        assertTrue(runtime.isVoiceSessionActive.value)

        runtime.stopAssistant()
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `tool executor executes all 5 global navigation actions when connected and reports performed actions`() = runBlocking {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        service.onServiceConnected()

        val executor = buildExecutor()
        val cases = listOf(
            "back" to AccessibilityService.GLOBAL_ACTION_BACK,
            "home" to AccessibilityService.GLOBAL_ACTION_HOME,
            "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
            "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        )

        val shadowService = Shadows.shadowOf(service)
        cases.forEach { (action, expectedGlobalAction) ->
            val result = executor.execute("accessibility_navigate", mapOf("action" to JsonPrimitive(action))) {}
            assertTrue("Expected $action to succeed, got: ${result.summary}", result.ok)
            assertEquals("ok", result.payload["status"]?.jsonPrimitive?.content)
            assertTrue(
                "Expected global action $expectedGlobalAction to be recorded",
                shadowService.globalActionsPerformed.contains(expectedGlobalAction)
            )
        }
    }

    @Test
    fun `tool executor fails truthfully when disconnected or enabled-but-disconnected or action fails`() = runBlocking {
        val executor = buildExecutor()

        // 1. Not granted & disconnected -> must fail
        val notGranted = executor.execute("accessibility_navigate", mapOf("action" to JsonPrimitive("back"))) {}
        assertFalse(notGranted.ok)
        assertEquals("failed", notGranted.payload["status"]?.jsonPrimitive?.content)

        // 2. Enabled in Android Settings, but service not connected -> must still fail with reconnect guidance
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            expectedComponentFlat()
        )
        val enabledDisconnected = executor.execute("accessibility_navigate", mapOf("action" to JsonPrimitive("home"))) {}
        assertFalse(enabledDisconnected.ok)
        assertEquals("failed", enabledDisconnected.payload["status"]?.jsonPrimitive?.content)
        assertTrue(enabledDisconnected.summary.contains("disconnected", ignoreCase = true))

        // 3. Connected, but performGlobalAction() returns false -> must fail and never claim success
        val failingBridge = object : AccessibilityNavigationBridge {
            override fun isSettingsEnabled(): Boolean = true
            override fun isServiceConnected(): Boolean = true
            override fun performAction(action: String): Boolean = false
        }
        val failingExecutor = buildExecutor(bridge = failingBridge)
        val actionRejected = failingExecutor.execute(
            "accessibility_navigate",
            mapOf("action" to JsonPrimitive("notifications"))
        ) {}
        assertFalse(actionRejected.ok)
        assertEquals("failed", actionRejected.payload["status"]?.jsonPrimitive?.content)

        // 4. Unsupported action -> must fail
        val unsupported = failingExecutor.execute(
            "accessibility_navigate",
            mapOf("action" to JsonPrimitive("tap_button"))
        ) {}
        assertFalse(unsupported.ok)
        assertEquals("failed", unsupported.payload["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `direct command matcher and firebase tool declarations cover navigation actions without breaking existing tools`() {
        val back = DirectCommandMatcher.match("go back") as? DirectActionMatch.ToolCall
        assertNotNull(back)
        assertEquals("accessibility_navigate", back?.toolName)
        assertEquals("back", back?.args?.get("action"))

        val home = DirectCommandMatcher.match("go home") as? DirectActionMatch.ToolCall
        assertNotNull(home)
        assertEquals("accessibility_navigate", home?.toolName)
        assertEquals("home", home?.args?.get("action"))

        val recents = DirectCommandMatcher.match("open recents") as? DirectActionMatch.ToolCall
        assertNotNull(recents)
        assertEquals("accessibility_navigate", recents?.toolName)
        assertEquals("recents", recents?.args?.get("action"))

        val notifications = DirectCommandMatcher.match("open notifications") as? DirectActionMatch.ToolCall
        assertNotNull(notifications)
        assertEquals("accessibility_navigate", notifications?.toolName)
        assertEquals("notifications", notifications?.args?.get("action"))

        val quickSettings = DirectCommandMatcher.match("open quick settings") as? DirectActionMatch.ToolCall
        assertNotNull(quickSettings)
        assertEquals("accessibility_navigate", quickSettings?.toolName)
        assertEquals("quick_settings", quickSettings?.args?.get("action"))

        // Existing rules must remain intact
        val spotify = DirectCommandMatcher.match("open Spotify") as? DirectActionMatch.ToolCall
        assertEquals("open_application", spotify?.toolName)

        val settings = DirectCommandMatcher.match("open settings") as? DirectActionMatch.ToolCall
        assertEquals("control_interface", settings?.toolName)

        // ToolConfirmationGate remains intact for protected actions
        assertTrue(ToolConfirmationPolicy.requiresConfirmation("delete_file", emptyMap()))
        assertFalse(ToolConfirmationPolicy.requiresConfirmation("accessibility_navigate", emptyMap()))

        // FirebaseAiService tool declarations include accessibility_navigate once without duplicates
        val names = FirebaseAiService().declaredToolNames(pcPaired = true, tvPaired = true)
        assertEquals(1, names.count { it == "accessibility_navigate" })
        assertEquals(names.size, names.distinct().size)
    }
}
