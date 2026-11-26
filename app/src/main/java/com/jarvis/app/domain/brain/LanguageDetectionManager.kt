package com.jarvis.app.domain.brain

import com.jarvis.app.domain.model.ChatMessageItem
import java.util.Locale

enum class UserLanguage(
    val code: String,
    val displayName: String,
    val locale: Locale,
    val ttsLocale: Locale,
    val isLatinScript: Boolean
) {
    HINDI(
        code = "hi",
        displayName = "Hindi",
        locale = Locale("hi", "IN"),
        ttsLocale = Locale("hi", "IN"),
        isLatinScript = false
    ),
    MARATHI(
        code = "mr",
        displayName = "Marathi",
        locale = Locale("mr", "IN"),
        ttsLocale = Locale("mr", "IN"),
        isLatinScript = false
    ),
    HINGLISH(
        code = "hi-Latn",
        displayName = "Hinglish",
        locale = Locale("hi", "IN"),
        ttsLocale = Locale("hi", "IN"),
        isLatinScript = true
    ),
    ENGLISH(
        code = "en",
        displayName = "English",
        locale = Locale("en", "US"),
        ttsLocale = Locale("en", "IN"),
        isLatinScript = true
    );

    companion object {
        fun fromCode(code: String): UserLanguage? = when (code.lowercase(Locale.ROOT)) {
            "hi", "hindi" -> HINDI
            "mr", "marathi" -> MARATHI
            "hi-latn", "hinglish", "hi_latn" -> HINGLISH
            "en", "english" -> ENGLISH
            else -> null
        }
    }
}

data class LanguageDetectionResult(
    val language: UserLanguage,
    val confidence: Float,
    val isReliable: Boolean,
    val rawText: String,
    val detectedMarkers: List<String> = emptyList(),
    val isCodeSwitched: Boolean = false
)

class LanguageDetectionManager {

    private val lock = Any()

    @Volatile
    private var lastReliableLanguage: UserLanguage? = null

    @Volatile
    private var activeConversationLanguage: UserLanguage = UserLanguage.ENGLISH

