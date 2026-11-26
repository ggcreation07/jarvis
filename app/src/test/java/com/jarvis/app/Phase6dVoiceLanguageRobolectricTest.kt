package com.jarvis.app

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.domain.brain.LanguageDetectionManager
import com.jarvis.app.domain.brain.NaturalLanguageReplyAdapter
import com.jarvis.app.domain.brain.UserLanguage
import com.jarvis.app.platform.VoiceController
import com.jarvis.app.platform.VoiceSelectionManager
import com.jarvis.app.platform.VoiceSelectionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase6dVoiceLanguageRobolectricTest {

    @Test
    fun `language detection manager detects Hindi Marathi Hinglish and English correctly`() {
        val manager = LanguageDetectionManager()

        // 1. Hindi Devanagari detection
        val hiRes = manager.detect("आवाज़ बढ़ाओ")
        assertEquals(UserLanguage.HINDI, hiRes.language)
        assertTrue(hiRes.isReliable)

        // 2. Marathi Devanagari detection
        val mrRes = manager.detect("आवाज वाढव")
        assertEquals(UserLanguage.MARATHI, mrRes.language)
        assertTrue(mrRes.isReliable)

        // 3. Hinglish Latin detection
        val hingRes = manager.detect("volume badha do")
        assertEquals(UserLanguage.HINGLISH, hingRes.language)
        assertTrue(hingRes.isReliable)

        // 4. English detection
        val enRes = manager.detect("increase the volume please")
        assertEquals(UserLanguage.ENGLISH, enRes.language)
        assertTrue(enRes.isReliable)
    }

    @Test
    fun `language detection manager handles low confidence fallback to last reliable language`() {
        val manager = LanguageDetectionManager()
        manager.detect("आवाज़ बढ़ाओ") // Sets reliable Hindi
        assertEquals(UserLanguage.HINDI, manager.getActiveLanguage())

        // Ambiguous command with low signal
        val ambiguous = manager.detect("youtube")
        assertEquals(UserLanguage.HINDI, ambiguous.language)
    }

    @Test
    fun `natural language reply adapter adapts summaries across languages`() {
        val summary = "Volume increased to 80%."

        val hiReply = NaturalLanguageReplyAdapter.adapt(summary, UserLanguage.HINDI, "आवाज़ बढ़ाओ", true)
        assertTrue(hiReply.contains("हाँ") || hiReply.contains("बढ़ा"))

        val mrReply = NaturalLanguageReplyAdapter.adapt(summary, UserLanguage.MARATHI, "आवाज वाढव", true)
        assertTrue(mrReply.contains("हो") || mrReply.contains("वाढवला"))

        val hingReply = NaturalLanguageReplyAdapter.adapt(summary, UserLanguage.HINGLISH, "volume badhao", true)
        assertEquals("Haan, volume badha diya.", hingReply)

        val enReply = NaturalLanguageReplyAdapter.adapt(summary, UserLanguage.ENGLISH, "volume up", true)
        assertEquals("Turned the volume up.", enReply)
    }

    @Test
    fun `voice selection manager filters male voices and rejects female voices`() {
        val manager = VoiceSelectionManager()

        val maleVoice = Voice("hi-in-x-hie-local", Locale("hi", "IN"), 400, 200, false, setOf("male", "gender=male"))
        val femaleVoice = Voice("hi-in-x-hia-local", Locale("hi", "IN"), 400, 200, false, setOf("female", "gender=female"))
        val neutralVoice = Voice("en-us-x-sfg-local", Locale("US"), 400, 200, false, emptySet())

        assertTrue(manager.isMaleVoice(maleVoice))
        assertFalse(manager.isMaleVoice(femaleVoice))
        assertTrue(manager.isMaleVoice(neutralVoice))

        manager.voiceProviderOverride = { setOf(maleVoice, femaleVoice, neutralVoice) }

        val outcome = manager.selectMaleVoice(null, UserLanguage.HINDI)
        assertTrue(outcome is VoiceSelectionOutcome.Selected)
        assertEquals("hi-in-x-hie-local", (outcome as VoiceSelectionOutcome.Selected).voice.name)
    }

    @Test
    fun `voice selection manager handles unavailable male voice with fallback approval`() {
        val manager = VoiceSelectionManager()
        val femaleVoice = Voice("hi-in-x-hia-local", Locale("hi", "IN"), 400, 200, false, setOf("female"))
        manager.voiceProviderOverride = { setOf(femaleVoice) }

        // Without approval -> Unavailable
        val unavailable = manager.selectMaleVoice(null, UserLanguage.HINDI, allowFallbackWithApproval = false)
        assertTrue(unavailable is VoiceSelectionOutcome.Unavailable)

        // With approval -> FallbackApproved or fallback male
        val approved = manager.selectMaleVoice(null, UserLanguage.HINDI, allowFallbackWithApproval = true)
        assertTrue(approved is VoiceSelectionOutcome.FallbackApproved || approved is VoiceSelectionOutcome.Selected)
    }

    @Test
    fun `voice controller integrates language detection and male voice personalization`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val controller = VoiceController(app)

        val maleHindi = Voice("hi-in-x-hie-local", Locale("hi", "IN"), 400, 200, false, setOf("male"))
        controller.setVoicesForTest(setOf(maleHindi))
        controller.setTtsReadyForTest(true)

        val detected = controller.languageDetectionManager.detect("आवाज़ बढ़ाओ")
        assertEquals(UserLanguage.HINDI, detected.language)

        val voices = controller.getAvailableMaleVoices()
        assertEquals(1, voices.size)
        assertEquals("hi-in-x-hie-local", voices[0].id)
    }
}
