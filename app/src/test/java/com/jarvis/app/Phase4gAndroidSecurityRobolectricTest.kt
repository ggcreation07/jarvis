package com.jarvis.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.data.ai.ByoAiClient
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisDatabase
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.network.JarvisHttpTools
import com.jarvis.app.data.security.SecureSecretStore
import com.jarvis.app.domain.brain.ConfirmationIntent
import com.jarvis.app.domain.brain.ConfirmationTurnOutcome
import com.jarvis.app.domain.brain.DirectActionMatch
import com.jarvis.app.domain.brain.DirectCommandMatcher
import com.jarvis.app.domain.brain.JarvisOrchestrator
import com.jarvis.app.domain.brain.ToolConfirmationPolicy
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.domain.brain.isConfirmationRequired
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.TvControlClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase4gAndroidSecurityRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: JarvisDatabase
    private lateinit var repo: JarvisRepository
    private lateinit var configStore: ConfigStore
    private lateinit var secretStore: SecureSecretStore
    private lateinit var deviceController: DeviceController
    private lateinit var executor: ToolExecutor
    private lateinit var orchestrator: JarvisOrchestrator
    private var testNowMs = 100_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = JarvisRepository(db.jarvisDao())
        configStore = ConfigStore(context)
        secretStore = SecureSecretStore(context)
        val httpTools = JarvisHttpTools()
        val telemetry = DeviceTelemetry(context)
        deviceController = DeviceController(context)
        val firebaseAiService = FirebaseAiService()
        val byoAiClient = ByoAiClient()
        val remotePcClient = RemotePcClient(context)
        val tvControlClient = TvControlClient()

        executor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = httpTools,
            telemetry = telemetry,
            deviceController = deviceController,
            firebaseAiService = firebaseAiService,
            remotePcClient = remotePcClient,
            tvControlClient = tvControlClient,
            nowMs = { testNowMs }
        )

        orchestrator = JarvisOrchestrator(
            repository = repo,
            configStore = configStore,
            secretStore = secretStore,
            firebaseAiService = firebaseAiService,
            byoAiClient = byoAiClient,
            toolExecutor = executor
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `all destructive and high-impact Android actions require confirmation`() = runBlocking {
        // 1. File deletion
        val deleteRes = executor.execute("delete_file", mapOf("path" to JsonPrimitive("confidential.pdf"))) {}
        assertTrue(deleteRes.isConfirmationRequired())
        assertFalse(deleteRes.ok)
        val pending1 = executor.getPendingConfirmation()
        assertNotNull(pending1)
        assertEquals("delete_file", pending1!!.binding.toolName)
        assertEquals("confidential.pdf", pending1.binding.canonicalArgs["path"])

        executor.clearPendingConfirmation()

        // 2. App uninstallation
        val uninstallRes = executor.execute("uninstall_app", mapOf("package" to JsonPrimitive("com.example.malware"))) {}
        assertTrue(uninstallRes.isConfirmationRequired())
        assertFalse(uninstallRes.ok)
        val pending2 = executor.getPendingConfirmation()
        assertNotNull(pending2)
        assertEquals("uninstall_app", pending2!!.binding.toolName)

        executor.clearPendingConfirmation()

        // 3. Open application with uninstall prefix
        val openUninstallRes = executor.execute("open_application", mapOf("name" to JsonPrimitive("uninstall Spotify"))) {}
        assertTrue(openUninstallRes.isConfirmationRequired())
        assertFalse(openUninstallRes.ok)

        executor.clearPendingConfirmation()

        // 4. Send message
        val sendMsgRes = executor.execute("send_message", mapOf(
            "to" to JsonPrimitive("+1234567890"),
            "body" to JsonPrimitive("Bank OTP 998877")
        )) {}
        assertTrue(sendMsgRes.isConfirmationRequired())
        assertFalse(sendMsgRes.ok)

        executor.clearPendingConfirmation()

        // 5. Do Not Disturb
        val dndRes = executor.execute("device_control", mapOf("action" to JsonPrimitive("dnd_on"))) {}
        assertTrue(dndRes.isConfirmationRequired())
        assertFalse(dndRes.ok)

        executor.clearPendingConfirmation()

        // 6. Reset screen / HUD customization
        val resetRes = executor.execute("customize_screen", mapOf("action" to JsonPrimitive("reset"))) {}
        assertTrue(resetRes.isConfirmationRequired())
        assertFalse(resetRes.ok)

        executor.clearPendingConfirmation()

        // 7. Clear schedule
        val clearScheduleRes = executor.execute("manage_schedule", mapOf("action" to JsonPrimitive("clear"))) {}
        assertTrue(clearScheduleRes.isConfirmationRequired())
        assertFalse(clearScheduleRes.ok)

        executor.clearPendingConfirmation()

        // 8. Wipe facts / memory
        val wipeMemoryRes = executor.execute("forget_fact", mapOf("text" to JsonPrimitive("everything"))) {}
        assertTrue(wipeMemoryRes.isConfirmationRequired())
        assertFalse(wipeMemoryRes.ok)

        executor.clearPendingConfirmation()

        // 9. Clear conversation chat
        val clearChatRes = executor.execute("control_interface", mapOf("action" to JsonPrimitive("clear_chat"))) {}
        assertTrue(clearChatRes.isConfirmationRequired())
        assertFalse(clearChatRes.ok)
    }

    @Test
    fun `benign Android actions execute immediately without requiring confirmation`() = runBlocking {
        // 1. Ordinary app opening
        val openApp = executor.execute("open_application", mapOf("name" to JsonPrimitive("Spotify"))) {}
        assertFalse(openApp.isConfirmationRequired())

        // 2. Get time
        val getTime = executor.execute("get_time", emptyMap()) {}
        assertFalse(getTime.isConfirmationRequired())
        assertTrue(getTime.ok)

        // 3. Flashlight toggle
        val flashOn = executor.execute("device_control", mapOf("action" to JsonPrimitive("flashlight_on"))) {}
        assertFalse(flashOn.isConfirmationRequired())

        // 4. Volume adjustment
        val volUp = executor.execute("device_control", mapOf("action" to JsonPrimitive("volume_up"))) {}
        assertFalse(volUp.isConfirmationRequired())

        // 5. Disable DND (turning DND off is not destructive)
        val dndOff = executor.execute("device_control", mapOf("action" to JsonPrimitive("dnd_off"))) {}
        assertFalse(dndOff.isConfirmationRequired())

        // 6. Add agenda item
        val addAgenda = executor.execute("manage_schedule", mapOf(
            "action" to JsonPrimitive("add"),
            "task" to JsonPrimitive("Meeting with team")
        )) {}
        assertFalse(addAgenda.isConfirmationRequired())
        assertTrue(addAgenda.ok)

        // 7. Read file (read-only)
        val readFile = executor.execute("read_file", mapOf("path" to JsonPrimitive("readme.txt"))) {}
        assertFalse(readFile.isConfirmationRequired())

        // 8. List directory (read-only)
        val listDir = executor.execute("list_directory", emptyMap()) {}
        assertFalse(listDir.isConfirmationRequired())

        // 9. Change theme (non-reset)
        val setLook = executor.execute("customize_screen", mapOf(
            "action" to JsonPrimitive("set_look"),
            "value" to JsonPrimitive("ironman")
        )) {}
        assertFalse(setLook.isConfirmationRequired())
        assertTrue(setLook.ok)
    }

    @Test
    fun `direct command matcher intercepts protected Android actions and orchestrator enforces confirmation`() = runBlocking {
        // Direct command for DND on
        val dndMatch = DirectCommandMatcher.match("turn on dnd")
        assertNotNull(dndMatch)
        assertTrue(dndMatch is DirectActionMatch.ToolCall)
        val toolCall = dndMatch as DirectActionMatch.ToolCall
        assertEquals("device_control", toolCall.toolName)
        assertEquals("dnd_on", toolCall.args["action"])

        // Orchestrator executes turn
        val events = orchestrator.executeTurn(
            rawUserInput = "turn on dnd",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig()
        ).toList()

        val pending = executor.getPendingConfirmation()
        assertNotNull("Direct command must not bypass confirmation gate", pending)
        assertEquals("device_control", pending!!.binding.toolName)
        assertEquals("dnd_on", pending.binding.canonicalArgs["action"])

        val finishedEvent = events.filterIsInstance<TurnProgressEvent.Finished>().firstOrNull()
        assertNotNull(finishedEvent)
        assertTrue(finishedEvent!!.replyText.contains("Confirmation required"))
    }

    @Test
    fun `direct command matcher handles reset hud uninstall delete file and send message`() = runBlocking {
        // Reset hud
        val resetMatch = DirectCommandMatcher.match("reset hud")
        assertNotNull(resetMatch)
        assertTrue(resetMatch is DirectActionMatch.ToolCall)
        assertEquals("customize_screen", (resetMatch as DirectActionMatch.ToolCall).toolName)
        assertEquals("reset", resetMatch.args["action"])

        // Uninstall
        val uninstallMatch = DirectCommandMatcher.match("uninstall app TestApp")
        assertNotNull(uninstallMatch)
        assertTrue(uninstallMatch is DirectActionMatch.ToolCall)
        assertEquals("uninstall_app", (uninstallMatch as DirectActionMatch.ToolCall).toolName)
        assertEquals("TestApp", uninstallMatch.args["package"])

        // Delete file
        val deleteMatch = DirectCommandMatcher.match("delete file data.csv")
        assertNotNull(deleteMatch)
        assertTrue(deleteMatch is DirectActionMatch.ToolCall)
        assertEquals("delete_file", (deleteMatch as DirectActionMatch.ToolCall).toolName)
        assertEquals("data.csv", deleteMatch.args["path"])

        // Send message
        val sendMatch = DirectCommandMatcher.match("send message to John saying See you at 5")
        assertNotNull(sendMatch)
        assertTrue(sendMatch is DirectActionMatch.ToolCall)
        assertEquals("send_message", (sendMatch as DirectActionMatch.ToolCall).toolName)
        assertEquals("John", sendMatch.args["to"])
        assertEquals("See you at 5", sendMatch.args["body"])
    }

    @Test
    fun `affirmation authorizes exact action and single use grant cannot be duplicated or reused`() = runBlocking {
        // Request protected action: delete_file
        val req = executor.execute("delete_file", mapOf("path" to JsonPrimitive("cache.tmp"))) {}
        assertTrue(req.isConfirmationRequired())
        val pending = executor.getPendingConfirmation()
        assertNotNull(pending)

        // Affirm in natural Hindi echo
        val outcome = executor.handleConfirmationInput("haan file delete kar do") {}
        assertTrue(outcome is ConfirmationTurnOutcome.Executed)
        val executed = outcome as ConfirmationTurnOutcome.Executed
        assertEquals("delete_file", executed.pending.binding.toolName)

        // Pending confirmation is consumed and cleared
        assertNull(executor.getPendingConfirmation())

        // Re-attempting to execute without a new confirmation request fails-closed
        val duplicateAttempt = executor.execute("delete_file", mapOf("path" to JsonPrimitive("cache.tmp"))) {}
        assertTrue("Duplicate execution without re-confirmation must be blocked", duplicateAttempt.isConfirmationRequired())
    }

    @Test
    fun `argument mismatch and timeout prevent unauthorized execution`() = runBlocking {
        // 1. Request send_message to Alice
        executor.execute("send_message", mapOf(
            "to" to JsonPrimitive("Alice"),
            "body" to JsonPrimitive("Meeting at 3")
        )) {}
        assertNotNull(executor.getPendingConfirmation())

        // User speaks an unrelated command ("what is the weather")
        val unrelatedOutcome = executor.handleConfirmationInput("what is the weather in Delhi") {}
        assertEquals(ConfirmationTurnOutcome.None, unrelatedOutcome)
        // Pending confirmation is cleared when user changes context
        assertNull(executor.getPendingConfirmation())

        // 2. Request uninstall_app and allow to expire (45s timeout)
        executor.execute("uninstall_app", mapOf("package" to JsonPrimitive("com.unwanted.app"))) {}
        assertNotNull(executor.getPendingConfirmation())

        testNowMs += ToolConfirmationPolicy.DEFAULT_CONFIRMATION_TIMEOUT_MS + 5_000L

        // Attempt to confirm after timeout
        val expiredOutcome = executor.handleConfirmationInput("yes confirm") {}
        assertTrue(expiredOutcome is ConfirmationTurnOutcome.Expired)
        assertNull(executor.getPendingConfirmation())

        // 3. Cancellation test in Marathi
        executor.execute("customize_screen", mapOf("action" to JsonPrimitive("reset"))) {}
        assertNotNull(executor.getPendingConfirmation())

        val cancelOutcome = executor.handleConfirmationInput("नाही, रद्द करा") {}
        assertTrue(cancelOutcome is ConfirmationTurnOutcome.Cancelled)
        assertNull(executor.getPendingConfirmation())
    }
}
