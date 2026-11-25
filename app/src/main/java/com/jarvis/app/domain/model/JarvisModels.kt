package com.jarvis.app.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

enum class HudStatus(val label: String) {
    IDLE("Standing by"),
    LISTENING("Listening…"),
    THINKING("Thinking…"),
    WORKING("Working on it…"),
    SPEAKING("Speaking")
}

enum class OverlayType {
    NONE,
    CHAT,
    MEMORY,
    SKILLS,
    CAPABILITIES,
    HANDS_FREE,
    CUSTOMIZE,
    SETTINGS,
    REMOTE_PC,
    REMOTE_DESKTOP,
    DEVICE_POWER,
    COMMAND_PALETTE,
    TASKS_ACTIVITY,
    ROUTINES,
    QUICK_START
}

@Immutable
@Serializable
data class AccentPreset(
    val key: String,
    val label: String,
    val mainHex: String,
    val secondHex: String
)

val DEFAULT_ACCENT_PRESETS = listOf(
    AccentPreset("cyan", "Cyan", "#00E5FF", "#3B82F6"),
    AccentPreset("iron", "Reactor", "#00E5FF", "#FFAA00"),
    AccentPreset("blue", "Cobalt", "#3B82F6", "#60A5FA"),
    AccentPreset("amber", "Amber", "#F59E0B", "#EF4444"),
    AccentPreset("gold", "Stark Gold", "#FFCC33", "#FF6B00"),
    AccentPreset("emerald", "Emerald", "#10B981", "#06B6D4"),
    AccentPreset("crimson", "Crimson", "#EF4444", "#F97316"),
    AccentPreset("violet", "Violet", "#8B5CF6", "#EC4899"),
    AccentPreset("ice", "Ice", "#93C5FD", "#E0F2FE"),
    AccentPreset("lime", "Neon", "#84CC16", "#22D3EE"),
    AccentPreset("rose", "Rose", "#F43F5E", "#A855F7")
)

val ALL_CARD_IDS = listOf(
    "status",
    "routines",
    "device",
    "battery",
    "agenda",
    "weather",
    "network",
    "log"
)

val DEFAULT_TOP_BAR_BUTTONS = listOf(
    "mute",
    "tasks",
    "remote",
    "search",
    "customize",
    "settings",
    "power"
)

@Immutable
@Serializable
data class CustomLookPreset(
    val name: String,
    val look: String,
    val accentKey: String,
    val mainHex: String,
    val secondHex: String,
    val fontStyle: String,
    val cardStyle: String,
    val centerpiece: String,
    val gaugeStyle: String,
    val cornerRadiusDp: Int,
    val glowStrength: Int,
    val backgroundStyle: String,
    val density: String
)

@Immutable
@Serializable
data class ScreenConfig(
    val look: String = "modern", // "modern" | "ironman"
    val accentKey: String = "cyan",
    val mainHex: String = "#00E5FF",
    val secondHex: String = "#3B82F6",
    val fontStyle: String = "clean", // "clean" | "hud"
    val cardStyle: String = "clean", // "clean" | "hud"
    val centerpiece: String = "orb", // "orb" | "reactor"
    val gaugeStyle: String = "bars", // "bars" | "rings"
    val cornerRadiusDp: Int = 16,
    val glowStrength: Int = 65,
    val backgroundStyle: String = "grid", // "grid" | "glow" | "aurora" | "minimal" | "picture"
    val backgroundPictureUri: String = "",
    val density: String = "comfortable", // "comfortable" | "compact"
    val cardOrder: List<String> = ALL_CARD_IDS,
    val hiddenCards: Set<String> = emptySet(),
    val collapsedCards: Set<String> = setOf("device", "battery", "network", "log"),
    val topBarButtons: List<String> = DEFAULT_TOP_BAR_BUTTONS,
    val customPresets: List<CustomLookPreset> = emptyList(),
    val quickStartCompleted: Boolean = false
)

