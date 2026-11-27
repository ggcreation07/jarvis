package com.jarvis.app.platform

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.jarvis.app.domain.brain.UserLanguage
import java.util.Locale

data class AvailableVoiceInfo(
    val id: String,
    val name: String,
    val locale: Locale,
    val languageCode: String,
    val isMale: Boolean,
    val isNetworkRequired: Boolean,
    val quality: Int
)

sealed interface VoiceSelectionOutcome {
    data class Selected(
        val voice: Voice,
        val isFallback: Boolean = false,
        val fallbackReason: String? = null
    ) : VoiceSelectionOutcome

    data class FallbackApproved(
        val locale: Locale,
        val pitch: Float,
        val description: String
    ) : VoiceSelectionOutcome

    data class Unavailable(
        val reason: String,
        val targetLanguage: UserLanguage
    ) : VoiceSelectionOutcome
}

class VoiceSelectionManager {

    internal var voiceProviderOverride: (() -> Set<Voice>?)? = null

    companion object {
        private val EXPLICIT_FEMALE_INDICATORS = setOf(
            "female", "woman", "girl", "gender=female", "#female", "_female",
            "-hic-", "-hia-", "-mra-", "-ena-", "-enb-"
        )

        private val KNOWN_MALE_INDICATORS = listOf(
            "male", "gender=male", "#male", "_male", "-male-",
            "-hie-", "-hid-", // Google Hindi male
            "-mrc-", // Google Marathi male
            "-enc-", "-end-", // Google Indian English male
            "-sfg-", "-iol-", "-tpd-", // Google US English male
            "-rjs", "-gda", // Google UK male
            "wavenet-b", "wavenet-d", "standard-b", "standard-d" // Google Cloud male
        )
    }

    fun isExplicitMaleVoice(voice: Voice): Boolean {
        val nameLower = voice.name.lowercase(Locale.ROOT)
        val features = runCatching { voice.features }.getOrNull().orEmpty().map { it.lowercase(Locale.ROOT) }
        if (features.any { it.contains("male") || it == "gender=male" }) return true
        if (KNOWN_MALE_INDICATORS.any { nameLower.contains(it) }) return true
        return false
    }

    fun isMaleVoice(voice: Voice): Boolean {
        val nameLower = voice.name.lowercase(Locale.ROOT)
        val features = runCatching { voice.features }.getOrNull().orEmpty().map { it.lowercase(Locale.ROOT) }

        // If explicitly female, reject immediately
        if (EXPLICIT_FEMALE_INDICATORS.any { nameLower.contains(it) } ||
            features.any { f -> EXPLICIT_FEMALE_INDICATORS.any { f.contains(it) } }
        ) {
            return false
        }

        // Accept explicit male or neutral/unspecified voices
        return true
    }

    fun getAvailableMaleVoices(tts: TextToSpeech?): List<AvailableVoiceInfo> {
        val voices = voiceProviderOverride?.invoke() ?: runCatching { tts?.voices }.getOrNull().orEmpty()
        return voices.filter { isMaleVoice(it) }.map { voice ->
            AvailableVoiceInfo(
                id = voice.name,
                name = voice.name,
                locale = voice.locale,
                languageCode = voice.locale.language,
                isMale = true,
                isNetworkRequired = voice.isNetworkConnectionRequired,
                quality = voice.quality
            )
        }.sortedWith(
            compareBy<AvailableVoiceInfo> { it.languageCode }
                .thenBy { it.name }
        )
    }

