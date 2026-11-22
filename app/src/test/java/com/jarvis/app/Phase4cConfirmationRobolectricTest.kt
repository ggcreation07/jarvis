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
import com.jarvis.app.domain.brain.ConfirmationClassifier
import com.jarvis.app.domain.brain.ConfirmationIntent
import com.jarvis.app.domain.brain.ConfirmationTurnOutcome
import com.jarvis.app.domain.brain.JarvisOrchestrator
import com.jarvis.app.domain.brain.ToolConfirmationPolicy
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.domain.brain.ToolSideEffects
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.domain.brain.isConfirmationRequired
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.domain.model.RoutineItem
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.RoutineAlarms
import com.jarvis.app.platform.TvControlClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase4cConfirmationRobolectricTest {

    private data class TestHarness(
        val db: JarvisDatabase,
        val repo: JarvisRepository,
        val executor: ToolExecutor,
        val orchestrator: JarvisOrchestrator,
        var nowMs: Long
    )

    private fun createHarness(initialTimeMs: Long = 100_000L): TestHarness {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = JarvisRepository(db.jarvisDao())
        val configStore = ConfigStore(context)
        val secretStore = SecureSecretStore(context)
        val httpTools = JarvisHttpTools()
        val telemetry = DeviceTelemetry(context)
        val deviceController = DeviceController(context)
        val firebaseAiService = FirebaseAiService()
        val byoAiClient = ByoAiClient()
        val remotePcClient = RemotePcClient(context)
        val tvControlClient = TvControlClient()

        lateinit var harness: TestHarness
        val executor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = httpTools,
            telemetry = telemetry,
            deviceController = deviceController,
            firebaseAiService = firebaseAiService,
            remotePcClient = remotePcClient,
            tvControlClient = tvControlClient,
            nowMs = { harness.nowMs }
        )
        val orchestrator = JarvisOrchestrator(
            repository = repo,
            configStore = configStore,
            secretStore = secretStore,
            firebaseAiService = firebaseAiService,
            byoAiClient = byoAiClient,
            toolExecutor = executor
        )
        harness = TestHarness(db, repo, executor, orchestrator, initialTimeMs)
        return harness
    }

    @Test
    fun `confirmation classifier handles hindi marathi hinglish english ambiguous and unrelated replies`() {
        listOf(
            "yes", "confirm", "yes, go ahead", "do it", "yes please",
            "हाँ", "हां", "हाँ कर दो", "जी हाँ", "कर दीजिए",
            "हो", "होय", "हो करा", "नक्की करा",
            "haan", "haan kar do", "haan ji", "kar do", "ho kara"
        ).forEach { phrase ->
            assertEquals("Expected AFFIRM for '$phrase'", ConfirmationIntent.AFFIRM, ConfirmationClassifier.classify(phrase))
        }

        listOf(
            "no", "cancel", "abort", "stop", "don't",
            "नहीं", "रद्द करो", "रहने दो", "मत करो",
            "नाही", "नको", "रद्द करा", "सोडून द्या",
            "nahi", "mat karo", "cancel kar", "rehne do", "nako"
        ).forEach { phrase ->
            assertEquals("Expected CANCEL for '$phrase'", ConfirmationIntent.CANCEL, ConfirmationClassifier.classify(phrase))
        }

        listOf(
            "maybe", "not sure", "hmm", "wait", "ok", "okay", "yes, but wait",
            "yes maybe", "yes check the weather", "शायद", "रुको", "थांब", "हो कदाचित", "haan shayad"
        ).forEach { phrase ->
            assertEquals("Expected AMBIGUOUS for '$phrase'", ConfirmationIntent.AMBIGUOUS, ConfirmationClassifier.classify(phrase))
        }

        listOf(
            "what is the current time?",
            "turn on the flashlight",
            "clear chat",
            "आज हवामान कसं आहे"
        ).forEach { phrase ->
            assertEquals("Expected UNRELATED for '$phrase'", ConfirmationIntent.UNRELATED, ConfirmationClassifier.classify(phrase))
        }
    }

    @Test
    fun `protected actions are blocked on initial execution while non-destructive tools run normally`() = runBlocking {
        val h = createHarness()
        try {
            val today = RoutineAlarms.isoDate(System.currentTimeMillis())
            h.repo.addFact("User lives in Pune")
            h.repo.addFact("User prefers dark mode")
            h.repo.addAgendaItem(today, "10:00", "Design review")

            val sideEffects = mutableListOf<ToolSideEffects>()

            // 1. forget_fact("everything") and forget_fact("all") are blocked
            val forgetAll1 = h.executor.execute(
                "forget_fact",
                mapOf("text" to JsonPrimitive("everything"))
            ) { sideEffects.add(it) }
            assertFalse(forgetAll1.ok)
            assertTrue(forgetAll1.isConfirmationRequired())
            assertEquals(2, h.repo.getFactsOnce().size)

            val forgetAll2 = h.executor.execute(
                "forget_fact",
                mapOf("text" to JsonPrimitive("all"))
            ) { sideEffects.add(it) }
            assertFalse(forgetAll2.ok)
            assertTrue(forgetAll2.isConfirmationRequired())
            assertEquals(2, h.repo.getFactsOnce().size)

            // 2. manage_schedule(action="clear") is blocked
            val clearSchedule = h.executor.execute(
                "manage_schedule",
                mapOf("action" to JsonPrimitive("clear"))
            ) { sideEffects.add(it) }
            assertFalse(clearSchedule.ok)
            assertTrue(clearSchedule.isConfirmationRequired())
            assertEquals(1, h.repo.getAgendaOnce(today).size)

            // 3. control_interface(action="clear_chat") is blocked
            val clearChat = h.executor.execute(
                "control_interface",
                mapOf("action" to JsonPrimitive("clear_chat"))
            ) { sideEffects.add(it) }
            assertFalse(clearChat.ok)
            assertTrue(clearChat.isConfirmationRequired())
            assertTrue(sideEffects.none { it.clearChat })

            // 4. High-impact pc_task is blocked; policy differentiates destructive vs benign
            val pcWipe = h.executor.execute(
                "pc_task",
                mapOf("goal" to JsonPrimitive("delete C:\\temp\\notes.txt"), "kind" to JsonPrimitive("computer"))
            ) { sideEffects.add(it) }
            assertFalse(pcWipe.ok)
            assertTrue(pcWipe.isConfirmationRequired())

            assertTrue(ToolConfirmationPolicy.isHighImpactPcTask("shutdown the pc", "computer"))
            assertTrue(ToolConfirmationPolicy.isHighImpactPcTask("saare temp files delete kar", "computer"))
            assertTrue(ToolConfirmationPolicy.isHighImpactPcTask("run powershell format D:", "computer"))
            assertFalse(ToolConfirmationPolicy.isHighImpactPcTask("search kotlin coroutines docs", "browser"))

            // 5. Non-destructive tools execute normally without confirmation
            val openSettings = h.executor.execute(
                "control_interface",
                mapOf("action" to JsonPrimitive("open_settings"))
            ) { sideEffects.add(it) }
            assertTrue(openSettings.ok)
            assertEquals(OverlayType.SETTINGS, sideEffects.last().openOverlay)

            val forgetOne = h.executor.execute(
                "forget_fact",
                mapOf("text" to JsonPrimitive("dark mode"))
            ) { sideEffects.add(it) }
            assertTrue(forgetOne.ok)
            assertEquals(listOf("User lives in Pune"), h.repo.getFactsOnce().map { it.text })

            val addAgenda = h.executor.execute(
                "manage_schedule",
                mapOf("action" to JsonPrimitive("add"), "time" to JsonPrimitive("16:00"), "task" to JsonPrimitive("Call Alex"))
            ) { sideEffects.add(it) }
            assertTrue(addAgenda.ok)
            assertEquals(2, h.repo.getAgendaOnce(today).size)
        } finally {
            h.db.close()
        }
    }

    @Test
    fun `confirmation binds strictly to pending action and original args and blocks retries or double execution`() = runBlocking {
        val h = createHarness()
        try {
            val today = RoutineAlarms.isoDate(System.currentTimeMillis())
            h.repo.addFact("User lives in Pune")
            h.repo.addFact("User likes chai")
            h.repo.addAgendaItem(today, "09:00", "Standup")

            // Step 1: Request forget_fact("everything") -> blocked
            val firstReq = h.executor.execute("forget_fact", mapOf("text" to JsonPrimitive("everything"))) {}
            assertTrue(firstReq.isConfirmationRequired())

            // Duplicate / retry call must NOT bypass confirmation
            val retryReq = h.executor.execute("forget_fact", mapOf("text" to JsonPrimitive("everything"))) {}
            assertFalse(retryReq.ok)
            assertTrue(retryReq.isConfirmationRequired())
            assertEquals(2, h.repo.getFactsOnce().size)

            // Step 2: New protected action replaces previous pending confirmation
            val replaceReq = h.executor.execute("manage_schedule", mapOf("action" to JsonPrimitive("clear"))) {}
            assertTrue(replaceReq.isConfirmationRequired())
            assertEquals("manage_schedule", h.executor.getPendingConfirmation()?.binding?.toolName)

            // Confirming now ("हाँ कर दो") executes ONLY manage_schedule("clear"), NOT forget_fact("everything")
            val outcome1 = h.executor.handleConfirmationInput("हाँ कर दो") {}
            assertTrue(outcome1 is ConfirmationTurnOutcome.Executed)
            assertEquals("manage_schedule", (outcome1 as ConfirmationTurnOutcome.Executed).pending.binding.toolName)
            assertTrue(outcome1.result.ok)
            assertTrue(h.repo.getAgendaOnce(today).isEmpty())
            assertEquals("Facts must remain untouched when schedule clear was confirmed", 2, h.repo.getFactsOnce().size)

            // Step 3: Repeating confirmation or calling forget_fact again cannot reuse grant
            assertNull(h.executor.getPendingConfirmation())
            val repeatConfirm = h.executor.handleConfirmationInput("yes") {}
            assertTrue(repeatConfirm is ConfirmationTurnOutcome.None)
            val unconfirmedForget = h.executor.execute("forget_fact", mapOf("text" to JsonPrimitive("everything"))) {}
            assertTrue(unconfirmedForget.isConfirmationRequired())
            assertEquals(2, h.repo.getFactsOnce().size)

            // Confirm forget_fact("everything") in Marathi ("हो करा") -> executes once
            val outcome2 = h.executor.handleConfirmationInput("हो करा") {}
            assertTrue(outcome2 is ConfirmationTurnOutcome.Executed)
            assertTrue((outcome2 as ConfirmationTurnOutcome.Executed).result.ok)
            assertTrue(h.repo.getFactsOnce().isEmpty())

            // Calling forget_fact("everything") again right after must require a brand new confirmation
            val secondCallAfterExec = h.executor.execute("forget_fact", mapOf("text" to JsonPrimitive("everything"))) {}
            assertTrue(secondCallAfterExec.isConfirmationRequired())
        } finally {
            h.db.close()
        }
    }

    @Test
    fun `cancellation timeout expiry and ambiguous replies prevent execution`() = runBlocking {
        val h = createHarness(initialTimeMs = 10_000L)
        try {
            val today = RoutineAlarms.isoDate(System.currentTimeMillis())
            h.repo.addAgendaItem(today, "11:00", "Important meeting")
            h.repo.addFact("User birthday is May 10")

            // 1. Ambiguous reply keeps pending state without executing
            h.executor.execute("manage_schedule", mapOf("action" to JsonPrimitive("clear"))) {}
            val amb = h.executor.handleConfirmationInput("yes, but wait") {}
            assertTrue(amb is ConfirmationTurnOutcome.NeedsClarification)
            assertNotNull(h.executor.getPendingConfirmation())
            assertEquals(1, h.repo.getAgendaOnce(today).size)

            // 2. Cancellation clears pending state permanently
            val cancelled = h.executor.handleConfirmationInput("नको") {}
            assertTrue(cancelled is ConfirmationTurnOutcome.Cancelled)
            assertNull(h.executor.getPendingConfirmation())
            assertEquals(1, h.repo.getAgendaOnce(today).size)
            assertTrue(h.executor.handleConfirmationInput("yes") {} is ConfirmationTurnOutcome.None)
            assertEquals(1, h.repo.getAgendaOnce(today).size)

            // 3. Timeout expiry clears pending confirmation and blocks late confirmation
            h.executor.execute("forget_fact", mapOf("text" to JsonPrimitive("everything"))) {}
            assertNotNull(h.executor.getPendingConfirmation())
            h.nowMs += ToolConfirmationPolicy.DEFAULT_CONFIRMATION_TIMEOUT_MS + 1_000L
            assertNull(h.executor.getPendingConfirmation())

            val expiredOutcome = h.executor.handleConfirmationInput("yes") {}
            assertTrue(expiredOutcome is ConfirmationTurnOutcome.Expired)
            assertEquals(1, h.repo.getFactsOnce().size)
        } finally {
            h.db.close()
        }
    }

    @Test
    fun `orchestrator clear_chat unrelated turn preemption routine isolation and failed pc task truth`() = runBlocking {
        val h = createHarness()
        try {
            // 1. DirectCommandMatcher "clear chat" must NOT clear chat on initial turn
            val events1 = mutableListOf<TurnProgressEvent>()
            val sideEffects1 = mutableListOf<ToolSideEffects>()
            h.orchestrator.executeTurn(
                rawUserInput = "clear chat",
                attachedBitmap = null,
                history = emptyList(),
                brainConfig = BrainConfig(),
                isVoiceTurn = true
            ).collect { ev ->
                events1.add(ev)
                if (ev is TurnProgressEvent.SideEffect) sideEffects1.add(ev.effect)
            }
            assertTrue(sideEffects1.none { it.clearChat })
            val finished1 = events1.filterIsInstance<TurnProgressEvent.Finished>().last()
            assertFalse("Asking confirmation is not an error turn", finished1.isError)
            assertEquals("awaiting_confirmation", finished1.activities.first().status)

            // 2. Starting an unrelated turn clears pending confirmation cleanly
            val eventsUnrelated = mutableListOf<TurnProgressEvent>()
            h.orchestrator.executeTurn(
                rawUserInput = "what is the current time?",
                attachedBitmap = null,
                history = emptyList(),
                brainConfig = BrainConfig(),
                isVoiceTurn = true
            ).collect { eventsUnrelated.add(it) }
            assertNull("Unrelated turn must clear prior pending confirmation", h.executor.getPendingConfirmation())
            val finishedTime = eventsUnrelated.filterIsInstance<TurnProgressEvent.Finished>().last()
            assertFalse(finishedTime.isError)
            assertTrue(finishedTime.replyText.startsWith("It's "))

            // 3. Request clear chat again, then confirm in Hinglish ("haan kar do")
            h.orchestrator.executeTurn("clear chat", null, emptyList(), BrainConfig(), isVoiceTurn = true).collect {}
            assertNotNull(h.executor.getPendingConfirmation())

            val eventsConfirm = mutableListOf<TurnProgressEvent>()
            val sideEffectsConfirm = mutableListOf<ToolSideEffects>()
            h.orchestrator.executeTurn("haan kar do", null, emptyList(), BrainConfig(), isVoiceTurn = true).collect { ev ->
                eventsConfirm.add(ev)
                if (ev is TurnProgressEvent.SideEffect) sideEffectsConfirm.add(ev.effect)
            }
            assertTrue("Confirmed clear_chat must emit clearChat side effect once", sideEffectsConfirm.count { it.clearChat } == 1)
            val finishedConfirm = eventsConfirm.filterIsInstance<TurnProgressEvent.Finished>().last()
            assertFalse(finishedConfirm.isError)
            assertEquals("succeeded", finishedConfirm.activities.first().status)

            // 4. Routine running a destructive step cannot bypass ToolExecutor confirmation
            h.repo.upsertRoutine(RoutineItem(name = "Cleanup", prompt = "clear chat"))
            val routineRes = h.executor.execute(
                "manage_routine",
                mapOf("action" to JsonPrimitive("run"), "name" to JsonPrimitive("Cleanup"))
            ) {}
            assertTrue(routineRes.ok)
            h.orchestrator.executeTurn("clear chat", null, emptyList(), BrainConfig(), isVoiceTurn = true).collect {}
            assertNotNull("Routine step must still stop for explicit user confirmation", h.executor.getPendingConfirmation())
            h.executor.clearPendingConfirmation()

            // 5. Failed protected tool execution after confirmation must NOT report ok/success
            h.executor.execute(
                "pc_task",
                mapOf("goal" to JsonPrimitive("shutdown the pc"), "kind" to JsonPrimitive("computer"))
            ) {}
            val pcFailEvents = mutableListOf<TurnProgressEvent>()
            h.orchestrator.executeTurn("confirm", null, emptyList(), BrainConfig(), isVoiceTurn = false).collect {
                pcFailEvents.add(it)
            }
            val pcFinished = pcFailEvents.filterIsInstance<TurnProgressEvent.Finished>().last()
            assertTrue("Unreachable PC task failure must surface as failed/error rather than claiming success", pcFinished.isError)
            assertEquals("failed", pcFinished.activities.first().status)
        } finally {
            h.db.close()
        }
    }
}
