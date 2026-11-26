package com.jarvis.app.domain.brain

sealed interface DirectActionMatch {
    data class ToolCall(val toolName: String, val args: Map<String, String>) : DirectActionMatch
    data class LocalAnswer(val text: String, val toolName: String) : DirectActionMatch
}

object DirectCommandMatcher {

    private val commonDevanagariAppAliases = mapOf(
        "यूट्यूब" to "YouTube",
        "स्पॉटिफाई" to "Spotify",
        "स्पॉटिफाय" to "Spotify",
        "व्हाट्सएप" to "WhatsApp",
        "व्हाट्सऐप" to "WhatsApp",
        "व्हॉट्सअ‍ॅप" to "WhatsApp",
        "व्हॉट्सअॅप" to "WhatsApp",
        "क्रोम" to "Chrome",
        "कैमरा" to "Camera",
        "कॅमेरा" to "Camera",
        "कैलेंडर" to "Calendar",
        "कॅलेंडर" to "Calendar",
        "मैप्स" to "Maps",
        "मॅप्स" to "Maps",
        "गैलरी" to "Gallery",
        "गॅलरी" to "Gallery",
        "जीमेल" to "Gmail",
        "क्लॉक" to "Clock",
        "घड़ी" to "Clock",
        "कैलकुलेटर" to "Calculator",
        "कॅल्क्युलेटर" to "Calculator",
        "फोन" to "Phone",
        "फ़ोन" to "Phone",
        "मैसेजेस" to "Messages",
        "मेसेजेस" to "Messages",
        "नोट्स" to "Notes"
    )

    private val reservedPanelWords = setOf(
        "chat", "settings", "memory", "skills", "capabilities",
        "customize", "power", "remote", "conversation", "recents",
        "notifications", "quick settings"
    )

    internal fun normalizeAppTarget(raw: String): String {
        val cleaned = raw.trim().trim('"', '\'').trim()
        return commonDevanagariAppAliases[cleaned] ?: cleaned
    }

