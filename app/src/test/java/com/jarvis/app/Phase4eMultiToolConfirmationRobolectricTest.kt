package com.jarvis.app

import android.content.Context
import android.graphics.Bitmap
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
import com.jarvis.app.domain.brain.ConfirmationTurnOutcome
import com.jarvis.app.domain.brain.JarvisOrchestrator
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.domain.brain.isConfirmationRequired
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.RoutineAlarms
import com.jarvis.app.platform.TvControlClient
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
class Phase4eMultiToolConfirmationRobolectricTest {

    private class FakeMultiToolFirebaseAiService(
        private val scriptedRounds: ArrayDeque<Pair<List<FunctionCallPart>, String>>
    ) : FirebaseAiService() {
        val observedToolResults = mutableListOf<ToolCallExecutionResult>()
        var callCount = 0

        override fun streamWithTools(
            modelName: String,
            systemPrompt: String,
            history: List<Pair<String, String>>,
            userMessage: String,
            attachedImage: Bitmap?,
            pcPaired: Boolean,
            tvPaired: Boolean,
            executeTool: suspend (FunctionCallPart) -> ToolCallExecutionResult
        ): Flow<AiStreamEvent> = flow {
            callCount += 1
            val (calls, replyText) = scriptedRounds.removeFirst()
            for (call in calls) {
                emit(AiStreamEvent.ToolStarted(call.name, call.name))
                val res = executeTool(call)
                observedToolResults.add(res)
                emit(AiStreamEvent.ToolFinished(call.name, res.ok, res.summary))
            }
            emit(AiStreamEvent.Completed(replyText))
        }
    }

    private data class Harness(
        val db: JarvisDatabase,
        val repo: JarvisRepository,
        val executor: ToolExecutor,
        val fakeAi: FakeMultiToolFirebaseAiService,
        val orchestrator: JarvisOrchestrator
    )

    private fun createHarness(
        scriptedRounds: List<Pair<List<FunctionCallPart>, String>>
    ): Harness {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = JarvisRepository(db.jarvisDao())
        val configStore = ConfigStore(context)
        val secretStore = SecureSecretStore(context)
        val fakeAi = FakeMultiToolFirebaseAiService(ArrayDeque(scriptedRounds))
        val executor = ToolExecutor(
            repository = repo,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(context),
            deviceController = DeviceController(context),
            firebaseAiService = fakeAi,
            remotePcClient = RemotePcClient(context),
            tvControlClient = TvControlClient()
        )
        val orchestrator = JarvisOrchestrator(
            repository = repo,
            configStore = configStore,
            secretStore = secretStore,
            firebaseAiService = fakeAi,
            byoAiClient = ByoAiClient(),
            toolExecutor = executor
        )
        return Harness(db, repo, executor, fakeAi, orchestrator)
    }

    @Test
    fun `protected action followed by benign tool in same AI round preserves pending confirmation until user confirms`() = runBlocking {
        val today = RoutineAlarms.isoDate(System.currentTimeMillis())
        val h = createHarness(
            listOf(
                listOf(
                    FunctionCallPart("manage_schedule", JsonObject(mapOf("action" to JsonPrimitive("clear")))),
                    FunctionCallPart("get_time", JsonObject(emptyMap())),
                    FunctionCallPart("manage_schedule", JsonObject(mapOf("action" to JsonPrimitive("get"))))
                ) to "Today's time is ready. Please confirm if you want me to clear your schedule."
            )
        )
        try {
            h.repo.addAgendaItem(today, "10:00", "Architecture review")

            val turn1Events = mutableListOf<TurnProgressEvent>()
            h.orchestrator.executeTurn(
                rawUserInput = "clear my schedule and tell me the time",
                attachedBitmap = null,
                history = emptyList(),
                brainConfig = BrainConfig(),
                isVoiceTurn = true
            ).collect { turn1Events.add(it) }

            // Protected action must NOT execute during round 1; benign tools must execute cleanly.
            assertEquals(1, h.repo.getAgendaOnce(today).size)
            assertEquals(3, h.fakeAi.observedToolResults.size)
            assertTrue(h.fakeAi.observedToolResults[0].isConfirmationRequired())
            assertTrue(h.fakeAi.observedToolResults[1].ok)
            assertTrue(h.fakeAi.observedToolResults[2].ok)

            // Pending confirmation must survive the subsequent benign tools in the same round.
            val pending = h.executor.getPendingConfirmation()
            assertNotNull("Pending confirmation must survive benign tools in the same AI turn", pending)
            assertEquals("manage_schedule", pending?.binding?.toolName)
            assertEquals("clear", pending?.binding?.canonicalArgs?.get("action"))

            val finished1 = turn1Events.filterIsInstance<TurnProgressEvent.Finished>().last()
            assertEquals("awaiting_confirmation", finished1.activities[0].status)
            assertEquals("succeeded", finished1.activities[1].status)
            assertEquals("succeeded", finished1.activities[2].status)

            // User's next confirmation response executes ONLY the preserved pending action once.
            val turn2Events = mutableListOf<TurnProgressEvent>()
            h.orchestrator.executeTurn(
                rawUserInput = "haan kar do",
                attachedBitmap = null,
                history = emptyList(),
                brainConfig = BrainConfig(),
                isVoiceTurn = true
            ).collect { turn2Events.add(it) }

            assertNull(h.executor.getPendingConfirmation())
            assertTrue("Schedule should now be cleared after user confirmation", h.repo.getAgendaOnce(today).isEmpty())
            val finished2 = turn2Events.filterIsInstance<TurnProgressEvent.Finished>().last()
            assertFalse(finished2.isError)
            assertEquals("succeeded", finished2.activities.single().status)
            assertEquals(1, h.fakeAi.callCount)
        } finally {
            h.db.close()
        }
    }