@Immutable
@Serializable
data class BrainConfig(
    val activeProvider: String = "auto", // "auto" | "firebase_ai" | "groq" | "gemini" | "openrouter" | "nvidia" | "mistral" | "openai" | "anthropic" | "xai"
    val activeModel: String = "gemini-3.8-flash",
    val providerModels: Map<String, String> = emptyMap(),
    val conversationMode: Boolean = false,
    val agentsMode: Boolean = false,
    val selectedAgentId: String = "auto",
    val voiceTtsEnabled: Boolean = true,
    val muted: Boolean = false,
    val voiceBargeInEnabled: Boolean = false,
    val wakeSensitivity: Int = 60,
    val calendarSyncEnabled: Boolean = false,
    val nightlyDigestEnabled: Boolean = true,
    val pinnedLocation: String = "",
    val grantedFolderUri: String = "",
    val grantedFolderName: String = "",
    val pcHost: String = "",
    val pcPort: Int = 8765,
    val pcName: String = "",
    val pcMacAddress: String = "",
    val pcPaired: Boolean = false,
    val tvHost: String = "",
    val tvPort: Int = 5555,
    val tvName: String = "",
    val tvMacAddress: String = "",
    val tvPaired: Boolean = false
)

@Immutable
data class ToolActivityItem(
    val id: String,
    val toolName: String,
    val label: String,
    val status: String, // "queued" | "running" | "succeeded" | "failed" | "unverified"
    val summary: String = "",
    val durationMs: Long = 0L
)

@Immutable
data class ChatMessageItem(
    val id: Long = 0L,
    val threadId: String = "current",
    val role: String, // "user" | "assistant" | "system"
    val content: String,
    val agentId: String = "auto",
    val timestampMs: Long = System.currentTimeMillis(),
    val toolActivities: List<ToolActivityItem> = emptyList(),
    val attachmentLabel: String = "",
    val generatedImageKey: String = "",
    val qrText: String = "",
    val isStreaming: Boolean = false,
    val isError: Boolean = false
)

@Immutable
data class ArchivedThreadSummary(
    val id: String,
    val title: String,
    val preview: String,
    val messageCount: Int,
    val updatedAtMs: Long
)

@Immutable
data class FactItem(
    val id: Long = 0L,
    val text: String,
    val category: String = "personal",
    val createdAtMs: Long = System.currentTimeMillis()
)

@Immutable
data class RoutineItem(
    val id: Long = 0L,
    val name: String,
    val prompt: String,
    val triggerTime: String = "", // "08:00" or "home" or ""
    val days: String = "daily", // "daily" | "weekdays" | "weekends" | "mon,wed,fri"
    val enabled: Boolean = true,
    val lastRunDate: String = ""
)

