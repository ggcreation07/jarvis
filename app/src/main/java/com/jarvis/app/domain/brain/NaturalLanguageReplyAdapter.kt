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

        // 14. Notification reading states
        if (lowerSummary.startsWith("no unread notifications") || lowerSummary.startsWith("no matching notifications")) {
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "Abhi koi nayi notification nahi hai."
                UserLanguage.MARATHI -> "सध्या कोणतेही नवीन नोटिफिकेशन नाही."
                UserLanguage.HINDI -> "अभी कोई नया नोटिफिकेशन नहीं है।"
                UserLanguage.ENGLISH -> trimmed
            }
        }
        if (lowerSummary.startsWith("notification access isn't enabled") ||
            lowerSummary.contains("notification listener is enabled in android settings, but not connected")
        ) {
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "Notification access abhi on nahi hai. Please Android Settings mein JARVIS Notification Reader enable karein."
                UserLanguage.MARATHI -> "नोटिफिकेशन अ‍ॅक्सेस अजून चालू नाही. कृपया Android सेटिंग्जमध्ये JARVIS Notification Reader चालू करा."
                UserLanguage.HINDI -> "नोटिफिकेशन एक्सेस अभी चालू नहीं है। कृपया Android सेटिंग्स में JARVIS Notification Reader चालू करें।"
                UserLanguage.ENGLISH -> trimmed
            }
        }
        if (lowerSummary.startsWith("notification reading is currently disabled")) {
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "JARVIS Settings mein notification reading band hai. Settings mein jaakar ise chalu karein."
                UserLanguage.MARATHI -> "JARVIS सेटिंग्जमध्ये नोटिफिकेशन वाचन बंद आहे. कृपया सेटिंग्जमधून ते चालू करा."
                UserLanguage.HINDI -> "JARVIS सेटिंग्स में नोटिफिकेशन पढ़ना बंद है। कृपया सेटिंग्स में इसे चालू करें।"
                UserLanguage.ENGLISH -> trimmed
            }
        }
        if (lowerSummary.startsWith("latest notification is from ")) {
            val detail = trimmed.removePrefix("Latest notification is from ")
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "Latest notification: $detail"
                UserLanguage.MARATHI -> "नवीन नोटिफिकेशन: $detail"
                UserLanguage.HINDI -> "नवीनतम नोटिफिकेशन: $detail"
                UserLanguage.ENGLISH -> trimmed
            }
        }
        if (lowerSummary.startsWith("active notifications:\n")) {
            val body = trimmed.removePrefix("Active notifications:\n")
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "Yeh rahe active notifications:\n$body"
                UserLanguage.MARATHI -> "सध्याची नोटिफिकेशन्स:\n$body"
                UserLanguage.HINDI -> "ये रहे सक्रिय नोटिफिकेशन:\n$body"
                UserLanguage.ENGLISH -> trimmed
            }
        }

        // 15. Empty / non-accessible screen text limitation
        if (lowerSummary.contains("exposes no accessible text")) {
            return when (targetLanguage) {
                UserLanguage.HINGLISH -> "Is screen par koi padhne layak text nahi mila.\n$trimmed"
                UserLanguage.MARATHI -> "या स्क्रीनवर वाचण्याजोगा कोणताही मजकूर उपलब्ध नाही.\n$trimmed"
                UserLanguage.HINDI -> "इस स्क्रीन पर पढ़ने योग्य कोई टेक्स्ट उपलब्ध नहीं है।\n$trimmed"
                UserLanguage.ENGLISH -> trimmed
            }
        }

        // If no translation pattern matches, keep original summary
        return trimmed
    }
}
