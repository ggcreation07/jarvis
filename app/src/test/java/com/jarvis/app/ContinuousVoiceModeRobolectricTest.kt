package com.jarvis.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.platform.JarvisAssistantRuntime
import com.jarvis.app.platform.VoiceController
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class ContinuousVoiceModeRobolectricTest {

    private fun prepareRuntimeForVoiceTest(app: Application): JarvisAssistantRuntime {
        val runtime = JarvisAssistantRuntime.getInstance(app)
        val targets = listOfNotNull(app, runtime.context.applicationContext as? Application).distinctBy { System.identityHashCode(it) }
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
        runtime.voiceController.resetListenAttemptCountForTest()
        ShadowLooper.idleMainLooper()
        return runtime
    }

    private fun pumpMainUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) {
                ShadowLooper.idleMainLooper()
                return
            }
            ShadowLooper.idleMainLooper(50L, TimeUnit.MILLISECONDS)
            Thread.sleep(10L)
        }
        ShadowLooper.idleMainLooper()
    }

    @Test
    fun `default assistant retries automatically on silence empty results and temporary errors without closing overlay`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = prepareRuntimeForVoiceTest(app)

        val controller = Robolectric.buildActivity(AssistantOverlayActivity::class.java, Intent(Intent.ACTION_ASSIST))
        controller.create().start().resume()
        ShadowLooper.idleMainLooper()
        val activity = controller.get()

        try {
            assertTrue(runtime.isVoiceSessionActive.value)
            assertTrue(runtime.isVoiceOnlyUiActive.value)
            assertFalse(activity.isFinishing)
            assertTrue(runtime.voiceController.getListenAttemptCountForTest() >= 1)

            // 1. ERROR_NO_MATCH -> wait ~500ms (400-700ms) and restart listening cleanly
            val beforeNoMatch = runtime.voiceController.getListenAttemptCountForTest()
            runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_NO_MATCH)
            assertEquals(JarvisAssistantRuntime.RETRY_SILENCE_DELAY_MS, runtime.lastListenRetryDelayMsForTest)
            assertTrue(runtime.lastListenRetryDelayMsForTest in 400L..700L)
            assertTrue("Session must stay active after ERROR_NO_MATCH", runtime.isVoiceSessionActive.value)
            assertTrue("Orb UI must stay active after ERROR_NO_MATCH", runtime.isVoiceOnlyUiActive.value)
            assertFalse("Activity must not finish on ERROR_NO_MATCH", activity.isFinishing)

            ShadowLooper.idleMainLooper(600L, TimeUnit.MILLISECONDS)
            pumpMainUntil { runtime.voiceController.getListenAttemptCountForTest() > beforeNoMatch }
            assertEquals(beforeNoMatch + 1, runtime.voiceController.getListenAttemptCountForTest())

            // 2. ERROR_SPEECH_TIMEOUT -> wait ~500ms and restart listening cleanly
            val beforeTimeout = runtime.voiceController.getListenAttemptCountForTest()
            runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            assertEquals(JarvisAssistantRuntime.RETRY_SILENCE_DELAY_MS, runtime.lastListenRetryDelayMsForTest)
            assertTrue(runtime.isVoiceSessionActive.value)
            assertTrue(runtime.isVoiceOnlyUiActive.value)
            assertFalse(activity.isFinishing)

            ShadowLooper.idleMainLooper(600L, TimeUnit.MILLISECONDS)
            pumpMainUntil { runtime.voiceController.getListenAttemptCountForTest() > beforeTimeout }
            assertEquals(beforeTimeout + 1, runtime.voiceController.getListenAttemptCountForTest())

            // 3. Empty recognition result -> wait ~500ms and restart listening cleanly
            val beforeEmpty = runtime.voiceController.getListenAttemptCountForTest()
            runtime.voiceController.simulateRecognitionResultsForTest(listOf("   "))
            assertEquals(JarvisAssistantRuntime.RETRY_SILENCE_DELAY_MS, runtime.lastListenRetryDelayMsForTest)
            assertTrue(runtime.isVoiceSessionActive.value)
            assertTrue(runtime.isVoiceOnlyUiActive.value)

            ShadowLooper.idleMainLooper(600L, TimeUnit.MILLISECONDS)
            pumpMainUntil { runtime.voiceController.getListenAttemptCountForTest() > beforeEmpty }
            assertEquals(beforeEmpty + 1, runtime.voiceController.getListenAttemptCountForTest())

            // 4. ERROR_RECOGNIZER_BUSY & rapid temporary error backoff stays bounded and safe
            val beforeBusy = runtime.voiceController.getListenAttemptCountForTest()
            runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            val delay1 = runtime.lastListenRetryDelayMsForTest!!
            assertTrue("First busy/temp retry delay must be in 400-700ms, was $delay1", delay1 in 400L..700L)
            ShadowLooper.idleMainLooper(delay1 + 100L, TimeUnit.MILLISECONDS)
            pumpMainUntil { runtime.voiceController.getListenAttemptCountForTest() > beforeBusy }

            val beforeNet = runtime.voiceController.getListenAttemptCountForTest()
            runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_NETWORK)
            val delay2 = runtime.lastListenRetryDelayMsForTest!!
            assertTrue("Second rapid retry delay must be in 400-700ms, was $delay2", delay2 in 400L..700L)
            ShadowLooper.idleMainLooper(delay2 + 100L, TimeUnit.MILLISECONDS)
            pumpMainUntil { runtime.voiceController.getListenAttemptCountForTest() > beforeNet }

            val beforeAudio = runtime.voiceController.getListenAttemptCountForTest()
            runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_AUDIO)
            assertEquals(JarvisAssistantRuntime.RETRY_MAX_FAST_DELAY_MS, runtime.lastListenRetryDelayMsForTest)
            ShadowLooper.idleMainLooper(800L, TimeUnit.MILLISECONDS)
            pumpMainUntil { runtime.voiceController.getListenAttemptCountForTest() > beforeAudio }

            // 4th rapid immediate failure backs off safely (>= 1200ms) instead of spinning
            runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_CLIENT)
            assertTrue((runtime.lastListenRetryDelayMsForTest ?: 0L) >= 1200L)
            assertTrue(runtime.isVoiceSessionActive.value)
            assertFalse(activity.isFinishing)

            // 5. Explicit Stop terminates pending retries and finishes overlay
            val attemptsBeforeStop = runtime.voiceController.getListenAttemptCountForTest()
            activity.onStopAssistantClicked()
            ShadowLooper.idleMainLooper(2000L, TimeUnit.MILLISECONDS)
            assertFalse(runtime.isVoiceSessionActive.value)
            assertFalse(runtime.isVoiceOnlyUiActive.value)
            assertFalse(runtime.isFollowUpScheduledForTest())
            assertTrue(activity.isFinishing)
            assertEquals(attemptsBeforeStop, runtime.voiceController.getListenAttemptCountForTest())
        } finally {
            runtime.stopAssistant()
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `in app voice mode runs continuous listening and only stops on explicit stop or dismiss`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = prepareRuntimeForVoiceTest(app)

        try {
            assertTrue(runtime.startInAppVoiceMode())
            assertTrue(runtime.isVoiceSessionActive.value)
            assertTrue(runtime.isVoiceOnlyUiActive.value)
            val initialAttempts = runtime.voiceController.getListenAttemptCountForTest()
            assertTrue(initialAttempts >= 1)

            // Silence timeout keeps in-app Voice Mode active and restarts listening after delay
            runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            assertTrue(runtime.isVoiceSessionActive.value)
            assertTrue(runtime.isVoiceOnlyUiActive.value)

            // Duplicate callback after onError (before retry starts) is ignored
            runtime.voiceController.simulateRecognitionResultsForTest(listOf("ghost utterance"))
            ShadowLooper.idleMainLooper()
            assertFalse("Duplicate callback must not trigger turn", runtime.isTurnActiveForTest())

            ShadowLooper.idleMainLooper(600L, TimeUnit.MILLISECONDS)
            pumpMainUntil { runtime.voiceController.getListenAttemptCountForTest() > initialAttempts }
            assertEquals(initialAttempts + 1, runtime.voiceController.getListenAttemptCountForTest())

            // Explicit Stop / Dismiss cancels retries and terminates session cleanly
            runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_NO_MATCH)
            assertTrue(runtime.isFollowUpScheduledForTest())
            val countAtStop = runtime.voiceController.getListenAttemptCountForTest()
            runtime.stopAssistant()
            ShadowLooper.idleMainLooper(1000L, TimeUnit.MILLISECONDS)

            assertFalse(runtime.isVoiceSessionActive.value)
            assertFalse(runtime.isVoiceOnlyUiActive.value)
            assertFalse(runtime.isFollowUpScheduledForTest())
            assertEquals(countAtStop, runtime.voiceController.getListenAttemptCountForTest())
        } finally {
            runtime.stopAssistant()
        }
    }

    @Test
    fun `continuous voice mode processes request speaks complete response and auto listens after TTS completion`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = prepareRuntimeForVoiceTest(app)
        runtime.voiceController.setTtsReadyForTest(true)

        // Confirm BrainConfig disables concurrent AudioRecord barge-in by default
        assertFalse(BrainConfig().voiceBargeInEnabled)

        val seenPrompts = mutableListOf<String>()
        runtime.turnExecutorOverride = { rawInput, _, _, _, isVoiceTurn ->
            seenPrompts.add(rawInput)
            assertTrue(isVoiceTurn)
            flow {
                emit(
                    TurnProgressEvent.Finished(
                        replyText = "Today's forecast is clear and sunny across your region with light winds.",
                        agentId = "auto",
                        activities = emptyList(),
                        isError = false
                    )
                )
            }
        }

        try {
            assertTrue(runtime.startInAppVoiceMode())
            val attemptsBeforeUtterance = runtime.voiceController.getListenAttemptCountForTest()

            // Simulate user speech -> AI turn finishes -> wait for TTS -> re-listen
            runtime.voiceController.simulateRecognitionResultsForTest(listOf("what is the weather today"))
            pumpMainUntil { !runtime.isTurnActiveForTest() && !runtime.isBusy.value }

            assertEquals(listOf("what is the weather today"), seenPrompts)
            assertTrue(runtime.isVoiceSessionActive.value)
            assertTrue(runtime.isAutoFollowUpAllowedForTest())

            // Utterance started from speak(); stale utterance ID must NOT re-arm listening
            val expectedUttId = runtime.voiceController.lastStartedUtteranceId()!!
            runtime.voiceController.simulateUtteranceStartForTest(expectedUttId)
            runtime.voiceController.triggerTestUtteranceCompletion("stale-other-utt")
            ShadowLooper.idleMainLooper(500L, TimeUnit.MILLISECONDS)
            assertEquals(attemptsBeforeUtterance, runtime.voiceController.getListenAttemptCountForTest())

            // Natural completion of the expected utterance re-arms listening cleanly
            runtime.voiceController.triggerTestUtteranceCompletion(expectedUttId)
            ShadowLooper.idleMainLooper(500L, TimeUnit.MILLISECONDS)
            pumpMainUntil { runtime.voiceController.getListenAttemptCountForTest() > attemptsBeforeUtterance }
            assertEquals(attemptsBeforeUtterance + 1, runtime.voiceController.getListenAttemptCountForTest())

            // Tapping orb during TTS playback interrupts speech cleanly and starts listening immediately
            runtime.voiceController.cancelListening()
            runtime.voiceController.simulateUtteranceStartForTest("utt-tap-interrupt")
            assertTrue(runtime.voiceController.isSpeaking.value)
            assertTrue(runtime.interruptSpeechAndListen())
            assertFalse(runtime.voiceController.isSpeaking.value)
            assertEquals(attemptsBeforeUtterance + 2, runtime.voiceController.getListenAttemptCountForTest())
        } finally {
            runtime.turnExecutorOverride = null
            runtime.stopAssistant()
        }
    }

    @Test
    fun `voice controller preserves long tts responses without 1200 char cutoff and splits cleanly at sentence boundaries`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val voice = VoiceController(app)
        try {
            val sentence = "This is a complete response sentence that should never be truncated prematurely. "
            val longReply = buildString {
                repeat(55) { append(sentence) } // ~4400 chars
            }
            val sanitized = voice.sanitizeTextForSpeech(longReply)
            assertTrue("Sanitized TTS text must retain > 1200 chars, was ${sanitized.length}", sanitized.length > 4000)

            val chunks = voice.splitTextForTtsChunks(sanitized, maxChunkLen = 3500)
            assertTrue("Long reply > 3500 chars must split into multiple chunks", chunks.size >= 2)
            assertTrue(chunks.all { it.length <= 3500 })
            assertTrue(chunks.first().endsWith("."))
            assertEquals(
                sanitized.replace(Regex("\\s+"), " "),
                chunks.joinToString(" ").replace(Regex("\\s+"), " ")
            )
        } finally {
            voice.release()
        }
    }

    @Test
    fun `chat bar single shot microphone remains single shot without starting continuous voice session`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = prepareRuntimeForVoiceTest(app)

        try {
            // 1. Tap chat-bar mic -> does NOT activate continuous session
            runtime.toggleVoiceInput()
            assertFalse(runtime.isVoiceSessionActive.value)
            assertFalse(runtime.isVoiceOnlyUiActive.value)
            val initialAttempts = runtime.voiceController.getListenAttemptCountForTest()
            assertEquals(1, initialAttempts)

            // 2. Recognition timeout on single-shot dictation does NOT auto-retry
            runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_NO_MATCH)
            ShadowLooper.idleMainLooper(800L, TimeUnit.MILLISECONDS)
            assertFalse(runtime.isVoiceSessionActive.value)
            assertFalse(runtime.isFollowUpScheduledForTest())
            assertEquals(1, runtime.voiceController.getListenAttemptCountForTest())
            assertEquals("Didn't catch that — please try again.", runtime.bannerMessage.value)

            // 3. Valid single-shot utterance processes turn without auto follow-up
            runtime.consumeBanner()
            runtime.turnExecutorOverride = { _, _, _, _, isVoice ->
                assertTrue(isVoice)
                flow {
                    emit(TurnProgressEvent.Finished("Single-shot reply.", "auto", emptyList(), false))
                }
            }
            runtime.toggleVoiceInput()
            assertEquals(2, runtime.voiceController.getListenAttemptCountForTest())
            runtime.voiceController.simulateRecognitionResultsForTest(listOf("quick note"))
            pumpMainUntil { !runtime.isTurnActiveForTest() && !runtime.isBusy.value }
            ShadowLooper.idleMainLooper(800L, TimeUnit.MILLISECONDS)

            assertFalse(runtime.isVoiceSessionActive.value)
            assertFalse(runtime.isAutoFollowUpAllowedForTest())
            assertEquals(2, runtime.voiceController.getListenAttemptCountForTest())
        } finally {
            runtime.turnExecutorOverride = null
            runtime.stopAssistant()
        }
    }
}