@Immutable
data class PlaybookItem(
    val id: Long = 0L,
    val name: String,
    val steps: String,
    val triggers: String = "",
    val status: String = "draft", // "draft" | "candidate" | "promoted" | "disabled"
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Immutable
data class AgendaItem(
    val id: Long = 0L,
    val dayKey: String, // YYYY-MM-DD
    val timeLabel: String,
    val task: String,
    val source: String = "local", // "local" | "calendar"
    val completed: Boolean = false
)

@Immutable
data class ActionLogItem(
    val id: Long = 0L,
    val toolName: String,
    val summary: String,
    val ok: Boolean,
    val timestampMs: Long = System.currentTimeMillis()
)

@Immutable
data class TaskRecordItem(
    val id: String,
    val objective: String,
    val state: String, // "running" | "succeeded" | "suspended" | "cancelled"
    val agentId: String,
    val stepsCount: Int,
    val summary: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Immutable
data class WeatherSnapshot(
    val place: String = "",
    val temperatureC: Int? = null,
    val feelsLikeC: Int? = null,
    val condition: String = "Unknown",
    val humidityPercent: Int? = null,
    val windKmh: Int? = null,
    val hourlySummary: List<Pair<String, String>> = emptyList(),
    val forecastDays: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
    val error: String? = null
)

@Immutable
data class NetworkSnapshot(
    val connected: Boolean = true,
    val transport: String = "Unknown", // "Wi-Fi" | "Cellular" | "Ethernet" | "Offline"
    val localIp: String = "—",
    val publicIp: String = "—",
    val cityRegion: String = "—"
)

@Immutable
data class TelemetrySnapshot(
    val ramUsedMb: Long = 0L,
    val ramTotalMb: Long = 1L,
    val ramPercent: Int = 0,
    val storageUsedGb: Float = 0f,
    val storageTotalGb: Float = 1f,
    val storagePercent: Int = 0,
    val batteryPercent: Int = 0,
    val isCharging: Boolean = false,
    val batteryTempC: Float = 0f,
    val batteryHealth: String = "Good",
    val network: NetworkSnapshot = NetworkSnapshot()
)

enum class HandsFreeActivity(
    val id: String,
    val title: String,
    val description: String
) {
    VOICE("voice", "Voice & Assist", "Quick palm pause, fist stop, or thumbs feedback."),
    MEDIA("media", "Music & Media", "Open palm play/pause, peace next track, point volume."),
    READING("reading", "Reading", "Paste or bookmark text and advance pages hands-free."),
    STEPS("cooking", "Follow Steps", "Step-by-step guide for recipes or checklists with timer."),
    PHOTO("photo", "Photo Timer", "Show peace sign or tap capture for timed camera shot."),
    PHONE("phone", "Phone Control", "Navigate, pause, or confirm quick phone actions.")
}

enum class HandPose(val rawLabel: String, val displayName: String, val hint: String) {
    OPEN_PALM("Open_Palm", "Open Palm", "Play / Pause / Next"),
    PEACE("Victory", "Peace", "Next Track / Capture"),
    POINT_UP("Pointing_Up", "Point Up", "Scroll / Highlight"),
    THUMBS_UP("Thumb_Up", "Thumbs Up", "Good / Confirm"),
    THUMBS_DOWN("Thumb_Down", "Thumbs Down", "Skip / Previous"),
    LOVE("ILoveYou", "Love Sign", "Bookmark / Favorite"),
    FIST("Closed_Fist", "Closed Fist", "Stop Immediately"),
    UNKNOWN("None", "Neutral", "Show a pose clearly")
}

@Immutable
data class AgentDefinition(
    val id: String,
    val handle: String,
    val name: String,
    val tagline: String,
    val systemHint: String,
    val requiresPc: Boolean = false
)

val JARVIS_AGENTS = listOf(
    AgentDefinition(
        id = "auto",
        handle = "@auto",
        name = "Auto",
        tagline = "Picks the right tools automatically",
        systemHint = "Route intelligently across phone actions, research, memory, scheduling, and content."
    ),
    AgentDefinition(
        id = "phone",
        handle = "@phone",
        name = "Phone",
        tagline = "Apps, toggles, timers, calls, notifications",
        systemHint = "Focus on Android device controls, alarms, timers, apps, calls, messages, and notifications."
    ),
    AgentDefinition(
        id = "research",
        handle = "@research",
        name = "Research",
        tagline = "Weather, news, web search, places & directions",
        systemHint = "Prioritize verified facts, web lookup, weather, headlines, places, and structured summaries."
    ),
    AgentDefinition(
        id = "files",
        handle = "@files",
        name = "Files",
        tagline = "Granted folders, saved notes & clipboard",
        systemHint = "Inspect user-granted Storage Access Framework documents, clipboard text, and saved outputs."
    ),
    AgentDefinition(
        id = "scheduler",
        handle = "@scheduler",
        name = "Scheduler",
        tagline = "Agenda, routines, reminders & calendar",
        systemHint = "Manage today's agenda, recurring routines, reminders, and Android Calendar events."
    ),
    AgentDefinition(
        id = "content",
        handle = "@content",
        name = "Content",
        tagline = "Charts, tables, flowcharts, images & QR codes",
        systemHint = "Produce rich structured [CHART], [TABLE], [FLOWCHART], [SCHEDULE] blocks, QR codes, or AI images."
    ),
    AgentDefinition(
        id = "desktop",
        handle = "@desktop",
        name = "Desktop",
        tagline = "Control paired Windows PC apps & screen",
        systemHint = "Forward desktop actions and screen commands to the paired PC.",
        requiresPc = true
    ),
    AgentDefinition(
        id = "browser",
        handle = "@browser",
        name = "Browser",
        tagline = "Run browser tasks on paired PC",
        systemHint = "Coordinate browser automation tasks on the paired PC.",
        requiresPc = true
    )
)

@Immutable
data class ProviderOption(
    val id: String,
    val label: String,
    val defaultModel: String,
    val models: List<String>,
    val description: String,
    val requiresKey: Boolean
)

val FIREBASE_AI_MODELS = listOf(
    "gemini-3.8-flash",
    "gemini-3.5-flash-lite",
    "gemini-4-argon"
)

val BYO_LLM_PROVIDER_IDS = setOf(
    "groq",
    "gemini",
    "openrouter",
    "nvidia",
    "mistral",
    "openai",
    "anthropic",
    "xai"
)

val PROVIDER_CATALOG = listOf(
    ProviderOption(
        id = "auto",
        label = "Auto (Firebase AI + BYO Keys)",
        defaultModel = "gemini-3.8-flash",
        models = FIREBASE_AI_MODELS,
        description = "Uses built-in Firebase AI Logic first and seamlessly uses any optional BYO keys you add.",
        requiresKey = false
    ),
    ProviderOption(
        id = "firebase_ai",
        label = "Firebase AI Logic (Built-in)",
        defaultModel = "gemini-3.8-flash",
        models = FIREBASE_AI_MODELS,
        description = "Built-in Gemini models protected by App Check. No API key needed.",
        requiresKey = false
    ),
    ProviderOption(
        id = "groq",
        label = "Groq (BYO Key)",
        defaultModel = "",
        models = emptyList(),
        description = "Ultra-fast inference using your own Groq API key.",
        requiresKey = true
    ),
    ProviderOption(
        id = "gemini",
        label = "Google AI Studio (BYO Key)",
        defaultModel = "",
        models = emptyList(),
        description = "Direct Gemini REST endpoint using your personal AI Studio key.",
        requiresKey = true
    ),
    ProviderOption(
        id = "openrouter",
        label = "OpenRouter (BYO Key)",
        defaultModel = "",
        models = emptyList(),
        description = "Multi-model router using your own OpenRouter key.",
        requiresKey = true
    ),
    ProviderOption(
        id = "nvidia",
        label = "NVIDIA NIM (BYO Key)",
        defaultModel = "",
        models = emptyList(),
        description = "NVIDIA hosted endpoints using your NVIDIA API key.",
        requiresKey = true
    ),
    ProviderOption(
        id = "mistral",
        label = "Mistral AI (BYO Key)",
        defaultModel = "",
        models = emptyList(),
        description = "Mistral AI models using your own Mistral API key.",
        requiresKey = true
    ),
    ProviderOption(
        id = "openai",
        label = "OpenAI (BYO Key)",
        defaultModel = "",
        models = emptyList(),
        description = "Direct OpenAI chat completions using your own OpenAI API key.",
        requiresKey = true
    ),
    ProviderOption(
        id = "anthropic",
        label = "Anthropic Claude (BYO Key)",
        defaultModel = "",
        models = emptyList(),
        description = "Direct Anthropic Claude Messages API using your own Anthropic API key.",
        requiresKey = true
    ),
    ProviderOption(
        id = "xai",
        label = "xAI Grok (BYO Key)",
        defaultModel = "",
        models = emptyList(),
        description = "Direct xAI Grok chat completions using your own xAI API key.",
        requiresKey = true
    )
)

@Immutable
data class CapabilityExample(
    val category: String,
    val title: String,
    val prompt: String
)

val CAPABILITIES_CATALOG = listOf(
    CapabilityExample("Daily Briefing", "Today's agenda & weather", "What's on my agenda today and what's the weather like?"),
    CapabilityExample("Daily Briefing", "Latest headlines", "Give me today's top technology news headlines."),
    CapabilityExample("Daily Briefing", "Morning briefing routine", "Run my morning briefing routine."),
    CapabilityExample("Phone Control", "Flashlight toggle", "Turn on the flashlight."),
    CapabilityExample("Phone Control", "Set countdown timer", "Set a 5 minute timer for coffee."),
    CapabilityExample("Phone Control", "Set morning alarm", "Set an alarm for 7:00 AM on weekdays."),
    CapabilityExample("Phone Control", "Play music", "Play lofi beats on YouTube Music."),
    CapabilityExample("Phone Control", "Read notifications", "Read my unread notifications."),
    CapabilityExample("Research & Maps", "Nearby coffee", "Find the nearest coffee shops around me."),
    CapabilityExample("Research & Maps", "Directions", "Get directions to the nearest train station."),
    CapabilityExample("Research & Maps", "Structured comparison", "Compare Android Compose and SwiftUI in a clean table."),
    CapabilityExample("Creative & Visuals", "Generate artwork", "Draw a futuristic glowing blue arc reactor wallpaper."),
    CapabilityExample("Creative & Visuals", "Create QR code", "Make a QR code for https://github.com/ggcreation07/jarvis"),
    CapabilityExample("Creative & Visuals", "Visual chart", "Create a weekly study schedule and bar chart of focus hours."),
    CapabilityExample("Memory & Routines", "Remember preference", "Remember that I prefer concise replies and dark HUD mode."),
    CapabilityExample("Memory & Routines", "Create routine", "Create a bedtime routine that turns on DND and sets a 7am alarm.")
)