    @Test
    fun `unrelated user request still clears pending confirmation saved after benign tools`() = runBlocking {
        val h = createHarness(
            listOf(
                listOf(
                    FunctionCallPart("forget_fact", JsonObject(mapOf("text" to JsonPrimitive("everything")))),
                    FunctionCallPart("get_day", JsonObject(emptyMap()))
                ) to "Confirm if you want me to delete all facts."
            )
        )
        try {
            h.repo.addFact("User lives in Mumbai")

            h.orchestrator.executeTurn(
                rawUserInput = "forget everything and tell me the date",
                attachedBitmap = null,
                history = emptyList(),
                brainConfig = BrainConfig(),
                isVoiceTurn = false
            ).collect {}

            assertNotNull(h.executor.getPendingConfirmation())
            assertEquals(1, h.repo.getFactsOnce().size)

            // User starts an unrelated request instead of confirming -> clears pending confirmation.
            val unrelatedEvents = mutableListOf<TurnProgressEvent>()
            h.orchestrator.executeTurn(
                rawUserInput = "what is the current time?",
                attachedBitmap = null,
                history = emptyList(),
                brainConfig = BrainConfig(),
                isVoiceTurn = false
            ).collect { unrelatedEvents.add(it) }

            assertNull("Unrelated user request must clear pending confirmation", h.executor.getPendingConfirmation())
            assertEquals(1, h.repo.getFactsOnce().size)
            assertTrue(unrelatedEvents.filterIsInstance<TurnProgressEvent.Finished>().last().replyText.startsWith("It's "))

            // Late confirmation cannot execute the cleared action.
            assertTrue(h.executor.handleConfirmationInput("yes") {} is ConfirmationTurnOutcome.None)
            assertEquals(1, h.repo.getFactsOnce().size)
        } finally {
            h.db.close()
        }
    }

    @Test
    fun `benign tools cannot authorize or modify pending confirmation and multiple protected calls cannot cross-authorize`() = runBlocking {
        val today = RoutineAlarms.isoDate(System.currentTimeMillis())
        val h = createHarness(
            listOf(
                // Round with two protected actions + a benign tool in between + duplicate protected call
                listOf(
                    FunctionCallPart("forget_fact", JsonObject(mapOf("text" to JsonPrimitive("everything")))),
                    FunctionCallPart("get_time", JsonObject(emptyMap())),
                    FunctionCallPart("manage_schedule", JsonObject(mapOf("action" to JsonPrimitive("clear")))),
                    FunctionCallPart("manage_schedule", JsonObject(mapOf("action" to JsonPrimitive("clear")))),
                    FunctionCallPart("get_day", JsonObject(emptyMap()))
                ) to "Please confirm clearing your schedule."
            )
        )
        try {
            h.repo.addFact("User drinks black coffee")
            h.repo.addFact("User uses Kotlin")
            h.repo.addAgendaItem(today, "14:00", "Sprint sync")

            // Direct benign tool execution before any pending confirmation creates no grant or pending state
            val initialBenign = h.executor.execute("get_time", emptyMap()) {}
            assertTrue(initialBenign.ok)
            assertNull(h.executor.getPendingConfirmation())

            h.orchestrator.executeTurn(
                rawUserInput = "wipe memory and clear my schedule",
                attachedBitmap = null,
                history = emptyList(),
                brainConfig = BrainConfig(),
                isVoiceTurn = true
            ).collect {}

            // Neither protected action executed in the round; duplicate calls never execute twice.
            assertEquals(2, h.repo.getFactsOnce().size)
            assertEquals(1, h.repo.getAgendaOnce(today).size)

            // Only the bound pending action (manage_schedule clear) is queued; get_day did not wipe it.
            val pending = h.executor.getPendingConfirmation()
            assertNotNull(pending)
            assertEquals("manage_schedule", pending?.binding?.toolName)

            // Confirming executes ONLY manage_schedule("clear") once and NEVER cross-authorizes forget_fact.
            val outcome = h.executor.handleConfirmationInput("yes") {}
            assertTrue(outcome is ConfirmationTurnOutcome.Executed)
            assertEquals("manage_schedule", (outcome as ConfirmationTurnOutcome.Executed).pending.binding.toolName)
            assertTrue(outcome.result.ok)
            assertTrue(h.repo.getAgendaOnce(today).isEmpty())
            assertEquals("forget_fact must remain blocked and untouched", 2, h.repo.getFactsOnce().size)

            // Calling duplicate protected actions or confirming again cannot execute anything twice.
            assertTrue(h.executor.handleConfirmationInput("yes") {} is ConfirmationTurnOutcome.None)
            val repeatProtected = h.executor.execute(
                "manage_schedule",
                mapOf("action" to JsonPrimitive("clear"))
            ) {}
            assertTrue(repeatProtected.isConfirmationRequired())

            // Even concurrent execution of benign + duplicate protected tools cannot reuse an active grant.
            val concurrentBenign = async { h.executor.execute("get_time", emptyMap()) {} }
            val concurrentProtected = async {
                h.executor.execute("forget_fact", mapOf("text" to JsonPrimitive("everything"))) {}
            }
            assertTrue(concurrentBenign.await().ok)
            assertTrue(concurrentProtected.await().isConfirmationRequired())
            assertEquals(2, h.repo.getFactsOnce().size)
        } finally {
            h.db.close()
        }
    }
}
