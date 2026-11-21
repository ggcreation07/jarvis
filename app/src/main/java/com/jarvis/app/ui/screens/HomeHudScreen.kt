package com.jarvis.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.R
import com.jarvis.app.domain.model.ALL_CARD_IDS
import com.jarvis.app.domain.model.HudStatus
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.ActionLogWidgetCard
import com.jarvis.app.ui.components.AgendaWidgetCard
import com.jarvis.app.ui.components.BatteryWidgetCard
import com.jarvis.app.ui.components.DeviceWidgetCard
import com.jarvis.app.ui.components.HudBackgroundLayer
import com.jarvis.app.ui.components.HudWaveform
import com.jarvis.app.ui.components.IronManArcReactor
import com.jarvis.app.ui.components.ModernOrb
import com.jarvis.app.ui.components.NetworkWidgetCard
import com.jarvis.app.ui.components.RoutinesWidgetCard
import com.jarvis.app.ui.components.StatusWidgetCard
import com.jarvis.app.ui.components.WeatherWidgetCard
import com.jarvis.app.ui.theme.LocalJarvisColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeHudScreen(
    viewModel: JarvisViewModel,
    onRequestMicAndToggleVoice: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val screenConfig by viewModel.screenConfig.collectAsStateWithLifecycle()
    val brainConfig by viewModel.brainConfig.collectAsStateWithLifecycle()
    val status by viewModel.hudStatus.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val audioLevel by viewModel.voiceController.audioLevel.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.voiceController.partialTranscript.collectAsStateWithLifecycle()
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val streamingMsg by viewModel.streamingMessage.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val agenda by viewModel.todayAgenda.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetryState.collectAsStateWithLifecycle()
    val weather by viewModel.weatherState.collectAsStateWithLifecycle()
    val actionLogs by viewModel.actionLogs.collectAsStateWithLifecycle()
    val taskRecords by viewModel.taskRecords.collectAsStateWithLifecycle()

    var askInput by remember { mutableStateOf("") }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(15_000L)
        }
    }

    val lastReply = remember(turns, streamingMsg) {
        streamingMsg ?: turns.lastOrNull { it.role == "assistant" }
    }

    val activeTasksCount = remember(taskRecords) {
        taskRecords.count { it.state == "running" }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HudBackgroundLayer(
            screenConfig = screenConfig,
            modifier = Modifier.fillMaxSize()
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                HomeTopBar(
                    topBarButtons = screenConfig.topBarButtons,
                    muted = brainConfig.muted,
                    activeTasksCount = activeTasksCount,
                    onToggleMute = { viewModel.toggleMute() },
                    onOpenTasks = { viewModel.openOverlay(OverlayType.TASKS_ACTIVITY) },
                    onOpenRemote = { viewModel.openOverlay(OverlayType.REMOTE_PC) },
                    onOpenPalette = { viewModel.openOverlay(OverlayType.COMMAND_PALETTE) },
                    onOpenCustomize = { viewModel.openOverlay(OverlayType.CUSTOMIZE) },
                    onOpenSettings = { viewModel.openOverlay(OverlayType.SETTINGS) },
                    onOpenPower = { viewModel.openOverlay(OverlayType.DEVICE_POWER) }
                )
            },
            bottomBar = {
                HomeFloatingBottomNav(
                    isListening = status == HudStatus.LISTENING,
                    isSpeaking = status == HudStatus.SPEAKING,
                    onOpenChat = { viewModel.openOverlay(OverlayType.CHAT) },
                    onOpenMemory = { viewModel.openOverlay(OverlayType.MEMORY) },
                    onMicClick = {
                        if (isBusy || status == HudStatus.SPEAKING) {
                            viewModel.stopCurrentWork()
                        } else {
                            onRequestMicAndToggleVoice()
                        }
                    },
                    onOpenSkills = { viewModel.openOverlay(OverlayType.SKILLS) },
                    onOpenCapabilities = { viewModel.openOverlay(OverlayType.CAPABILITIES) }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 640.dp)
                        .imePadding(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(if (screenConfig.density == "compact") 10.dp else 14.dp)
                ) {
                    item(key = "hero") {
                        HeroCenterpieceSection(
                            nowMs = nowMs,
                            status = status,
                            audioLevel = audioLevel,
                            centerpiece = screenConfig.centerpiece,
                            partialTranscript = partialTranscript,
                            onCoreTap = {
                                if (isBusy || status == HudStatus.SPEAKING) {
                                    viewModel.stopCurrentWork()
                                } else {
                                    onRequestMicAndToggleVoice()
                                }
                            }
                        )
                    }

                    if (lastReply != null) {
                        item(key = "last_reply") {
                            LastReplyPreviewCard(
                                content = lastReply.content,
                                isStreaming = lastReply.isStreaming,
                                onOpenChat = { viewModel.openOverlay(OverlayType.CHAT) }
                            )
                        }
                    }

                    item(key = "ask_bar") {
                        HomeAskBar(
                            text = askInput,
                            onTextChange = { askInput = it },
                            isBusy = isBusy,
                            onMicClick = onRequestMicAndToggleVoice,
                            onStopClick = { viewModel.stopCurrentWork() },
                            onSend = {
                                if (askInput.isNotBlank()) {
                                    viewModel.sendUserMessage(askInput, openChatIfHome = true)
                                    askInput = ""
                                }
                            }
                        )
                    }

                    item(key = "starter_chips") {
                        StarterPromptRow(
                            onPick = { prompt ->
                                viewModel.sendUserMessage(prompt, openChatIfHome = true)
                            }
                        )
                    }

                    val orderedCards = (screenConfig.cardOrder + ALL_CARD_IDS).distinct()
                        .filterNot { it in screenConfig.hiddenCards }

                    items(
                        count = orderedCards.size,
                        key = { idx -> "card_${orderedCards[idx]}" }
                    ) { idx ->
                        val cardId = orderedCards[idx]
                        val collapsed = cardId in screenConfig.collapsedCards
                        val toggleCollapse = {
                            viewModel.updateScreen { cur ->
                                val next = if (cardId in cur.collapsedCards) {
                                    cur.collapsedCards - cardId
                                } else {
                                    cur.collapsedCards + cardId
                                }
                                cur.copy(collapsedCards = next)
                            }
                        }
                        when (cardId) {
                            "status" -> StatusWidgetCard(
                                status = status,
                                modelLabel = brainConfig.activeModel,
                                factsCount = facts.size,
                                tasksCount = taskRecords.size,
                                collapsed = collapsed,
                                screenConfig = screenConfig,
                                onToggleCollapse = toggleCollapse,
                                onOpenTasks = { viewModel.openOverlay(OverlayType.TASKS_ACTIVITY) }
                            )
                            "routines" -> RoutinesWidgetCard(
                                routines = routines,
                                collapsed = collapsed,
                                density = screenConfig.density,
                                onToggleCollapse = toggleCollapse,
                                onSaveRoutine = { viewModel.saveRoutine(it) },
                                onRunRoutine = { viewModel.runRoutine(it) },
                                onDeleteRoutine = { viewModel.deleteRoutine(it) }
                            )
                            "device" -> DeviceWidgetCard(
                                telemetry = telemetry,
                                gaugeStyle = screenConfig.gaugeStyle,
                                collapsed = collapsed,
                                density = screenConfig.density,
                                onToggleCollapse = toggleCollapse
                            )
                            "battery" -> BatteryWidgetCard(
                                telemetry = telemetry,
                                collapsed = collapsed,
                                density = screenConfig.density,
                                onToggleCollapse = toggleCollapse
                            )
                            "agenda" -> AgendaWidgetCard(
                                agenda = agenda,
                                collapsed = collapsed,
                                density = screenConfig.density,
                                onToggleCollapse = toggleCollapse,
                                onAddAgenda = { t, task -> viewModel.addAgendaItem(t, task) },
                                onToggleItem = { viewModel.toggleAgendaItem(it) },
                                onDeleteItem = { viewModel.deleteAgendaItem(it) }
                            )
                            "weather" -> WeatherWidgetCard(
                                weather = weather,
                                collapsed = collapsed,
                                density = screenConfig.density,
                                onToggleCollapse = toggleCollapse,
                                onRefresh = { viewModel.refreshTelemetryAndWeather() }
                            )
                            "network" -> NetworkWidgetCard(
                                telemetry = telemetry,
                                collapsed = collapsed,
                                density = screenConfig.density,
                                onToggleCollapse = toggleCollapse
                            )
                            "log" -> ActionLogWidgetCard(
                                logs = actionLogs,
                                collapsed = collapsed,
                                density = screenConfig.density,
                                onToggleCollapse = toggleCollapse
                            )
                        }
                    }

                    item(key = "bottom_spacer") {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    topBarButtons: List<String>,
    muted: Boolean,
    activeTasksCount: Int,
    onToggleMute: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenRemote: () -> Unit,
    onOpenPalette: () -> Unit,
    onOpenCustomize: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPower: () -> Unit
) {
    val hud = LocalJarvisColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.app_wordmark),
            style = MaterialTheme.typography.titleLarge,
            color = hud.accent,
            maxLines = 1,
            modifier = Modifier.padding(start = 4.dp)
        )
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if ("mute" in topBarButtons) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.testTag("top_mute_button")
                ) {
                    Icon(
                        imageVector = if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = if (muted) "Unmute voice replies" else "Mute voice replies",
                        tint = if (muted) hud.warning else hud.textSecondary
                    )
                }
            }
            if ("tasks" in topBarButtons) {
                Box {
                    IconButton(
                        onClick = onOpenTasks,
                        modifier = Modifier.testTag("top_tasks_button")
                    ) {
                        Icon(
                            Icons.Rounded.Checklist,
                            contentDescription = stringResource(R.string.cd_open_tasks),
                            tint = if (activeTasksCount > 0) hud.accent else hud.textSecondary
                        )
                    }
                    if (activeTasksCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 8.dp, end = 8.dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(hud.accent)
                        )
                    }
                }
            }
            if ("remote" in topBarButtons) {
                IconButton(
                    onClick = onOpenRemote,
                    modifier = Modifier.testTag("top_remote_button")
                ) {
                    Icon(
                        Icons.Rounded.Computer,
                        contentDescription = stringResource(R.string.cd_open_remote),
                        tint = hud.textSecondary
                    )
                }
            }
            if ("search" in topBarButtons) {
                IconButton(
                    onClick = onOpenPalette,
                    modifier = Modifier.testTag("top_search_button")
                ) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.cd_open_search),
                        tint = hud.textSecondary
                    )
                }
            }
            if ("customize" in topBarButtons) {
                IconButton(
                    onClick = onOpenCustomize,
                    modifier = Modifier.testTag("top_customize_button")
                ) {
                    Icon(
                        Icons.Rounded.Palette,
                        contentDescription = stringResource(R.string.cd_open_customize),
                        tint = hud.textSecondary
                    )
                }
            }
            if ("settings" in topBarButtons) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("top_settings_button")
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.cd_open_settings),
                        tint = hud.textSecondary
                    )
                }
            }
            if ("power" in topBarButtons) {
                IconButton(
                    onClick = onOpenPower,
                    modifier = Modifier.testTag("top_power_button")
                ) {
                    Icon(
                        Icons.Rounded.PowerSettingsNew,
                        contentDescription = stringResource(R.string.cd_open_power),
                        tint = hud.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCenterpieceSection(
    nowMs: Long,
    status: HudStatus,
    audioLevel: Float,
    centerpiece: String,
    partialTranscript: String,
    onCoreTap: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val cal = remember(nowMs) { Calendar.getInstance().apply { timeInMillis = nowMs } }
    val greeting = remember(cal.get(Calendar.HOUR_OF_DAY)) {
        when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    val timeText = remember(nowMs) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs)) }
    val dateText = remember(nowMs) { SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(nowMs)) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (hud.isHudLook) greeting.uppercase() else greeting,
            style = MaterialTheme.typography.labelMedium,
            color = hud.accent
        )
        Text(
            text = timeText,
            style = MaterialTheme.typography.displayLarge,
            color = hud.textPrimary
        )
        Text(
            text = dateText,
            style = MaterialTheme.typography.bodySmall,
            color = hud.textSecondary
        )

        Spacer(Modifier.height(2.dp))

        if (centerpiece == "reactor") {
            IronManArcReactor(
                status = status,
                audioLevel = audioLevel,
                onClick = onCoreTap,
                sizeDp = 198.dp
            )
        } else {
            ModernOrb(
                status = status,
                audioLevel = audioLevel,
                onClick = onCoreTap,
                sizeDp = 186.dp
            )
        }

        HudWaveform(status = status, audioLevel = audioLevel)

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(hud.surfaceCard)
                .border(1.dp, hud.borderSubtle, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = if (hud.isHudLook) status.label.uppercase() else status.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (status == HudStatus.IDLE) hud.textSecondary else hud.accent
            )
        }

        if (partialTranscript.isNotBlank()) {
            Text(
                text = "\"$partialTranscript\"",
                style = MaterialTheme.typography.bodyMedium,
                color = hud.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
    }
}