    fun selectMaleVoice(
        tts: TextToSpeech?,
        targetLanguage: UserLanguage,
        preferredVoiceName: String = "",
        allowFallbackWithApproval: Boolean = false,
        configuredElevenLabsMaleVoiceId: String = ""
    ): VoiceSelectionOutcome {
        val targetLocale = targetLanguage.ttsLocale
        val allVoices = voiceProviderOverride?.invoke() ?: runCatching { tts?.voices }.getOrNull().orEmpty()
        if (allVoices.isEmpty()) {
            return VoiceSelectionOutcome.FallbackApproved(
                locale = targetLocale,
                pitch = 0.95f,
                description = "Engine voice enumeration unavailable; applied ${targetLanguage.displayName} with male register pitch."
            )
        }

        val maleVoices = allVoices.filter { isMaleVoice(it) }

        // 1. If an explicit verified male voice is preferred by the user, respect it
        if (preferredVoiceName.isNotBlank()) {
            val userVoice = maleVoices.firstOrNull { it.name.equals(preferredVoiceName, ignoreCase = true) }
            if (userVoice != null) {
                return VoiceSelectionOutcome.Selected(userVoice)
            }
        }

        // 1B. If ElevenLabs male voice ID is configured, prefer it if present in available male voices
        if (configuredElevenLabsMaleVoiceId.isNotBlank()) {
            val elevenLabsVoice = maleVoices.firstOrNull { it.name.equals(configuredElevenLabsMaleVoiceId, ignoreCase = true) }
            if (elevenLabsVoice != null) {
                return VoiceSelectionOutcome.Selected(elevenLabsVoice)
            }
        }

        // 2. Search for direct locale matching male voice (explicit male first, then offline & quality)
        val directMatches = maleVoices.filter { isLocaleMatch(it.locale, targetLocale) }
            .sortedWith(
                compareByDescending<Voice> { isExplicitMaleVoice(it) }
                    .thenByDescending { !it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency }
            )

        if (directMatches.isNotEmpty()) {
            return VoiceSelectionOutcome.Selected(directMatches.first())
        }

        // 2B. Search for same-language male voice across regional variants (e.g. en-US / en-GB for English)
        val sameLanguageMatches = maleVoices.filter {
            it.locale.language.equals(targetLocale.language, ignoreCase = true)
        }.sortedWith(
            compareByDescending<Voice> { isExplicitMaleVoice(it) }
                .thenByDescending { !it.isNetworkConnectionRequired }
                .thenByDescending { it.quality }
                .thenBy { it.latency }
        )
        if (sameLanguageMatches.isNotEmpty()) {
            val chosen = sameLanguageMatches.first()
            return VoiceSelectionOutcome.Selected(
                voice = chosen,
                isFallback = true,
                fallbackReason = "Using ${chosen.locale.toLanguageTag()} male voice for ${targetLanguage.displayName}."
            )
        }

        // 3. Language-specific matching:
        // For Hinglish, if no Hindi male voice exists, look for Indian English male voice
        if (targetLanguage == UserLanguage.HINGLISH) {
            val inEnglishMale = maleVoices.filter {
                it.locale.language.equals("en", ignoreCase = true) &&
                    it.locale.country.equals("IN", ignoreCase = true)
            }.sortedByDescending { isExplicitMaleVoice(it) }.firstOrNull()
            if (inEnglishMale != null) {
                return VoiceSelectionOutcome.Selected(
                    voice = inEnglishMale,
                    isFallback = true,
                    fallbackReason = "Using English (India) male voice for Hinglish."
                )
            }
        }

        // 4. Missing male voice for target language
        // Never silently switch to female voice.
        // Check if user approved fallback
        if (allowFallbackWithApproval) {
            // Find another available male voice (e.g. English male or Hindi male) as an approved fallback
            val fallbackMaleVoice = maleVoices.firstOrNull {
                it.locale.language.equals("en", ignoreCase = true) ||
                    it.locale.language.equals("hi", ignoreCase = true)
            }
            if (fallbackMaleVoice != null) {
                return VoiceSelectionOutcome.Selected(
                    voice = fallbackMaleVoice,
                    isFallback = true,
                    fallbackReason = "Approved fallback: Using ${fallbackMaleVoice.name} for ${targetLanguage.displayName}."
                )
            }

            // Fallback pitch modulation on available engine (lowered to deep male register)
            return VoiceSelectionOutcome.FallbackApproved(
                locale = targetLocale,
                pitch = 0.88f,
                description = "Approved fallback: Using ${targetLanguage.displayName} with male register pitch modulation."
            )
        }

        return VoiceSelectionOutcome.Unavailable(
            reason = "No suitable male voice is available on device for ${targetLanguage.displayName}. Please install a male voice pack or enable fallback approval.",
            targetLanguage = targetLanguage
        )
    }

    private fun isLocaleMatch(voiceLoc: Locale, targetLoc: Locale): Boolean {
        if (!voiceLoc.language.equals(targetLoc.language, ignoreCase = true)) {
            return false
        }
        if (targetLoc.country.isNotBlank() && voiceLoc.country.isNotBlank()) {
            return voiceLoc.country.equals(targetLoc.country, ignoreCase = true)
        }
        return true
    }
}