    companion object {
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.85f
        private const val MIN_RELIABLE_CONFIDENCE = 0.65f

        // Characteristic Marathi vocabulary and morphology in Devanagari script
        private val MARATHI_MARKERS = setOf(
            "आहे", "आहेत", "आहोत", "नाही", "नाहीत", "होय", "काय", "कसा", "कशी", "कसे",
            "कुठे", "केव्हा", "कधी", "कोण", "कोठे", "करा", "कर", "करावे", "करायचे", "वाढव",
            "वाढवा", "कमी", "उघड", "उघडा", "बंद", "चालू", "माझे", "तुझे", "आपले", "मला",
            "तुला", "त्याला", "तिला", "त्यांना", "सांग", "सांगा", "दाखव", "दाखवा", "वाच",
            "वाचा", "थांब", "पुन्हा", "तीच", "स्क्रीन", "आवाज", "वार", "दिनांक", "वेळ",
            "झाली", "झाला", "आलो", "गेलो", "नंतर", "आणि", "पण", "परंतु", "म्हणून", "वर",
            "खाली", "शोध", "शोधा", "दाबा", "बटण", "कॅमेरा", "कॅल्क्युलेटर", "मेसेजेस"
        )

        // Characteristic Hindi vocabulary and morphology in Devanagari script
        private val HINDI_MARKERS = setOf(
            "है", "हैं", "हूँ", "था", "थी", "थे", "नहीं", "हाँ", "क्या", "कैसे",
            "कैसा", "कैसी", "कहाँ", "कब", "कौन", "करना", "करो", "करें", "बढ़ाओ", "बढ़ाएं",
            "कम", "खोलो", "खोल", "बंद", "चालू", "मेरा", "मेरी", "मेरे", "तुम्हारा", "आपका",
            "मुझे", "तुझे", "उसे", "उन्हें", "बताओ", "दिखाओ", "पढ़ो", "पढ़ें", "रुको", "फिर",
            "और", "लेकिन", "पर", "नीचे", "ऊपर", "समय", "कितना", "बजे", "ढूंढो", "खोजो",
            "दबाओ", "बटन", "कैमरा", "कैलकुलेटर", "मैसेजेस"
        )

        // Characteristic Hinglish words (Hindi/Urdu in Latin script)
        private val HINGLISH_MARKERS = setOf(
            "aap", "apna", "apne", "apni", "tum", "tumhara", "tumhare", "main", "mera", "mere",
            "meri", "mujhe", "mujhko", "hum", "humara", "kya", "kyu", "kyun", "kaise", "kaisa",
            "kaisi", "kab", "kaha", "kahan", "kaun", "kitna", "kitne", "kitni", "karo", "karna",
            "kare", "karen", "kar", "dijiye", "hoga", "hogi", "hoge", "hai", "hain", "hoon",
            "tha", "thi", "raha", "rahi", "rahe", "gaya", "gayi", "gaye", "aao",
            "jao", "batao", "dikhao", "padho", "kholo", "bulao", "sunao", "chalu", "band",
            "badhao", "ghatao", "haan", "nahi", "nahin", "mat", "bhi", "aur", "phir",
            "fir", "pehle", "baad", "mein", "wahi", "usko", "isko", "unko", "inhe",
            "unhe", "pichhe", "aage", "upar", "niche", "neeche", "theek", "achha", "accha",
            "bahut", "thoda", "dhundo", "daba", "dabao", "aawaz", "awaz", "awaaz", "bhai",
            "yaar", "karo na", "badha do", "kholo na", "sunao na"
        )

        // Characteristic English grammatical words and indicators
        private val ENGLISH_MARKERS = setOf(
            "the", "a", "an", "this", "that", "these", "those", "is", "are", "am",
            "was", "were", "be", "been", "being", "have", "has", "had", "do", "does",
            "did", "can", "could", "would", "should", "will", "shall", "may", "might",
            "must", "it", "its", "you", "your", "yours", "he", "she", "they", "them",
            "their", "we", "us", "our", "my", "mine", "what", "how", "when", "where",
            "which", "who", "why", "turn", "open", "close", "read", "click", "find",
            "scroll", "increase", "decrease", "navigate", "please", "thank", "thanks", "hello",
            "okay", "today", "yesterday", "tomorrow", "settings", "volume", "flashlight", "screen"
        )

        // Ambiguous standalone tokens that have low language signal on their own
        private val AMBIGUOUS_TOKENS = setOf(
            "ok", "okay", "yes", "no", "hi", "hey", "hello", "hmm", "ha", "haan",
            "open", "stop", "back", "cancel", "next", "continue", "youtube", "whatsapp",
            "google", "chrome", "settings", "camera", "music", "spotify", "notes"
        )
    }

    fun getActiveLanguage(): UserLanguage = synchronized(lock) { activeConversationLanguage }

    fun setExplicitLanguage(language: UserLanguage) = synchronized(lock) {
        lastReliableLanguage = language
        activeConversationLanguage = language
    }

    fun reset() = synchronized(lock) {
        lastReliableLanguage = null
        activeConversationLanguage = UserLanguage.ENGLISH
    }

