package com.jarvis.app

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.domain.brain.ToolSideEffects
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.platform.JarvisAssistantRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase4fTurnCancellationRobolectricTest {

    private fun pumpUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.runUiThreadTasks()
            if (condition()) {
                ShadowLooper.runUiThreadTasks()
                return
            }
            Thread.sleep(10L)
        }
    }

    @Test
    fun `turn cancellation race fix ensures newer turn B owns busy state and streaming message while old turn A finishes or cancels`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = JarvisAssistantRuntime.getInstance(app)
        runtime.stopAssistant()
        ShadowLooper.runUiThreadTasks()

        val turnAHanging = CompletableDeferred<Unit>()
        val turnAResumed = CompletableDeferred<Unit>()
        val turnBHanging = CompletableDeferred<Unit>()
        val turnBResumed = CompletableDeferred<Unit>()

        runtime.turnExecutorOverride = { prompt, _, _, _, _ ->
            flow {
                if (prompt == "Turn A") {
                    emit(TurnProgressEvent.StreamingText("A thinking..."))
                    turnAHanging.complete(Unit)
                    turnAResumed.await()
                    emit(TurnProgressEvent.Finished("Result A", "assistant", emptyList(), false))
                } else if (prompt == "Turn B") {
                    emit(TurnProgressEvent.StreamingText("B streaming..."))
                    turnBHanging.complete(Unit)
                    turnBResumed.await()
                    emit(TurnProgressEvent.Finished("Result B", "assistant", emptyList(), false))
                }
            }
        }

        try {
            // 1. Start turn A and suspend it.
            runtime.sendUserMessage("Turn A", openChatIfHome = false, initiatedByVoice = false)
            pumpUntil { turnAHanging.isCompleted }

            assertTrue("Turn A should have reached suspension point", turnAHanging.isCompleted)
            assertTrue("Turn A must set busy state to true", runtime.isBusy.value)
            assertEquals("A thinking...", runtime.streamingMessage.value?.content)

            // 2. Start turn B before A finishes -> cancels A.
            runtime.sendUserMessage("Turn B", openChatIfHome = false, initiatedByVoice = false)
            pumpUntil { turnBHanging.isCompleted }

            assertTrue("Turn B should have reached suspension point", turnBHanging.isCompleted)
            assertTrue("Turn B must own busy state", runtime.isBusy.value)
            assertEquals("B streaming...", runtime.streamingMessage.value?.content)

            // 3. Cancel/complete A and verify B still owns _isBusy and _streamingMessage.
            turnAResumed.complete(Unit)
            pumpUntil(timeoutMs = 150L) { false }
            ShadowLooper.runUiThreadTasks()

            assertTrue("Turn B must retain busy state even after A resumes and cancels", runtime.isBusy.value)
            assertEquals("B streaming...", runtime.streamingMessage.value?.content)

            // 4. Complete B and verify state resets normally.
            turnBResumed.complete(Unit)
            pumpUntil { !runtime.isBusy.value && runtime.streamingMessage.value == null }

            assertFalse("Busy state must reset after B completes", runtime.isBusy.value)
            assertNull("Streaming message must be null after B completes", runtime.streamingMessage.value)
        } finally {
            runtime.turnExecutorOverride = null
            runtime.stopAssistant()
            ShadowLooper.runUiThreadTasks()
        }
    }

    @Test
    fun `routine chaining and voice follow up work correctly with token ownership`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val runtime = JarvisAssistantRuntime.getInstance(app)
        runtime.stopAssistant()
        ShadowLooper.runUiThreadTasks()

        runtime.activateVoiceOnlyUi(Intent.ACTION_ASSIST, nowMs = 120_000L)
        ShadowLooper.runUiThreadTasks()

        val observedRoutines = mutableListOf<String>()
        runtime.turnExecutorOverride = { prompt, _, _, _, isVoice ->
            flow {
                observedRoutines.add(prompt)
                if (prompt == "Routine Step 1") {
                    emit(
                        TurnProgressEvent.SideEffect(
                            ToolSideEffects(runRoutinePrompt = "Routine Step 2")
                        )
                    )
                    emit(TurnProgressEvent.Finished("Step 1 done", "assistant", emptyList(), false))
                } else if (prompt == "Routine Step 2") {
                    emit(TurnProgressEvent.Finished("Step 2 done", "assistant", emptyList(), false))
                }
            }
        }

        try {
            runtime.sendUserMessage("Routine Step 1", openChatIfHome = false, initiatedByVoice = true)
            pumpUntil { !runtime.isTurnActiveForTest() && !runtime.isBusy.value && runtime.streamingMessage.value == null }

            assertEquals(listOf("Routine Step 1", "Routine Step 2"), observedRoutines)
            assertFalse(runtime.isBusy.value)
            assertNull(runtime.streamingMessage.value)
            assertTrue("Voice session should remain active for follow-up", runtime.isVoiceSessionActive.value)
            assertTrue("Follow up should be permitted after successful voice turn", runtime.isAutoFollowUpAllowedForTest())
        } finally {
            runtime.turnExecutorOverride = null
            runtime.stopAssistant()
            ShadowLooper.runUiThreadTasks()
        }
    }
}