    internal fun looksMultiClause(text: String): Boolean {
        val outsideQuotes = text.replace(Regex("\"[^\"]*\"|'[^']*'"), " ")
        return Regex(
            "(?:;|\\band\\b|\\bthen\\b|\\baur\\b|\\bphir\\b|\\bऔर\\b|\\bफिर\\b|\\bआणि\\b|\\bनंतर\\b|,)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(outsideQuotes)
    }

    fun match(rawInput: String): DirectActionMatch? {
        val text = rawInput.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || text.length > 90) return null
        val cleaned = text.trimEnd('?', '.', '!', '।').trim()
        if (cleaned.isEmpty()) return null
        val lower = cleaned.lowercase()

        // Time & date
        if (Regex("^(what('s| is) the )?(current )?time( right now)?$").matches(lower) ||
            Regex("^(?:kitne baje hain|time kya hua hai|samay kya hai|अभी क्या समय है|कितने बजे हैं|वेळ काय झाली आहे|किती वाजले)$").matches(lower)
        ) {
            return DirectActionMatch.ToolCall("get_time", emptyMap())
        }
        if (Regex("^(what )?(day|date)( is it)?( today)?$").matches(lower) ||
            Regex("^(?:aaj kaun sa din hai|aaj kya date hai|आज कौन सा दिन है|आज की तारीख क्या है|आज कोणता वार आहे|आजची तारीख काय)$").matches(lower)
        ) {
            return DirectActionMatch.ToolCall("get_day", emptyMap())
        }

        // Flashlight (English, Hinglish, Hindi, Marathi)
        if (Regex("^(turn |switch )?on (the )?(flashlight|torch)$").matches(lower) ||
            Regex("^(?:flashlight|torch) (?:on|chalu|start|jalao)(?: (?:karo|kar|do))?$").matches(lower) ||
            Regex("^(?:chalu|on|jalao) (?:karo )?(?:the )?(?:flashlight|torch)$").matches(lower) ||
            Regex("^(?:फ्लैशलाइट|फ्लॅशलाइट|टॉर्च) (?:ऑन|चालू|शुरू|सुरू) (?:करो|कर|करें|करा|दीजिए|दो)$").matches(cleaned) ||
            Regex("^(?:फ्लैशलाइट|फ्लॅशलाइट|टॉर्च) (?:जलाओ|जला दो)$").matches(cleaned)
        ) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "flashlight_on"))
        }
        if (Regex("^(turn |switch )?off (the )?(flashlight|torch)$").matches(lower) ||
            Regex("^(?:flashlight|torch) (?:off|band|bujhao)(?: (?:karo|kar|do))?$").matches(lower) ||
            Regex("^(?:band|off|bujhao) (?:karo )?(?:the )?(?:flashlight|torch)$").matches(lower) ||
            Regex("^(?:फ्लैशलाइट|फ्लॅशलाइट|टॉर्च) (?:ऑफ|बंद) (?:करो|कर|करें|करा|दीजिए|दो)$").matches(cleaned) ||
            Regex("^(?:फ्लैशलाइट|फ्लॅशलाइट|टॉर्च) (?:बुझाओ|बुझा दो)$").matches(cleaned)
        ) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "flashlight_off"))
        }

        // Volume / mute (English, Hinglish, Hindi, Marathi)
        if (Regex("^(turn |volume )?(up|louder|increase volume)$").matches(lower) ||
            Regex("^turn volume up$").matches(lower) ||
            Regex("^(?:awaaz|awaz|volume|sound) (?:badhao|badha do|zyada karo|tez karo|up karo|up|badha)$").matches(lower) ||
            Regex("^(?:आवाज़|आवाज|वॉल्यूम|व्हॉल्यूम|साउंड) (?:बढ़ाओ|बढ़ा दो|ज्यादा करो|तेज़ करो|तेज करो|बढ़ाएं|वाढव|वाढवा|जास्त कर|जास्त करा)$").matches(cleaned)
        ) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "volume_up"))
        }
        if (Regex("^(turn |volume )?(down|quieter|lower volume|decrease volume)$").matches(lower) ||
            Regex("^turn volume down$").matches(lower) ||
            Regex("^(?:awaaz|awaz|volume|sound) (?:kam karo|kam kar|ghatao|dheemi karo|dhimi karo|down karo|down|kam)$").matches(lower) ||
            Regex("^(?:आवाज़|आवाज|वॉल्यूम|व्हॉल्यूम|साउंड) (?:कम करो|कम कर|कम करें|धीमी करो|घटाओ|कमी कर|कमी करा)$").matches(cleaned)
        ) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "volume_down"))
        }
        if (Regex("^mute( media| volume)?$").matches(lower) ||
            Regex("^(?:(?:phone|media|volume|awaaz|awaz) )?(?:mute karo|mute kar|awaaz band karo|awaz band karo)$").matches(lower) ||
            Regex("^(?:(?:आवाज़|आवाज|वॉल्यूम|व्हॉल्यूम|फ़ोन|फोन) )?(?:म्यूट करो|म्यूट करें|म्यूट कर|म्यूट करा|(?:आवाज़|आवाज) बंद (?:करो|कर|करा))$").matches(cleaned)
        ) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "mute"))
        }
        if (Regex("^unmute( media| volume)?$").matches(lower) ||
            Regex("^(?:(?:phone|media|volume|awaaz|awaz) )?(?:unmute karo|unmute kar|awaaz chalu karo|awaz chalu karo)$").matches(lower) ||
            Regex("^(?:(?:आवाज़|आवाज|वॉल्यूम|व्हॉल्यूम|फ़ोन|फोन) )?(?:अनम्यूट करो|अनम्यूट करें|अनम्यूट कर|अनम्यूट करा|(?:आवाज़|आवाज) (?:चालू|सुरू) (?:करो|कर|करा))$").matches(cleaned)
        ) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "unmute"))
        }
        if (Regex("^(turn |switch )?on (do not disturb|dnd)$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "dnd_on"))
        }
        if (Regex("^(turn |switch )?off (do not disturb|dnd)$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "dnd_off"))
        }

        // Media transport
        if (Regex("^(pause|resume|play)( the)?( music| media)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "media_play_pause"))
        }
        if (Regex("^(next|skip)( song| track)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "media_next"))
        }
        if (Regex("^(previous|prev)( song| track)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("device_control", mapOf("action" to "media_prev"))
        }

        // Android Settings screens (English, Hinglish, Hindi, Marathi)
        matchAndroidSettings(cleaned, lower)?.let { return it }

        // HUD / Interface direct commands
        if (Regex("^(switch to |enable |use )?iron ?man( mode| look| theme)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("customize_screen", mapOf("action" to "set_look", "value" to "ironman"))
        }
        if (Regex("^(switch to |enable |use )?modern( mode| look| theme)?$").matches(lower)) {
            return DirectActionMatch.ToolCall("customize_screen", mapOf("action" to "set_look", "value" to "modern"))
        }
        if (Regex("^open (settings|memory|skills|capabilities|customize|power|remote)$").matches(lower)) {
            val target = lower.removePrefix("open ").trim()
            return DirectActionMatch.ToolCall("control_interface", mapOf("action" to "open_$target"))
        }
        if (Regex("^(clear|reset) (the )?chat$").matches(lower)) {
            return DirectActionMatch.ToolCall("control_interface", mapOf("action" to "clear_chat"))
        }
        if (Regex("^(reset) (the )?(hud|theme|screen|customization)$").matches(lower)) {
            return DirectActionMatch.ToolCall("customize_screen", mapOf("action" to "reset"))
        }

        // Direct device action commands (protected)
        val uninstallApp = Regex("^uninstall (?:app )?([a-z0-9 ._-]{2,35})$", RegexOption.IGNORE_CASE).find(cleaned)
        if (uninstallApp != null) {
            val appName = uninstallApp.groupValues[1].trim()
            return DirectActionMatch.ToolCall("uninstall_app", mapOf("package" to appName))
        }
        val deleteFile = Regex("^delete file ([a-z0-9 ._/-]{2,50})$", RegexOption.IGNORE_CASE).find(cleaned)
        if (deleteFile != null) {
            val fileName = deleteFile.groupValues[1].trim()
            return DirectActionMatch.ToolCall("delete_file", mapOf("path" to fileName))
        }
        val sendMsg = Regex("^send message to ([a-z0-9 +_-]{2,30}) saying (.+)$", RegexOption.IGNORE_CASE).find(cleaned)
        if (sendMsg != null) {
            val to = sendMsg.groupValues[1].trim()
            val body = sendMsg.groupValues[2].trim()
            return DirectActionMatch.ToolCall("send_message", mapOf("to" to to, "body" to body))
        }

        // Quick Timer: "set a 5 minute timer" / "timer 30 seconds"
        val timerMin = Regex("^(?:set )?(?:a )?(\\d+)\\s*(?:min|minute|minutes)\\s*timer$").find(lower)
        if (timerMin != null) {
            val secs = (timerMin.groupValues[1].toIntOrNull() ?: 1) * 60
            return DirectActionMatch.ToolCall("set_timer", mapOf("seconds" to secs.toString()))
        }
        val timerSec = Regex("^(?:set )?(?:a )?(\\d+)\\s*(?:sec|second|seconds)\\s*timer$").find(lower)
        if (timerSec != null) {
            val secs = timerSec.groupValues[1].toIntOrNull() ?: 10
            return DirectActionMatch.ToolCall("set_timer", mapOf("seconds" to secs.toString()))
        }

        // Global Android navigation (Accessibility Service) - must precede generic openApp rule
        matchAccessibilityNavigation(cleaned, lower)?.let { return it }

        // Skip multi-clause prompts so UiTaskPlanner handles sequential UI steps cleanly
        if (looksMultiClause(cleaned)) return null

        // Quick Open App: English, Hinglish, Hindi, Marathi
        matchOpenApp(cleaned)?.let { appTarget ->
            return DirectActionMatch.ToolCall("open_application", mapOf("name" to appTarget))
        }

        return null
    }

    private fun matchAndroidSettings(cleaned: String, lower: String): DirectActionMatch.ToolCall? {
        val action = when {
            Regex("^(?:open |show |go to |navigate to )?(?:the )?wi-?fi settings$").matches(lower) ||
                Regex("^wi-?fi settings (?:kholo|open karo|dikhao)$").matches(lower) ||
                Regex("^(?:वाईफाई|वायफाय|वाई-फाई) (?:सेटिंग्स|सेटिंग्ज) (?:खोलो|उघडा|उघड|दिखाओ)$").matches(cleaned) -> "wifi_settings"

            Regex("^(?:open |show |go to |navigate to )?(?:the )?bluetooth settings$").matches(lower) ||
                Regex("^bluetooth settings (?:kholo|open karo|dikhao)$").matches(lower) ||
                Regex("^ब्लूटूथ (?:सेटिंग्स|सेटिंग्ज) (?:खोलो|उघडा|उघड|दिखाओ)$").matches(cleaned) -> "bluetooth_settings"

            Regex("^(?:open |show |go to |navigate to )?(?:the )?accessibility settings$").matches(lower) ||
                Regex("^accessibility settings (?:kholo|open karo|dikhao)$").matches(lower) ||
                Regex("^(?:एक्सेसिबिलिटी|अ‍ॅक्सेसिबिलिटी|अॅक्सेसिबिलिटी) (?:सेटिंग्स|सेटिंग्ज) (?:खोलो|उघडा|उघड)$").matches(cleaned) -> "accessibility_settings"

            Regex("^(?:open |show |go to |navigate to )?(?:the )?notification (?:access|listener) settings$").matches(lower) ||
                Regex("^notification access settings (?:kholo|open karo)$").matches(lower) -> "notification_listener_settings"

            Regex("^(?:open |show |go to |navigate to )?(?:the )?(?:do not disturb|dnd)(?: access)? settings$").matches(lower) -> "dnd_settings"

            Regex("^(?:open |show |go to |navigate to )?(?:the )?(?:display|brightness) settings$").matches(lower) ||
                Regex("^(?:display|brightness) settings (?:kholo|open karo)$").matches(lower) ||
                Regex("^(?:डिस्प्ले|ब्राइटनेस) (?:सेटिंग्स|सेटिंग्ज) (?:खोलो|उघडा|उघड)$").matches(cleaned) -> "display_settings"

            Regex("^(?:open |show |go to |navigate to )?(?:the )?(?:sound|audio) settings$").matches(lower) ||
                Regex("^(?:sound|audio) settings (?:kholo|open karo)$").matches(lower) ||
                Regex("^साउंड (?:सेटिंग्स|सेटिंग्ज) (?:खोलो|उघडा|उघड)$").matches(cleaned) -> "sound_settings"

            Regex("^(?:open |show |go to |navigate to )?(?:the )?battery settings$").matches(lower) ||
                Regex("^battery settings (?:kholo|open karo)$").matches(lower) ||
                Regex("^(?:बैटरी|बॅटरी) (?:सेटिंग्स|सेटिंग्ज) (?:खोलो|उघडा|उघड)$").matches(cleaned) -> "battery_settings"

            Regex("^(?:open |show |go to |navigate to )?(?:the )?(?:location|gps) settings$").matches(lower) ||
                Regex("^(?:location|gps) settings (?:kholo|open karo)$").matches(lower) ||
                Regex("^लोकेशन (?:सेटिंग्स|सेटिंग्ज) (?:खोलो|उघडा|उघड)$").matches(cleaned) -> "location_settings"

            Regex("^(?:open |show |go to |navigate to )?(?:the )?(?:apps?|application|installed apps) settings$").matches(lower) ||
                Regex("^(?:app|apps) settings (?:kholo|open karo)$").matches(lower) ||
                Regex("^(?:ऐप|अ‍ॅप) (?:सेटिंग्स|सेटिंग्ज) (?:खोलो|उघडा|उघड)$").matches(cleaned) -> "apps_settings"

            Regex("^(?:open |show |go to |navigate to )?(?:the )?default assistant settings$").matches(lower) -> "default_assistant_settings"

            Regex("^(?:open |show |go to |navigate to )?(?:the )?(?:android|phone|system|device) settings$").matches(lower) ||
                Regex("^(?:android|phone|system|mobile) settings (?:kholo|open karo)$").matches(lower) ||
                Regex("^(?:फ़ोन|फोन|मोबाइल|सिस्टम) (?:सेटिंग्स|सेटिंग्ज) (?:खोलो|उघडा|उघड)$").matches(cleaned) -> "system_settings"

            else -> null
        } ?: return null

        return DirectActionMatch.ToolCall("device_control", mapOf("action" to action))
    }

    private fun matchAccessibilityNavigation(cleaned: String, lower: String): DirectActionMatch.ToolCall? {
        return when {
            Regex("^(?:(?:go|navigate|press|tap)\\s+)?back(?:\\s+button)?$").matches(lower) ||
                Regex("^(?:piche|pichhe|pichhle|peeche|maage|mage|wapas|vaapas|back) (?:jao|ja|chalo|karo|kar|aao)$").matches(lower) ||
                Regex("^(?:पीछे|वापस|बैक) (?:जाओ|चलो|करो|जाएं)$").matches(cleaned) ||
                Regex("^(?:मागे|पाठीमागे|बॅक) (?:जा|जावा|करा|कर)$").matches(cleaned) ->
                DirectActionMatch.ToolCall("accessibility_navigate", mapOf("action" to "back"))

            Regex("^(?:go(?:\\s+to(?:\\s+the)?)?\\s+home(?:\\s+screen)?|return\\s+home|(?:press|tap)\\s+home(?:\\s+button)?|home\\s+screen)$").matches(lower) ||
                Regex("^(?:home|home screen)(?: (?:pe|par))? (?:jao|chalo|kholo)$").matches(lower) ||
                Regex("^(?:होम|होम स्क्रीन)(?: पर)? (?:जाओ|चलो|खोलो)$").matches(cleaned) ||
                Regex("^होम स्क्रीन$").matches(cleaned) ||
                Regex("^(?:होम|होम स्क्रीन)(?:वर)? (?:जा|जावा|उघडा)$").matches(cleaned) ||
                Regex("^घरी जा$").matches(cleaned) ->
                DirectActionMatch.ToolCall("accessibility_navigate", mapOf("action" to "home"))

            Regex("^(?:(?:open|show|view)\\s+(?:the\\s+)?(?:recents|recent\\s+apps|app\\s+switcher)|(?:press|tap)\\s+recents(?:\\s+button)?)$").matches(lower) ||
                Regex("^(?:recents|recent apps) (?:kholo|dikhao|dekho|open karo)$").matches(lower) ||
                Regex("^(?:रीसेंट|रीसेंट ऐप्स|हाल के ऐप्स) (?:खोलो|दिखाओ)$").matches(cleaned) ||
                Regex("^(?:अलीकडचे अ‍ॅप्स|अलीकडील अ‍ॅप्स|रीसेंट अ‍ॅप्स) (?:उघडा|दाखवा|उघड|दाखव)$").matches(cleaned) ->
                DirectActionMatch.ToolCall("accessibility_navigate", mapOf("action" to "recents"))

            Regex("^(?:(?:open|show|pull\\s+down|expand)\\s+(?:the\\s+)?(?:notifications?|notification\\s+(?:panel|shade|drawer|bar)))$").matches(lower) ||
                Regex("^notifications?(?: (?:panel|drawer|shade))? (?:kholo|dikhao|open karo)$").matches(lower) ||
                Regex("^(?:नोटिफिकेशन|नोटिफिकेशन्स|सूचनाएं?)(?: पैनल)? (?:खोलो|दिखाओ)$").matches(cleaned) ||
                Regex("^(?:नोटिफिकेशन|नोटिफिकेशन्स)(?: पॅनेल)? (?:उघडा|दाखवा|उघड|दाखव)$").matches(cleaned) ->
                DirectActionMatch.ToolCall("accessibility_navigate", mapOf("action" to "notifications"))

            Regex("^(?:(?:open|show|pull\\s+down|expand)\\s+(?:the\\s+)?quick\\s+settings(?:\\s+(?:panel|shade|drawer))?)$").matches(lower) ||
                Regex("^quick settings(?: (?:panel|drawer))? (?:kholo|dikhao|open karo)$").matches(lower) ||
                Regex("^क्विक सेटिंग्स (?:खोलो|दिखाओ)$").matches(cleaned) ||
                Regex("^क्विक सेटिंग्ज (?:उघडा|दाखवा|उघड|दाखव)$").matches(cleaned) ->
                DirectActionMatch.ToolCall("accessibility_navigate", mapOf("action" to "quick_settings"))

            else -> null
        }
    }

    private fun matchOpenApp(cleaned: String): String? {
        val candidate = Regex("^(?:open|launch|start) ([a-z0-9 ._-]{2,28})$", RegexOption.IGNORE_CASE)
            .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(.{2,28}) (?:app )?(?:kholo|khol do|chalao|open karo|launch karo)$", RegexOption.IGNORE_CASE)
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(?:kholo|chalao) ([a-z0-9 ._-]{2,28})$", RegexOption.IGNORE_CASE)
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(.{2,35}) (?:ऐप )?(?:खोलो|खोल दो|चालू करो)$")
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^खोलो (.{2,35})$")
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(.{2,35}) (?:अ‍ॅप |अॅप )?(?:उघड|उघडा|सुरू कर|सुरू करा|चालू करा)$")
                .find(cleaned)?.groupValues?.get(1)
            ?: Regex("^(?:उघड|उघडा) (.{2,35})$")
                .find(cleaned)?.groupValues?.get(1)
            ?: return null

        val normalized = normalizeAppTarget(candidate)
        val lowerNorm = normalized.lowercase()
        if (lowerNorm.isBlank() ||
            lowerNorm in reservedPanelWords ||
            lowerNorm.endsWith(" settings") ||
            lowerNorm.startsWith("uninstall ") ||
            lowerNorm.startsWith("delete ") ||
            lowerNorm.contains("screen")
        ) {
            return null
        }
        return normalized
    }
}