    fun detect(
        rawInput: String,
        recentHistory: List<ChatMessageItem> = emptyList(),
        preferredLanguage: String = "auto",
        autoDetect: Boolean = true
    ): LanguageDetectionResult = synchronized(lock) {
        val trimmed = rawInput.trim().replace(Regex("\\s+"), " ")

        // If auto-detect is disabled, honor the manual preferred language setting
        if (!autoDetect && preferredLanguage != "auto") {
            val manual = UserLanguage.fromCode(preferredLanguage) ?: UserLanguage.ENGLISH
            activeConversationLanguage = manual
            return LanguageDetectionResult(
                language = manual,
                confidence = 1.0f,
                isReliable = true,
                rawText = trimmed,
                detectedMarkers = listOf("manual_setting:$preferredLanguage")
            )
        }

        if (trimmed.isEmpty()) {
            return LanguageDetectionResult(
                language = activeConversationLanguage,
                confidence = 0.0f,
                isReliable = false,
                rawText = trimmed
            )
        }

        // 1. Devanagari script detection (Hindi vs Marathi)
        val devanagariCount = trimmed.count { it in '\u0900'..'\u097F' }
        val latinCount = trimmed.count { (it in 'a'..'z') || (it in 'A'..'Z') }

        if (devanagariCount > 0 && devanagariCount >= latinCount) {
            val devWords = trimmed.split(Regex("[\\s,;।.!?]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val marathiMatches = devWords.filter { it in MARATHI_MARKERS || hasMarathiSuffix(it) }
            val hindiMatches = devWords.filter { it in HINDI_MARKERS || hasHindiSuffix(it) }

            val totalDevMatches = marathiMatches.size + hindiMatches.size
            val isMixedDevanagari = marathiMatches.isNotEmpty() && hindiMatches.isNotEmpty()

            return when {
                marathiMatches.size > hindiMatches.size -> {
                    val conf = (0.75f + (marathiMatches.size * 0.10f)).coerceAtMost(0.98f)
                    lastReliableLanguage = UserLanguage.MARATHI
                    activeConversationLanguage = UserLanguage.MARATHI
                    LanguageDetectionResult(
                        language = UserLanguage.MARATHI,
                        confidence = conf,
                        isReliable = conf >= MIN_RELIABLE_CONFIDENCE,
                        rawText = trimmed,
                        detectedMarkers = marathiMatches,
                        isCodeSwitched = isMixedDevanagari
                    )
                }
                hindiMatches.size > marathiMatches.size -> {
                    val conf = (0.75f + (hindiMatches.size * 0.10f)).coerceAtMost(0.98f)
                    lastReliableLanguage = UserLanguage.HINDI
                    activeConversationLanguage = UserLanguage.HINDI
                    LanguageDetectionResult(
                        language = UserLanguage.HINDI,
                        confidence = conf,
                        isReliable = conf >= MIN_RELIABLE_CONFIDENCE,
                        rawText = trimmed,
                        detectedMarkers = hindiMatches,
                        isCodeSwitched = isMixedDevanagari
                    )
                }
                totalDevMatches > 0 -> {
                    // Tie between Hindi and Marathi: check previous conversation context
                    val fallback = when (lastReliableLanguage) {
                        UserLanguage.MARATHI -> UserLanguage.MARATHI
                        UserLanguage.HINDI -> UserLanguage.HINDI
                        else -> UserLanguage.HINDI
                    }
                    activeConversationLanguage = fallback
                    LanguageDetectionResult(
                        language = fallback,
                        confidence = 0.70f,
                        isReliable = true,
                        rawText = trimmed,
                        detectedMarkers = marathiMatches + hindiMatches,
                        isCodeSwitched = isMixedDevanagari
                    )
                }
                else -> {
                    // Devanagari text with no explicit grammatical marker (e.g., proper nouns like "यूट्यूब")
                    val fallback = when (lastReliableLanguage) {
                        UserLanguage.MARATHI -> UserLanguage.MARATHI
                        UserLanguage.HINDI -> UserLanguage.HINDI
                        else -> UserLanguage.HINDI
                    }
                    activeConversationLanguage = fallback
                    LanguageDetectionResult(
                        language = fallback,
                        confidence = 0.50f,
                        isReliable = false,
                        rawText = trimmed,
                        detectedMarkers = emptyList()
                    )
                }
            }
        }

        // 2. Latin script detection (English vs Hinglish vs Code-switching)
        val tokens = trimmed.lowercase(Locale.ROOT)
            .split(Regex("[^a-zA-Z0-9]+"))
            .filter { it.isNotBlank() }

        if (tokens.isEmpty()) {
            return LanguageDetectionResult(
                language = activeConversationLanguage,
                confidence = 0.0f,
                isReliable = false,
                rawText = trimmed
            )
        }

        val hinglishMatches = mutableListOf<String>()
        val englishMatches = mutableListOf<String>()

        for (token in tokens) {
            if (token in HINGLISH_MARKERS) {
                hinglishMatches.add(token)
            } else if (token in ENGLISH_MARKERS && token !in AMBIGUOUS_TOKENS) {
                englishMatches.add(token)
            }
        }

        // Check multi-word phrase patterns
        val lowerText = trimmed.lowercase(Locale.ROOT)
        if (Regex("\\b(karo na|kar do|badha do|chalu karo|band karo|khol do|batao na|sunao na)\\b").containsMatchIn(lowerText)) {
            hinglishMatches.add("verb_phrase_hinglish")
        }

        val hasHinglish = hinglishMatches.isNotEmpty()
        val hasEnglish = englishMatches.isNotEmpty()
        val isCodeSwitched = hasHinglish && (hasEnglish || tokens.any { it in AMBIGUOUS_TOKENS })

        // Check for ambiguous or isolated noun/app commands (e.g. "open youtube", "open app", "settings")
        val isAmbiguousCommand = tokens.size <= 2 && tokens.all { it in AMBIGUOUS_TOKENS || it.length <= 3 }

        if (isAmbiguousCommand && !hasHinglish) {
            // Low confidence command: use last reliably detected language if available!
            val fallback = lastReliableLanguage ?: UserLanguage.ENGLISH
            activeConversationLanguage = fallback
            return LanguageDetectionResult(
                language = fallback,
                confidence = 0.45f,
                isReliable = false,
                rawText = trimmed,
                detectedMarkers = tokens.filter { it in AMBIGUOUS_TOKENS }
            )
        }

        return when {
            // Hinglish verbal/grammatical markers present (even when mixed with English words like "please volume badhao")
            hasHinglish -> {
                val score = (0.75f + (hinglishMatches.size * 0.08f)).coerceAtMost(0.99f)
                lastReliableLanguage = UserLanguage.HINGLISH
                activeConversationLanguage = UserLanguage.HINGLISH
                LanguageDetectionResult(
                    language = UserLanguage.HINGLISH,
                    confidence = score,
                    isReliable = score >= MIN_RELIABLE_CONFIDENCE,
                    rawText = trimmed,
                    detectedMarkers = hinglishMatches,
                    isCodeSwitched = isCodeSwitched
                )
            }
            // Standard English with genuine English markers
            hasEnglish -> {
                val score = (0.78f + (englishMatches.size * 0.06f)).coerceAtMost(0.99f)
                lastReliableLanguage = UserLanguage.ENGLISH
                activeConversationLanguage = UserLanguage.ENGLISH
                LanguageDetectionResult(
                    language = UserLanguage.ENGLISH,
                    confidence = score,
                    isReliable = score >= MIN_RELIABLE_CONFIDENCE,
                    rawText = trimmed,
                    detectedMarkers = englishMatches,
                    isCodeSwitched = false
                )
            }
            // Tokens that match neither list
            else -> {
                val fallback = lastReliableLanguage ?: UserLanguage.ENGLISH
                activeConversationLanguage = fallback
                LanguageDetectionResult(
                    language = fallback,
                    confidence = 0.40f,
                    isReliable = false,
                    rawText = trimmed,
                    detectedMarkers = emptyList()
                )
            }
        }
    }

    private fun hasMarathiSuffix(word: String): Boolean {
        return word.endsWith("च्या") || word.endsWith("चे") || word.endsWith("ची") ||
            word.endsWith("चा") || word.endsWith("वरून") || word.endsWith("मध्ये") ||
            word.endsWith("तील") || word.endsWith("हून")
    }

    private fun hasHindiSuffix(word: String): Boolean {
        return word.endsWith("एगा") || word.endsWith("एगी") || word.endsWith("एंगे") ||
            word.endsWith("ऊंगा") || word.endsWith("ऊंगी")
    }
}
