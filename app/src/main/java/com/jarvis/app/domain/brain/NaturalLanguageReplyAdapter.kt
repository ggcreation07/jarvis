package com.jarvis.app.domain.brain

import java.util.Locale

object NaturalLanguageReplyAdapter {

    fun adapt(
        summary: String,
        targetLanguage: UserLanguage,
        userInput: String = "",
        matchSpeakingStyle: Boolean = true
    ): String {
        if (!matchSpeakingStyle) return summary
        val trimmed = summary.trim()
        if (trimmed.isEmpty()) return summary

        val lowerSummary = trimmed.lowercase(Locale.ROOT)
        val lowerInput = userInput.lowercase(Locale.ROOT)

        // Check if the summary is already in the matching Devanagari script for Hindi/Marathi
        val hasDevanagari = trimmed.any { it in '\u0900'..'\u097F' }
        if (hasDevanagari && (targetLanguage == UserLanguage.HINDI || targetLanguage == UserLanguage.MARATHI)) {
            return trimmed
        }

        // 1. Volume Up
        if (lowerSummary.contains("volume increased") || lowerSummary.contains("volume adjustment (up)") ||
            (lowerSummary.contains("volume") && lowerSummary.contains("increased"))
        ) {
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "Haan, volume badha diya."
                UserLanguage.MARATHI -> "हो, आवाज वाढवला."
                UserLanguage.HINDI -> "हाँ, आवाज़ बढ़ा दी गई है।"
                UserLanguage.ENGLISH -> "Turned the volume up."
            }
        }

