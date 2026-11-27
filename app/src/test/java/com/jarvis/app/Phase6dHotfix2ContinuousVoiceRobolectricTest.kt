package com.jarvis.app

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.domain.brain.UserLanguage
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.platform.JarvisAssistantRuntime
import com.jarvis.app.platform.VoiceController
import com.jarvis.app.platform.VoiceControllerState
import com.jarvis.app.platform.VoiceSelectionManager
import com.jarvis.app.platform.VoiceSelectionOutcome
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
import java.util.Locale
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase6dHotfix2ContinuousVoiceRobolectricTest {

    private fun prepareRuntime(app: Application): JarvisAssistantRuntime {
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
        runtime.voiceController.resetListenAttemptCountForTest()
        ShadowLooper.idleMainLooper()
        return runtime
    }

    private fun prepareVoiceController(app: Application): VoiceController {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val resolveInfo = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = "com.google.android.googlequicksearchbox"
                name = "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
            }
        }
        @Suppress("DEPRECATION")
        Shadows.shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(RecognitionService.SERVICE_INTERFACE),
            resolveInfo
        )
        return VoiceController(app)
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
    fun `single-owner recognizer blocks duplicate startListening does not cancel on normal callbacks and logs transitions`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val voice = prepareVoiceController(app)
        try {
            assertEquals(VoiceControllerState.IDLE, voice.voiceState.value)

            val delivered = mutableListOf<String>()
            voice.startListening(onFinalResult = { delivered.add(it) }, onFailure = {})
            assertEquals(VoiceControllerState.LISTENING, voice.voiceState.value)
            assertEquals(1, voice.getListenAttemptCountForTest())
            assertEquals(1, voice.getRecognizerCreationCountForTest())
            assertEquals(0, voice.getRecognizerCancelCountForTest())

            // Duplicate startListening while LISTENING must be ignored
            voice.startListening(onFinalResult = { delivered.add(it) }, onFailure = {})
            assertEquals(1, voice.getListenAttemptCountForTest())
            assertEquals(1, voice.getRecognizerCreationCountForTest())
            assertTrue(voice.getTransitionLogsForTest().any { it.contains("START_LISTENING_IGNORED_DUPLICATE") })

            // Repeated callbacks within the same session remain coherent
            voice.simulateRecognitionReadyForTest()
            voice.simulateBeginningOfSpeechForTest()
            assertEquals(VoiceControllerState.LISTENING, voice.voiceState.value)

            // onEndOfSpeech moves to PROCESSING and blocks duplicate startListening during processing
            voice.simulateEndOfSpeechForTest()
            assertEquals(VoiceControllerState.PROCESSING, voice.voiceState.value)
            voice.simulateEndOfSpeechForTest() // duplicate onEndOfSpeech ignored
            voice.startListening(onFinalResult = { delivered.add(it) }, onFailure = {})
            assertEquals(1, voice.getListenAttemptCountForTest())

            // Valid onResults delivers once and does NOT call cancel() on SpeechRecognizer
            voice.simulateRecognitionResultsForTest(listOf("open youtube"))
            assertEquals(listOf("open youtube"), delivered)
            assertEquals(VoiceControllerState.PROCESSING, voice.voiceState.value)
            assertEquals(0, voice.getRecognizerCancelCountForTest())

            // Duplicate onResults after session ended is safely ignored
            voice.simulateRecognitionResultsForTest(listOf("ghost second result"))
            assertEquals(listOf("open youtube"), delivered)

            // Mark processing finished -> IDLE; next startListening reuses same SpeechRecognizer (creation count stays 1!)
            voice.markProcessingFinished("test_done")
            assertEquals(VoiceControllerState.IDLE, voice.voiceState.value)
            voice.startListening(onFinalResult = { delivered.add(it) }, onFailure = {})
            assertEquals(2, voice.getListenAttemptCountForTest())
            assertEquals(1, voice.getRecognizerCreationCountForTest())

            // Recoverable error (ERROR_NO_MATCH) transitions to IDLE without calling cancel()
            voice.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_NO_MATCH)
            assertEquals(VoiceControllerState.IDLE, voice.voiceState.value)
            assertEquals(0, voice.getRecognizerCancelCountForTest())

            // Duplicate onError is ignored
            voice.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            assertEquals(VoiceControllerState.IDLE, voice.voiceState.value)

            // Intentional cancelListening increments cancel count
            voice.startListening(onFinalResult = { delivered.add(it) }, onFailure = {})
            voice.cancelListening("user_cancelled")
            assertEquals(1, voice.getRecognizerCancelCountForTest())
            assertEquals(VoiceControllerState.IDLE, voice.voiceState.value)
        } finally {
            voice.release()
        }
    }

    @Test
    fun `explicit stop enters STOPPED blocks trailing callbacks and blocks auto-restarts until manual start`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val voice = prepareVoiceController(app)
        try {
            val delivered = mutableListOf<String>()
            val failures = mutableListOf<String>()
            voice.startListening(
                onFinalResult = { delivered.add(it) },
                onFailure = { failures.add(it) }
            )
            assertEquals(VoiceControllerState.LISTENING, voice.voiceState.value)

            voice.stopSession("user_stopped")
            assertEquals(VoiceControllerState.STOPPED, voice.voiceState.value)
            assertEquals(1, voice.getRecognizerCancelCountForTest())

            // Trailing callbacks after stopSession are ignored
            voice.simulateRecognitionResultsForTest(listOf("late transcript"))
            voice.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_NETWORK)
            assertTrue(delivered.isEmpty())
            assertTrue(failures.isEmpty())
            assertEquals(VoiceControllerState.STOPPED, voice.voiceState.value)

            // Auto-restart while STOPPED is blocked
            voice.startListening(
                onFinalResult = { delivered.add(it) },
                onFailure = { failures.add(it) },
                isAutoRestart = true
            )
            assertEquals(1, voice.getListenAttemptCountForTest())
            assertEquals(VoiceControllerState.STOPPED, voice.voiceState.value)

            // Explicit user restart clears STOPPED and begins listening
            voice.startListening(
                onFinalResult = { delivered.add(it) },
                onFailure = { failures.add(it) },
                isAutoRestart = false
            )
            assertEquals(2, voice.getListenAttemptCountForTest())
            assertEquals(VoiceControllerState.LISTENING, voice.voiceState.value)
        } finally {
            voice.release()
        }
    }

    @Test
    fun `permission denial transitions to STOPPED and blocks continuous restarts`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val voice = prepareVoiceController(app)
        try {
            Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
            var failureKind: String? = null
            voice.startListening(
                onFinalResult = {},
                onFailure = { failureKind = it }
            )
            assertEquals(VoiceControllerState.STOPPED, voice.voiceState.value)
            assertTrue(failureKind?.contains("permission", ignoreCase = true) == true)
            assertEquals(0, voice.getListenAttemptCountForTest())
        } finally {
            voice.release()
        }
    }

    @Test
    fun `long silence backs off and bounds retries without creating infinite restart loop`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = prepareRuntime(app)

        try {
            assertTrue(runtime.startInAppVoiceMode())
            assertTrue(runtime.isVoiceSessionActive.value)
            val observedDelays = mutableListOf<Long>()

            // Trigger 1..MAX_CONSECUTIVE_SILENCE_RETRIES silence timeouts -> each schedules bounded backoff
            for (attempt in 1..JarvisAssistantRuntime.MAX_CONSECUTIVE_SILENCE_RETRIES) {
                val before = runtime.voiceController.getListenAttemptCountForTest()
                runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                val delayMs = runtime.lastListenRetryDelayMsForTest!!
                observedDelays.add(delayMs)
                assertTrue("Expected scheduled follow-up on silence attempt $attempt", runtime.isFollowUpScheduledForTest())
                ShadowLooper.idleMainLooper(delayMs + 100L, TimeUnit.MILLISECONDS)
                pumpMainUntil { runtime.voiceController.getListenAttemptCountForTest() > before }
            }

            // First 3 short silences retry quickly (~500ms); attempts 4..6 increase progressively
            assertEquals(JarvisAssistantRuntime.RETRY_SILENCE_DELAY_MS, observedDelays[0])
            assertEquals(JarvisAssistantRuntime.RETRY_SILENCE_DELAY_MS, observedDelays[1])
            assertEquals(JarvisAssistantRuntime.RETRY_SILENCE_DELAY_MS, observedDelays[2])
            assertTrue("Long silence attempt 4 should back off > 500ms", observedDelays[3] > observedDelays[2])
            assertTrue("Long silence attempt 6 should back off >= attempt 4", observedDelays[5] >= observedDelays[3])

            // Exceeding MAX_CONSECUTIVE_SILENCE_RETRIES pauses auto-restart cleanly while keeping Voice Mode active
            val countAtCap = runtime.voiceController.getListenAttemptCountForTest()
            runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            assertNull("Extended silence must stop scheduling automatic restarts", runtime.lastListenRetryDelayMsForTest)
            assertFalse(runtime.isFollowUpScheduledForTest())
            assertTrue("Voice Mode stays active for tap-to-speak", runtime.isVoiceSessionActive.value)
            ShadowLooper.idleMainLooper(6_000L, TimeUnit.MILLISECONDS)
            assertEquals(countAtCap, runtime.voiceController.getListenAttemptCountForTest())
        } finally {
            runtime.stopAssistant()
        }
    }

    @Test
    fun `temporary recognition error retries are bounded across slow and fast errors`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = prepareRuntime(app)

        try {
            assertTrue(runtime.startInAppVoiceMode())
            for (attempt in 1..JarvisAssistantRuntime.MAX_CONSECUTIVE_TEMP_RETRIES) {
                val before = runtime.voiceController.getListenAttemptCountForTest()
                runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_NETWORK)
                val delayMs = runtime.lastListenRetryDelayMsForTest!!
                assertTrue(runtime.isFollowUpScheduledForTest())
                ShadowLooper.idleMainLooper(delayMs + 100L, TimeUnit.MILLISECONDS)
                pumpMainUntil { runtime.voiceController.getListenAttemptCountForTest() > before }
            }

            val countAtCap = runtime.voiceController.getListenAttemptCountForTest()
            runtime.voiceController.simulateRecognitionErrorForTest(SpeechRecognizer.ERROR_NETWORK)
            assertNull(runtime.lastListenRetryDelayMsForTest)
            assertFalse(runtime.isFollowUpScheduledForTest())
            assertTrue(runtime.isVoiceSessionActive.value)
            ShadowLooper.idleMainLooper(6_000L, TimeUnit.MILLISECONDS)
            assertEquals(countAtCap, runtime.voiceController.getListenAttemptCountForTest())
        } finally {
            runtime.stopAssistant()
        }
    }

    @Test
    fun `unrelated TTS completion does not trigger listening and activity backgrounding preserves session`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = prepareRuntime(app)
        runtime.voiceController.setTtsReadyForTest(true)
        runtime.turnExecutorOverride = { _, _, _, _, _ ->
            flow {
                emit(TurnProgressEvent.Finished("Done.", "auto", emptyList(), false))
            }
        }

        try {
            assertTrue(runtime.startInAppVoiceMode())
            val baselineAttempts = runtime.voiceController.getListenAttemptCountForTest()

            // Hide UI / background activity -> Voice Mode session & listening continue uninterrupted
            runtime.hideVoiceOnlyUiKeepSession()
            runtime.onActivityStopped()
            assertTrue(runtime.isVoiceSessionActive.value)
            assertEquals(VoiceControllerState.LISTENING, runtime.voiceController.voiceState.value)

            // User finishes speaking -> PROCESSING -> turn runs -> SPEAKING
            runtime.voiceController.simulateEndOfSpeechForTest()
            assertEquals(VoiceControllerState.PROCESSING, runtime.voiceController.voiceState.value)
            runtime.voiceController.simulateRecognitionResultsForTest(listOf("turn off flashlight"))
            pumpMainUntil { !runtime.isTurnActiveForTest() && !runtime.isBusy.value }

            val expectedUttId = runtime.voiceController.lastStartedUtteranceId()!!
            runtime.voiceController.simulateUtteranceStartForTest(expectedUttId)
            assertEquals(VoiceControllerState.SPEAKING, runtime.voiceController.voiceState.value)

            // Unrelated utterance completion (Settings test speech, etc.) must NOT match or schedule listening
            runtime.voiceController.triggerTestUtteranceCompletion("settings-test-utterance")
            ShadowLooper.idleMainLooper(600L, TimeUnit.MILLISECONDS)
            assertEquals(baselineAttempts, runtime.voiceController.getListenAttemptCountForTest())

            // Now complete the real expected utterance -> auto-restarts listening once
            runtime.voiceController.triggerTestUtteranceCompletion(expectedUttId)
            ShadowLooper.idleMainLooper(600L, TimeUnit.MILLISECONDS)
            pumpMainUntil { runtime.voiceController.getListenAttemptCountForTest() > baselineAttempts }
            assertEquals(baselineAttempts + 1, runtime.voiceController.getListenAttemptCountForTest())
            assertEquals(VoiceControllerState.LISTENING, runtime.voiceController.voiceState.value)

            // Subsequent unrelated TTS completion while expectedTtsUtteranceId is null must NOT trigger restart
            runtime.voiceController.cancelListening("pause_for_check")
            val pausedAttempts = runtime.voiceController.getListenAttemptCountForTest()
            runtime.voiceController.triggerTestUtteranceCompletion("stray-read-aloud-utt")
            ShadowLooper.idleMainLooper(600L, TimeUnit.MILLISECONDS)
            assertEquals(pausedAttempts, runtime.voiceController.getListenAttemptCountForTest())
        } finally {
            runtime.turnExecutorOverride = null
            runtime.stopAssistant()
        }
    }

    @Test
    fun `voice selection manager prioritizes explicit male voices over neutral voices and matches regional variants`() {
        val manager = VoiceSelectionManager()
        val neutralHindi = Voice("hi-in-x-neutral-local", Locale("hi", "IN"), 500, 100, false, emptySet())
        val explicitMaleHindi = Voice("hi-in-x-hie-local", Locale("hi", "IN"), 400, 200, false, setOf("gender=male"))
        val femaleHindi = Voice("hi-in-x-hia-local", Locale("hi", "IN"), 500, 100, false, setOf("gender=female"))
        val maleUsEnglish = Voice("en-us-x-iol-local", Locale("en", "US"), 400, 200, false, setOf("male"))

        manager.voiceProviderOverride = { setOf(neutralHindi, explicitMaleHindi, femaleHindi, maleUsEnglish) }

        val hiOutcome = manager.selectMaleVoice(null, UserLanguage.HINDI)
        assertTrue(hiOutcome is VoiceSelectionOutcome.Selected)
        assertEquals("hi-in-x-hie-local", (hiOutcome as VoiceSelectionOutcome.Selected).voice.name)

        // English falls back to en-US explicit male voice when no en-IN voice is installed
        val enOutcome = manager.selectMaleVoice(null, UserLanguage.ENGLISH)
        assertTrue(enOutcome is VoiceSelectionOutcome.Selected)
        assertEquals("en-us-x-iol-local", (enOutcome as VoiceSelectionOutcome.Selected).voice.name)
    }
}
