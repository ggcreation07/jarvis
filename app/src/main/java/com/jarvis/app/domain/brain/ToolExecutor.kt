package com.jarvis.app.domain.brain

import android.graphics.Bitmap
import android.provider.Settings
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.ai.ToolCallExecutionResult
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.network.JarvisHttpTools
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.domain.model.RoutineItem
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.JarvisNotificationListener
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.RoutineAlarms
import com.jarvis.app.platform.TvControlClient
import com.jarvis.app.platform.WakeOnLan
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ToolSideEffects(
    val openOverlay: OverlayType? = null,
    val clearChat: Boolean = false,
    val generatedBitmap: Bitmap? = null,
    val qrBitmap: Bitmap? = null,
    val qrText: String = "",
    val runRoutinePrompt: String? = null
)

class ToolExecutor(
    private val repository: JarvisRepository,
    private val configStore: ConfigStore,
    private val httpTools: JarvisHttpTools,
    private val telemetry: DeviceTelemetry,
    private val deviceController: DeviceController,
    private val firebaseAiService: FirebaseAiService,
    private val remotePcClient: RemotePcClient,
    private val tvControlClient: TvControlClient,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val confirmationTimeoutMs: Long = ToolConfirmationPolicy.DEFAULT_CONFIRMATION_TIMEOUT_MS
) {
    private class ConfirmedGrant(
        val binding: ActionBindingKey,
        val expiresAtMs: Long
    ) {
        var consumed: Boolean = false
    }

    private val confirmationLock = Any()
    private var pendingConfirmation: PendingProtectedAction? = null
    private var nextConfirmationSeq: Long = 1L

    private fun pruneExpiredLocked(now: Long) {
        pendingConfirmation?.let {
            if (now > it.expiresAtMs) pendingConfirmation = null
        }
    }

    fun getPendingConfirmation(): PendingProtectedAction? = synchronized(confirmationLock) {
        pendingConfirmation?.takeIf { nowMs() <= it.expiresAtMs }
    }

    fun clearPendingConfirmation() {
        synchronized(confirmationLock) {
            pendingConfirmation = null
        }
    }

    suspend fun handleConfirmationInput(
        rawInput: String,
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): ConfirmationTurnOutcome {
        val current: PendingProtectedAction
        val isExpired: Boolean
        synchronized(confirmationLock) {
            val now = nowMs()
            val pending = pendingConfirmation
            if (pending == null) {
                return ConfirmationTurnOutcome.None
            }
            current = pending
            isExpired = now > pending.expiresAtMs
            if (isExpired) {
                pendingConfirmation = null
            }
        }

        val intent = ConfirmationClassifier.classify(rawInput)
        if (isExpired) {
            return when (intent) {
                ConfirmationIntent.CANCEL -> ConfirmationTurnOutcome.Cancelled(
                    actionDescription = current.description,
                    replyText = ToolConfirmationPolicy.buildCancelledReply(current.description, rawInput)
                )
                ConfirmationIntent.AFFIRM, ConfirmationIntent.AMBIGUOUS -> ConfirmationTurnOutcome.Expired(
                    actionDescription = current.description,
                    replyText = ToolConfirmationPolicy.buildExpiredReply(current.description, rawInput)
                )
                ConfirmationIntent.UNRELATED -> ConfirmationTurnOutcome.None
            }
        }

        return when (intent) {
            ConfirmationIntent.CANCEL -> {
                clearPendingConfirmation()
                repository.logAction(
                    current.binding.toolName,
                    "Confirmation cancelled: ${current.description}",
                    false
                )
                ConfirmationTurnOutcome.Cancelled(
                    actionDescription = current.description,
                    replyText = ToolConfirmationPolicy.buildCancelledReply(current.description, rawInput)
                )
            }
            ConfirmationIntent.AMBIGUOUS -> {
                ConfirmationTurnOutcome.NeedsClarification(
                    actionDescription = current.description,
                    replyText = ToolConfirmationPolicy.buildClarificationPrompt(current.description, rawInput)
                )
            }
            ConfirmationIntent.UNRELATED -> {
                clearPendingConfirmation()
                ConfirmationTurnOutcome.None
            }
            ConfirmationIntent.AFFIRM -> {
                val pair = synchronized(confirmationLock) {
                    val now = nowMs()
                    val latest = pendingConfirmation
                    if (latest == null || latest.id != current.id || now > latest.expiresAtMs) {
                        pendingConfirmation = null
                        null
                    } else {
                        pendingConfirmation = null
                        latest to ConfirmedGrant(latest.binding, latest.expiresAtMs)
                    }
                }
                if (pair == null) {
                    return ConfirmationTurnOutcome.Expired(
                        actionDescription = current.description,
                        replyText = ToolConfirmationPolicy.buildExpiredReply(current.description, rawInput)
                    )
                }
                val (boundAction, scopedGrant) = pair
                val result = executeInternal(
                    toolName = boundAction.binding.toolName,
                    args = boundAction.originalArgs,
                    onSideEffect = onSideEffect,
                    authorizedGrant = scopedGrant
                )
                ConfirmationTurnOutcome.Executed(boundAction, result)
            }
        }
    }

    private fun strArg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.content?.trim().orEmpty()

    private fun intArg(args: Map<String, JsonElement>, key: String): Int? =
        (args[key] as? JsonPrimitive)?.intOrNull

    suspend fun execute(
        toolName: String,
        args: Map<String, JsonElement>,
        onSideEffect: suspend (ToolSideEffects) -> Unit
    ): ToolCallExecutionResult = executeInternal(
        toolName = toolName,
        args = args,
        onSideEffect = onSideEffect,
        authorizedGrant = null
    )

    private suspend fun executeInternal(
        toolName: String,
        args: Map<String, JsonElement>,
        onSideEffect: suspend (ToolSideEffects) -> Unit,
        authorizedGrant: ConfirmedGrant?
    ): ToolCallExecutionResult {
        val normalizedName = toolName.trim()
        if (ToolConfirmationPolicy.requiresConfirmation(normalizedName, args)) {
            val binding = ActionBindingKey(
                toolName = normalizedName,
                canonicalArgs = ToolConfirmationPolicy.canonicalizeArgs(normalizedName, args)
            )
            var blockedId = ""
            var blockedDescription = ""
            var blockedSummary: String? = null
            synchronized(confirmationLock) {
                val now = nowMs()
                pruneExpiredLocked(now)
                val grantValid = authorizedGrant != null &&
                    !authorizedGrant.consumed &&
                    authorizedGrant.binding == binding &&
                    now <= authorizedGrant.expiresAtMs
                if (grantValid) {
                    authorizedGrant!!.consumed = true
                    pendingConfirmation = null
                } else {
                    val id = "confirm_${nextConfirmationSeq++}"
                    val description = ToolConfirmationPolicy.describeProtectedAction(normalizedName, args)
                    pendingConfirmation = PendingProtectedAction(
                        id = id,
                        binding = binding,
                        description = description,
                        originalArgs = args.toMap(),
                        createdAtMs = now,
                        expiresAtMs = now + confirmationTimeoutMs
                    )
                    blockedId = id
                    blockedDescription = description
                    blockedSummary = ToolConfirmationPolicy.buildConfirmationPrompt(description)
                }
            }
            if (blockedSummary != null) {
                repository.logAction(normalizedName, "Confirmation required: $blockedDescription", false)
                return confirmationRequired(normalizedName, blockedId, blockedDescription, blockedSummary)
            }
        } else {
            synchronized(confirmationLock) {
                pruneExpiredLocked(nowMs())
            }
        }

        val result = when (normalizedName) {
            "get_time" -> {
                val now = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                ok("get_time", "It's $now.")
            }
            "get_day" -> {
                val today = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
                ok("get_day", "Today is $today.")
            }
            "get_weather" -> {
                val coords = resolveCoordinates()
                if (coords == null) {
                    fail("get_weather", "I couldn't resolve your location yet. Grant Location permission or pin a city in Settings.")
                } else {
                    val snap = httpTools.fetchWeather(coords.first, coords.second, coords.third)
                    if (snap.error != null || snap.temperatureC == null) {
                        fail("get_weather", snap.error ?: "Couldn't read weather forecast.")
                    } else {
                        val where = if (snap.place.isNotBlank()) " in ${snap.place}" else ""
                        val summary = "Right now$where it's ${snap.temperatureC}°C (feels like ${snap.feelsLikeC ?: snap.temperatureC}°C), ${snap.condition}, humidity ${snap.humidityPercent ?: "—"}%, wind ${snap.windKmh ?: 0} km/h. " +
                            snap.forecastDays.joinToString(" | ")
                        ok("get_weather", summary)
                    }
                }
            }
            "get_news" -> {
                val topic = strArg(args, "topic")
                val (success, msg) = httpTools.fetchNews(topic)
                if (success) ok("get_news", msg) else fail("get_news", msg)
            }
            "find_places" -> {
                val query = strArg(args, "query")
                val coords = resolveCoordinates()
                if (coords == null) fail("find_places", "Need location access or a pinned city first.")
                else {
                    val (success, msg) = httpTools.findNearbyPlaces(query, coords.first, coords.second)
                    if (success) ok("find_places", msg) else fail("find_places", msg)
                }
            }
            "get_directions" -> {
                val dest = strArg(args, "destination")
                val coords = resolveCoordinates()
                if (coords == null) fail("get_directions", "Need current or pinned location first.")
                else {
                    val (success, msg) = httpTools.getDirections(coords.first, coords.second, dest)
                    if (success) ok("get_directions", msg) else fail("get_directions", msg)
                }
            }
            "read_webpage" -> {
                val url = strArg(args, "url")
                val (success, msg) = httpTools.readWebpage(url)
                if (success) ok("read_webpage", msg) else fail("read_webpage", msg)
            }
            "make_qr_code" -> {
                val text = strArg(args, "text")
                val bmp = httpTools.generateQrBitmap(text)
                if (bmp == null) {
                    fail("make_qr_code", "Couldn't encode an empty QR code.")
                } else {
                    onSideEffect(ToolSideEffects(qrBitmap = bmp, qrText = text))
                    ok("make_qr_code", "Generated QR code for $text.")
                }
            }
            "generate_image" -> {
                val prompt = strArg(args, "prompt")
                try {
                    val bmp = firebaseAiService.generateImage(prompt)
                    onSideEffect(ToolSideEffects(generatedBitmap = bmp))
                    ok("generate_image", "Generated image for \"$prompt\".")
                } catch (e: Exception) {
                    fail("generate_image", "Image generation failed: ${e.localizedMessage ?: "unknown error"}")
                }
            }
            "remember_fact" -> {
                val text = strArg(args, "text")
                if (text.isBlank()) fail("remember_fact", "Nothing provided to remember.")
                else {
                    repository.addFact(text)
                    ok("remember_fact", "Saved to memory: $text")
                }
            }
            "forget_fact" -> {
                val text = strArg(args, "text")
                val removed = repository.forgetFactsMatching(text)
                ok("forget_fact", if (removed > 0) "Forgot $removed matching memory item(s)." else "No matching memory item found.")
            }
            "manage_schedule" -> {
                val action = strArg(args, "action").ifBlank { "get" }
                val dayKey = RoutineAlarms.isoDate(System.currentTimeMillis())
                when (action) {
                    "add" -> {
                        val task = strArg(args, "task")
                        val time = strArg(args, "time").ifBlank { "Today" }
                        if (task.isBlank()) fail("manage_schedule", "Specify a task to add.")
                        else {
                            repository.addAgendaItem(dayKey, time, task)
                            ok("manage_schedule", "Added \"$task\" ($time) to today's agenda.")
                        }
                    }
                    "clear" -> {
                        repository.clearAgendaDay(dayKey)
                        ok("manage_schedule", "Cleared today's local agenda items.")
                    }
                    else -> {
                        val items = repository.getAgendaOnce(dayKey)
                        val calItems = if (configStore.getBrainConfigOnce().calendarSyncEnabled) {
                            deviceController.readTodaysCalendarEvents()
                        } else emptyList()
                        val all = (items + calItems).sortedBy { it.timeLabel }
                        if (all.isEmpty()) ok("manage_schedule", "Your agenda for today is clear.")
                        else ok("manage_schedule", "Today's agenda:\n" + all.joinToString("\n") { "• ${it.timeLabel}: ${it.task}" })
                    }
                }
            }
            "manage_routine" -> {
                val action = strArg(args, "action").ifBlank { "list" }
                val name = strArg(args, "name")
                when (action) {
                    "add" -> {
                        val prompt = strArg(args, "prompt")
                        val time = strArg(args, "time")
                        if (name.isBlank() || prompt.isBlank()) {
                            fail("manage_routine", "Routine needs both a name and steps.")
                        } else {
                            repository.upsertRoutine(RoutineItem(name = name, prompt = prompt, triggerTime = time))
                            ok("manage_routine", "Saved routine \"$name\"${if (time.isNotBlank()) " at $time" else ""}.")
                        }
                    }
                    "run" -> {
                        val routines = repository.getRoutinesOnce()
                        val match = routines.firstOrNull { it.name.contains(name, ignoreCase = true) }
                        if (match == null) fail("manage_routine", "Couldn't find routine \"$name\".")
                        else {
                            onSideEffect(ToolSideEffects(runRoutinePrompt = match.prompt))
                            ok("manage_routine", "Running routine \"${match.name}\": ${match.prompt}")
                        }
                    }
                    else -> {
                        val list = repository.getRoutinesOnce()
                        if (list.isEmpty()) ok("manage_routine", "You don't have any routines saved yet.")
                        else ok("manage_routine", "Saved routines: " + list.joinToString(", ") { "${it.name}${if (it.triggerTime.isNotBlank()) " (${it.triggerTime})" else ""}" })
                    }
                }
            }
            "set_timer" -> {
                val secs = intArg(args, "seconds") ?: strArg(args, "seconds").toIntOrNull() ?: 60
                val label = strArg(args, "label")
                val outcome = deviceController.setTimer(secs, label)
                if (outcome.ok) ok("set_timer", outcome.summary) else fail("set_timer", outcome.summary)
            }
            "set_alarm" -> {
                val hour = intArg(args, "hour") ?: 7
                val min = intArg(args, "minute") ?: 0
                val label = strArg(args, "label")
                val outcome = deviceController.setAlarm(hour, min, label)
                if (outcome.ok) ok("set_alarm", outcome.summary) else fail("set_alarm", outcome.summary)
            }
            "device_control" -> {
                val action = strArg(args, "action")
                val outcome = when (action) {
                    "flashlight_on" -> deviceController.toggleFlashlight(true)
                    "flashlight_off" -> deviceController.toggleFlashlight(false)
                    "volume_up" -> deviceController.adjustVolume("up")
                    "volume_down" -> deviceController.adjustVolume("down")
                    "mute" -> deviceController.adjustVolume("mute")
                    "unmute" -> deviceController.adjustVolume("unmute")
                    "dnd_on" -> deviceController.setDoNotDisturb(true)
                    "dnd_off" -> deviceController.setDoNotDisturb(false)
                    "media_play_pause" -> deviceController.sendMediaKey("play_pause")
                    "media_next" -> deviceController.sendMediaKey("next")
                    "media_prev" -> deviceController.sendMediaKey("previous")
                    "wifi_settings" -> deviceController.openSettingsScreen(Settings.ACTION_WIFI_SETTINGS)
                    "bluetooth_settings" -> deviceController.openSettingsScreen(Settings.ACTION_BLUETOOTH_SETTINGS)
                    else -> com.jarvis.app.platform.ActionOutcome(false, "Unknown device action: $action")
                }
                if (outcome.ok) ok("device_control", outcome.summary) else fail("device_control", outcome.summary)
            }
            "open_application" -> {
                val name = strArg(args, "name")
                val outcome = deviceController.openApplication(name)
                if (outcome.ok) ok("open_application", outcome.summary) else fail("open_application", outcome.summary)
            }
            "navigate_to" -> {
                val dest = strArg(args, "destination")
                val mode = strArg(args, "mode").ifBlank { "driving" }
                val outcome = deviceController.navigateMaps(dest, mode)
                if (outcome.ok) ok("navigate_to", outcome.summary) else fail("navigate_to", outcome.summary)
            }
            "play_media" -> {
                val query = strArg(args, "query")
                val app = strArg(args, "app")
                val outcome = deviceController.playMedia(query, app)
                if (outcome.ok) ok("play_media", outcome.summary) else fail("play_media", outcome.summary)
            }
            "read_notifications" -> {
                val app = strArg(args, "app")
                val listener = JarvisNotificationListener.instance
                if (listener == null) {
                    fail("read_notifications", "Notification access isn't enabled yet. Enable JARVIS Notification Reader in Android Settings.")
                } else {
                    val items = listener.readActiveItems(filterApp = app)
                    if (items.isEmpty()) ok("read_notifications", "No unread notifications right now.")
                    else ok("read_notifications", "Active notifications:\n" + items.take(8).joinToString("\n") { "• [${it.app}] ${it.title}: ${it.text}" })
                }
            }
            "read_clipboard" -> {
                val clip = deviceController.readClipboard()
                if (clip.isBlank()) ok("read_clipboard", "Your clipboard is currently empty.")
                else ok("read_clipboard", "Clipboard contents: $clip")
            }
            "list_directory" -> {
                val uri = configStore.getBrainConfigOnce().grantedFolderUri
                if (uri.isBlank()) fail("list_directory", "No folder granted yet. Choose a folder in Settings -> Files.")
                else {
                    val files = deviceController.listGrantedFolder(uri)
                    if (files.isEmpty()) ok("list_directory", "The granted folder is empty.")
                    else ok("list_directory", "Files in granted folder:\n" + files.joinToString("\n") { "• $it" })
                }
            }
            "read_file" -> {
                val path = strArg(args, "path")
                val uri = configStore.getBrainConfigOnce().grantedFolderUri
                val outcome = deviceController.readGrantedFileText(uri, path)
                if (outcome.ok) ok("read_file", outcome.summary) else fail("read_file", outcome.summary)
            }
            "customize_screen" -> {
                val action = strArg(args, "action")
                val value = strArg(args, "value").lowercase()
                when (action) {
                    "set_look" -> {
                        val isIronMan = value.contains("iron")
                        configStore.updateScreenConfig { cur ->
                            if (isIronMan) {
                                cur.copy(
                                    look = "ironman",
                                    accentKey = "iron",
                                    mainHex = "#00E5FF",
                                    secondHex = "#FFAA00",
                                    fontStyle = "hud",
                                    cardStyle = "hud",
                                    centerpiece = "reactor"
                                )
                            } else {
                                cur.copy(
                                    look = "modern",
                                    accentKey = "cyan",
                                    mainHex = "#00E5FF",
                                    secondHex = "#3B82F6",
                                    fontStyle = "clean",
                                    cardStyle = "clean",
                                    centerpiece = "orb"
                                )
                            }
                        }
                        ok("customize_screen", "Switched HUD look to ${if (isIronMan) "Iron Man" else "Modern"}.")
                    }
                    "set_accent" -> {
                        val preset = com.jarvis.app.domain.model.DEFAULT_ACCENT_PRESETS.firstOrNull {
                            it.key.equals(value, true) || it.label.equals(value, true)
                        }
                        if (preset != null) {
                            configStore.updateScreenConfig {
                                it.copy(accentKey = preset.key, mainHex = preset.mainHex, secondHex = preset.secondHex)
                            }
                            ok("customize_screen", "Accent color set to ${preset.label}.")
                        } else {
                            fail("customize_screen", "Unknown accent preset \"$value\".")
                        }
                    }
                    "set_background" -> {
                        val bg = value.takeIf { it in setOf("grid", "glow", "aurora", "minimal") } ?: "grid"
                        configStore.updateScreenConfig { it.copy(backgroundStyle = bg) }
                        ok("customize_screen", "Background set to $bg.")
                    }
                    "reset" -> {
                        configStore.updateScreenConfig { com.jarvis.app.domain.model.ScreenConfig(quickStartCompleted = it.quickStartCompleted) }
                        ok("customize_screen", "Reset HUD appearance to defaults.")
                    }
                    else -> fail("customize_screen", "Unsupported screen action: $action")
                }
            }
            "control_interface" -> {
                val action = strArg(args, "action")
                when (action) {
                    "open_memory" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.MEMORY)); ok("control_interface", "Opened Memory.") }
                    "open_skills" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.SKILLS)); ok("control_interface", "Opened Skills.") }
                    "open_capabilities" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.CAPABILITIES)); ok("control_interface", "Opened Capabilities.") }
                    "open_settings" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.SETTINGS)); ok("control_interface", "Opened Settings.") }
                    "open_customize" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.CUSTOMIZE)); ok("control_interface", "Opened Customize.") }
                    "open_power" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.DEVICE_POWER)); ok("control_interface", "Opened Device & Power.") }
                    "open_remote" -> { onSideEffect(ToolSideEffects(openOverlay = OverlayType.REMOTE_PC)); ok("control_interface", "Opened Remote PC & TV.") }
                    "clear_chat" -> { onSideEffect(ToolSideEffects(clearChat = true)); ok("control_interface", "Cleared conversation.") }
                    else -> ok("control_interface", "Handled UI action $action.")
                }
            }
            "pc_task" -> {
                val cfg = configStore.getBrainConfigOnce()
                val goal = strArg(args, "goal")
                val kind = strArg(args, "kind").ifBlank { "browser" }
                val (success, msg) = remotePcClient.sendTask(cfg.pcHost, cfg.pcPort, goal, kind)
                if (success) ok("pc_task", msg) else fail("pc_task", msg)
            }
            "wake_pc" -> {
                val cfg = configStore.getBrainConfigOnce()
                if (cfg.pcMacAddress.isBlank()) fail("wake_pc", "No PC MAC address configured in Settings -> Devices.")
                else {
                    val (success, msg) = WakeOnLan.send(cfg.pcMacAddress, cfg.pcHost)
                    if (success) ok("wake_pc", msg) else fail("wake_pc", msg)
                }
            }
            "tv_control" -> {
                val cfg = configStore.getBrainConfigOnce()
                val action = strArg(args, "action").ifBlank { "status" }
                if (action == "wake" && cfg.tvMacAddress.isNotBlank()) {
                    val (success, msg) = WakeOnLan.send(cfg.tvMacAddress, cfg.tvHost)
                    if (success) ok("tv_control", msg) else fail("tv_control", msg)
                } else {
                    val (success, msg) = tvControlClient.checkTvReachable(cfg.tvHost, cfg.tvPort)
                    if (success) ok("tv_control", msg) else fail("tv_control", msg)
                }
            }
            else -> fail(toolName, "Unknown tool: $toolName")
        }

        repository.logAction(result.toolName, result.summary, result.ok)
        return result
    }

    private suspend fun resolveCoordinates(): Triple<Double, Double, String>? {
        val cfg = configStore.getBrainConfigOnce()
        if (cfg.pinnedLocation.isNotBlank()) {
            val geo = httpTools.geocode(cfg.pinnedLocation)
            if (geo != null) return Triple(geo.lat, geo.lon, cfg.pinnedLocation)
        }
        val gps = telemetry.lastKnownDeviceLocation()
        if (gps != null) return Triple(gps.first, gps.second, "Current location")
        val ip = httpTools.ipGeoLookup() ?: return null
        return Triple(ip.lat, ip.lon, ip.place.ifBlank { "Approximate location" })
    }

    private fun ok(tool: String, summary: String) = ToolCallExecutionResult(
        toolName = tool,
        ok = true,
        summary = summary,
        payload = buildJsonObject { put("status", "ok") }
    )

    private fun fail(tool: String, summary: String) = ToolCallExecutionResult(
        toolName = tool,
        ok = false,
        summary = summary,
        payload = buildJsonObject { put("status", "failed") }
    )

    private fun confirmationRequired(
        tool: String,
        pendingId: String,
        description: String,
        summary: String
    ) = ToolCallExecutionResult(
        toolName = tool,
        ok = false,
        summary = summary,
        payload = buildJsonObject {
            put("status", ToolConfirmationPolicy.STATUS_CONFIRMATION_REQUIRED)
            put("requires_confirmation", true)
            put("pending_id", pendingId)
            put("action", description)
        }
    )
}