@Composable
private fun LastReplyPreviewCard(
    content: String,
    isStreaming: Boolean,
    onOpenChat: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val shape = RoundedCornerShape(hud.cornerRadiusDp.dp)
    val clean = remember(content) {
        content
            .replace(Regex("\\[(CHART|TABLE|FLOWCHART|SCHEDULE)][\\s\\S]*?\\[/\\1]"), "[Structured Card]")
            .trim()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(hud.surfaceCard)
            .border(1.dp, hud.borderSubtle, shape)
            .testTag("last_reply_card")
            .clickable(onClick = onOpenChat)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = clean.ifBlank { if (isStreaming) "Thinking…" else "" },
            style = MaterialTheme.typography.bodyMedium,
            color = hud.textPrimary,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "Open conversation →",
            style = MaterialTheme.typography.labelMedium,
            color = hud.accent,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HomeAskBar(
    text: String,
    onTextChange: (String) -> Unit,
    isBusy: Boolean,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    onSend: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val shape = RoundedCornerShape(hud.cornerRadiusDp.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(hud.surfaceCard)
            .border(1.dp, hud.borderSubtle, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("Ask JARVIS anything…", color = hud.textSecondary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = hud.textPrimary,
                unfocusedTextColor = hud.textPrimary,
                cursorColor = hud.accent
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("home_ask_input")
        )
        IconButton(
            onClick = onMicClick,
            modifier = Modifier.testTag("home_ask_mic_button")
        ) {
            Icon(
                Icons.Rounded.Mic,
                contentDescription = stringResource(R.string.cd_mic_button),
                tint = hud.accent
            )
        }
        IconButton(
            onClick = { if (isBusy) onStopClick() else onSend() },
            modifier = Modifier.testTag("home_ask_send_button")
        ) {
            Icon(
                imageVector = if (isBusy) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                contentDescription = if (isBusy) stringResource(R.string.cd_stop_speaking) else stringResource(R.string.cd_send_message),
                tint = if (isBusy) hud.danger else hud.accent
            )
        }
    }
}

@Composable
private fun StarterPromptRow(onPick: (String) -> Unit) {
    val hud = LocalJarvisColors.current
    val prompts = remember {
        listOf(
            "What's on my agenda today?",
            "What's the weather this week?",
            "Play lofi on YouTube"
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        prompts.forEachIndexed { idx, prompt ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(hud.surfaceCard)
                    .border(1.dp, hud.borderSubtle, RoundedCornerShape(50))
                    .testTag("starter_chip_$idx")
                    .clickable { onPick(prompt) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.labelMedium,
                    color = hud.textPrimary
                )
            }
        }
    }
}

@Composable
private fun HomeFloatingBottomNav(
    isListening: Boolean,
    isSpeaking: Boolean,
    onOpenChat: () -> Unit,
    onOpenMemory: () -> Unit,
    onMicClick: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCapabilities: () -> Unit
) {
    val hud = LocalJarvisColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = hud.surfacePanel.copy(alpha = 0.94f),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .border(1.dp, hud.borderSubtle, RoundedCornerShape(24.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomNavItem(
                    icon = Icons.AutoMirrored.Rounded.Chat,
                    label = "Chat",
                    contentDesc = stringResource(R.string.cd_open_chat),
                    tag = "nav_chat",
                    onClick = onOpenChat,
                    modifier = Modifier.weight(1f)
                )
                BottomNavItem(
                    icon = Icons.Rounded.Memory,
                    label = "Memory",
                    contentDesc = stringResource(R.string.cd_open_memory),
                    tag = "nav_memory",
                    onClick = onOpenMemory,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(72.dp))
                BottomNavItem(
                    icon = Icons.Rounded.AutoAwesome,
                    label = "Skills",
                    contentDesc = stringResource(R.string.cd_open_skills),
                    tag = "nav_skills",
                    onClick = onOpenSkills,
                    modifier = Modifier.weight(1f)
                )
                BottomNavItem(
                    icon = Icons.Rounded.Tune,
                    label = "Powers",
                    contentDesc = stringResource(R.string.cd_open_capabilities),
                    tag = "nav_capabilities",
                    onClick = onOpenCapabilities,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Elevated central glowing Mic button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset(y = (-14).dp)
                .size(62.dp)
                .shadow(14.dp, CircleShape, ambientColor = hud.accent, spotColor = hud.accent)
                .clip(CircleShape)
                .background(if (isListening || isSpeaking) hud.danger else hud.accent)
                .border(2.dp, Color.White.copy(alpha = 0.45f), CircleShape)
                .testTag("nav_mic_fab")
                .clickable(onClick = onMicClick)
        ) {
            Icon(
                imageVector = if (isListening || isSpeaking) Icons.Rounded.Stop else Icons.Rounded.Mic,
                contentDescription = if (isListening || isSpeaking) stringResource(R.string.cd_stop_speaking) else stringResource(R.string.cd_mic_button),
                tint = hud.background,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: ImageVector,
    label: String,
    contentDesc: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hud = LocalJarvisColors.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .testTag(tag)
            .clickable(onClick = onClick)
    ) {
        Icon(icon, contentDescription = contentDesc, tint = hud.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = hud.textSecondary, maxLines = 1)
    }
}