        // 2. Volume Down
        if (lowerSummary.contains("volume decreased") || lowerSummary.contains("volume adjustment (down)") ||
            (lowerSummary.contains("volume") && lowerSummary.contains("decreased"))
        ) {
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "Haan, volume kam kar diya."
                UserLanguage.MARATHI -> "हो, आवाज कमी केला."
                UserLanguage.HINDI -> "हाँ, आवाज़ कम कर दी गई है।"
                UserLanguage.ENGLISH -> "Turned the volume down."
            }
        }

        // 3. Mute
        if (lowerSummary.contains("muted") && !lowerSummary.contains("unmuted")) {
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "Media volume mute kar diya."
                UserLanguage.MARATHI -> "आवाज म्यूट केला."
                UserLanguage.HINDI -> "आवाज़ म्यूट कर दी गई है।"
                UserLanguage.ENGLISH -> "Media volume muted."
            }
        }

        // 4. Unmute
        if (lowerSummary.contains("unmuted")) {
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "Media volume unmute kar diya."
                UserLanguage.MARATHI -> "आवाज अनम्यूट केला."
                UserLanguage.HINDI -> "आवाज़ अनम्यूट कर दी गई है।"
                UserLanguage.ENGLISH -> "Media volume unmuted."
            }
        }

        // 5. Flashlight On
        if (lowerSummary.contains("flashlight turned on") || lowerSummary.contains("verified flashlight is on") ||
            (lowerSummary.contains("flashlight") && lowerSummary.contains("on"))
        ) {
            val torchWord = if (lowerInput.contains("torch") || lowerInput.contains("टॉर्च")) "Torch" else "Flashlight"
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "$torchWord on kar diya."
                UserLanguage.MARATHI -> "फ्लॅशलाइट चालू केली."
                UserLanguage.HINDI -> "फ्लैशलाइट चालू कर दी गई है।"
                UserLanguage.ENGLISH -> "Flashlight turned on."
            }
        }

        // 6. Flashlight Off
        if (lowerSummary.contains("flashlight turned off") || lowerSummary.contains("verified flashlight is off") ||
            (lowerSummary.contains("flashlight") && lowerSummary.contains("off"))
        ) {
            val torchWord = if (lowerInput.contains("torch") || lowerInput.contains("टॉर्च")) "Torch" else "Flashlight"
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "$torchWord off kar diya."
                UserLanguage.MARATHI -> "फ्लॅशलाइट बंद केली."
                UserLanguage.HINDI -> "फ्लैशलाइट बंद कर दी गई है।"
                UserLanguage.ENGLISH -> "Flashlight turned off."
            }
        }

        // 7. Open Application (e.g. "Opened YouTube.", "Opened Notes in the foreground.", "Opening YouTube")
        val openAppMatch = Regex("^(?:opened|launching|opening)\\s+([^.,(]+)", RegexOption.IGNORE_CASE).find(trimmed)
            ?: Regex("(?:open|launch)\\s+application\\s+([^.,(]+)", RegexOption.IGNORE_CASE).find(trimmed)
        if (openAppMatch != null) {
            val appName = openAppMatch.groupValues[1].trim()
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "$appName khol raha hoon."
                UserLanguage.MARATHI -> "$appName उघडत आहे."
                UserLanguage.HINDI -> "$appName खोल रहा हूँ।"
                UserLanguage.ENGLISH -> "Opening $appName."
            }
        }

        // 8. Navigation Back
        if (lowerSummary.contains("navigated back") || lowerSummary.contains("accessibility navigation: back")) {
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "Peeche ja raha hoon."
                UserLanguage.MARATHI -> "मागे आलो."
                UserLanguage.HINDI -> "पीछे आ गए।"
                UserLanguage.ENGLISH -> "Navigated back."
            }
        }

        // 9. Navigation Home
        if (lowerSummary.contains("navigated home") || lowerSummary.contains("accessibility navigation: home")) {
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "Home screen par aa gaye."
                UserLanguage.MARATHI -> "हो, होम स्क्रीनवर आलो."
                UserLanguage.HINDI -> "होम स्क्रीन पर आ गए।"
                UserLanguage.ENGLISH -> "Navigated home."
            }
        }

        // 10. Navigation Recents
        if (lowerSummary.contains("recent apps") || lowerSummary.contains("recents")) {
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "Recent apps khol diye."
                UserLanguage.MARATHI -> "अलीकडील अ‍ॅप्स उघडले."
                UserLanguage.HINDI -> "हालिया ऐप्स खोल दिए।"
                UserLanguage.ENGLISH -> "Opened recent apps."
            }
        }

        // 11. Scroll UI (e.g. "Scrolled DOWN", "Scrolled UP")
        val scrollMatch = Regex("scrolled\\s+(up|down)", RegexOption.IGNORE_CASE).find(trimmed)
        if (scrollMatch != null) {
            val dir = scrollMatch.groupValues[1].lowercase(Locale.ROOT)
            val isDown = dir == "down"
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> if (isDown) "Neeche scroll kar diya." else "Upar scroll kar diya."
                UserLanguage.MARATHI -> if (isDown) "खाली स्क्रोल केले." else "वर स्क्रोल केले."
                UserLanguage.HINDI -> if (isDown) "नीचे स्क्रॉल कर दिया।" else "ऊपर स्क्रॉल कर दिया।"
                UserLanguage.ENGLISH -> if (isDown) "Scrolled down." else "Scrolled up."
            }
        }

        // 12. Click UI Element (e.g. "Clicked Save", "Clicked Delete Account")
        val clickMatch = Regex("^clicked\\s+(.+?)(?:\\s+in\\s+.*)?$", RegexOption.IGNORE_CASE).find(trimmed)
        if (clickMatch != null) {
            val label = clickMatch.groupValues[1].trim()
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "$label par click kar diya."
                UserLanguage.MARATHI -> "$label वर क्लिक केले."
                UserLanguage.HINDI -> "$label पर क्लिक कर दिया।"
                UserLanguage.ENGLISH -> "Clicked $label."
            }
        }

        // 13. Settings screens
        if (lowerSummary.contains("settings") && (lowerSummary.contains("opened") || lowerSummary.contains("showing"))) {
            val screenName = trimmed.removePrefix("Opened ").removePrefix("Showing ").trimEnd('.')
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "$screenName khol diya."
                UserLanguage.MARATHI -> "$screenName उघडली."
                UserLanguage.HINDI -> "$screenName खोल दी गई है।"
                UserLanguage.ENGLISH -> "Opened $screenName."
            }
        }

        // If no translation pattern matches, keep original summary
        return trimmed
    }
}
