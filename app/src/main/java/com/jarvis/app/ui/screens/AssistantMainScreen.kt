package com.jarvis.app.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.R
import com.jarvis.app.domain.model.AgendaItem
import com.jarvis.app.domain.model.ArchivedThreadSummary
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.domain.model.ChatMessageItem
import com.jarvis.app.domain.model.HudStatus
import com.jarvis.app.domain.model.JARVIS_AGENTS
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.domain.model.TelemetrySnapshot
import com.jarvis.app.domain.model.ToolActivityItem
import com.jarvis.app.domain.model.WeatherSnapshot
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.OrbState
import com.jarvis.app.ui.components.RgbAssistantOrb
import com.jarvis.app.ui.components.RichResponseRenderer
import com.jarvis.app.ui.components.resolveOrbState
import com.jarvis.app.ui.theme.LocalJarvisColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantMainScreen(
    viewModel: JarvisViewModel,
    onRequestMicAndToggleVoice: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val brainConfig by viewModel.brainConfig.collectAsStateWithLifecycle()
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val streamingMsg by viewModel.streamingMessage.collectAsStateWithLifecycle()
    val archivedThreads by viewModel.archivedThreads.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val status by viewModel.hudStatus.collectAsStateWithLifecycle()
    val isListening by viewModel.voiceController.isListening.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.voiceController.isSpeaking.collectAsStateWithLifecycle()
    val audioLevel by viewModel.voiceController.audioLevel.collectAsStateWithLifecycle()
    val speakingLevel by viewModel.voiceController.speakingLevel.collectAsStateWithLifecycle()
    val partialTranscript by viewModel.voiceController.partialTranscript.collectAsStateWithLifecycle()
    val attachedImage by viewModel.attachedImage.collectAsStateWithLifecycle()
    val generatedBitmaps by viewModel.generatedBitmaps.collectAsStateWithLifecycle()
    val prefill by viewModel.prefillInput.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetryState.collectAsStateWithLifecycle()
    val weather by viewModel.weatherState.collectAsStateWithLifecycle()
    val agenda by viewModel.todayAgenda.collectAsStateWithLifecycle()
    val taskRecords by viewModel.taskRecords.collectAsStateWithLifecycle()

    val orbState = remember(isListening, isSpeaking, isBusy, status) {
        resolveOrbState(
            isListening = isListening,
            isSpeaking = isSpeaking,
            isBusy = isBusy,
            status = status
        )
    }

    var input by remember { mutableStateOf("") }
    var showModeSheet by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(prefill) {
        if (prefill.isNotBlank()) {
            input = prefill
            viewModel.clearPrefillPrompt()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.attachImageUri(uri)
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    val allMessages = remember(turns, streamingMsg) {
        if (streamingMsg != null) turns + streamingMsg!! else turns
    }
    val listState = rememberLazyListState()
    LaunchedEffect(allMessages.size, streamingMsg?.content?.length) {
        if (allMessages.isNotEmpty()) {
            listState.animateScrollToItem(allMessages.lastIndex)
        }
    }

    val activeTasksCount = remember(taskRecords) {
        taskRecords.count { it.state == "running" }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AssistantSidebarDrawer(
                archivedThreads = archivedThreads,
                weather = weather,
                telemetry = telemetry,
                agenda = agenda,
                activeTasksCount = activeTasksCount,
                onNewChat = {
                    viewModel.startNewChat()
                    scope.launch { drawerState.close() }
                },
                onRestoreThread = { threadId ->
                    viewModel.restoreThread(threadId)
                    scope.launch { drawerState.close() }
                },
                onDeleteThread = { threadId ->
                    viewModel.deleteArchivedThread(threadId)
                },
                onOpenOverlay = { overlay ->
                    scope.launch { drawerState.close() }
                    viewModel.openOverlay(overlay)
                },
                onRefreshWeather = { viewModel.refreshTelemetryAndWeather() }
            )
        }
    ) {
        Scaffold(
            containerColor = hud.background,
            topBar = {
                AssistantTopBar(
                    brainConfig = brainConfig,
                    status = status,
                    orbState = orbState,
                    audioLevel = audioLevel,
                    speakingLevel = speakingLevel,
                    hasMessages = allMessages.isNotEmpty(),
                    onOpenSidebar = { scope.launch { drawerState.open() } },
                    onOpenModePicker = { showModeSheet = true },
                    onVoiceShortcut = {
                        if (status == HudStatus.LISTENING || status == HudStatus.SPEAKING || isBusy) {
                            viewModel.stopCurrentWork()
                        } else {
                            onRequestMicAndToggleVoice()
                        }
                    },
                    onNewChat = { viewModel.startNewChat() }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(hud.background)
                    .padding(innerPadding)
                    .navigationBarsPadding()
                    .imePadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 720.dp)
                ) {
                    if (allMessages.isEmpty()) {
                        EmptyAssistantState(
                            status = status,
                            orbState = orbState,
                            audioLevel = audioLevel,
                            speakingLevel = speakingLevel,
                            onOrbClick = {
                                if (isBusy || status == HudStatus.LISTENING || status == HudStatus.SPEAKING) {
                                    viewModel.stopCurrentWork()
                                } else {
                                    onRequestMicAndToggleVoice()
                                }
                            },
                            onPickStarter = { prompt ->
                                viewModel.sendUserMessage(prompt, openChatIfHome = false)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            items(allMessages, key = { "${it.id}_${it.timestampMs}_${it.isStreaming}" }) { msg ->
                                AssistantMessageItem(
                                    msg = msg,
                                    bitmap = generatedBitmaps[msg.generatedImageKey],
                                    onCopy = {
                                        viewModel.deviceController.copyToClipboard("JARVIS", msg.content)
                                        viewModel.showBanner("Copied reply to clipboard.")
                                    },
                                    onSpeak = { viewModel.voiceController.speak(msg.content) }
                                )
                            }
                        }
                    }

                    AssistantBottomComposer(
                        input = input,
                        onInputChange = { input = it },
                        isBusy = isBusy,
                        status = status,
                        orbState = orbState,
                        audioLevel = audioLevel,
                        speakingLevel = speakingLevel,
                        partialTranscript = partialTranscript,
                        attachedImage = attachedImage,
                        onClearAttachment = { viewModel.clearAttachedImage() },
                        onAttachPhoto = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onMicClick = {
                            if (status == HudStatus.SPEAKING) {
                                viewModel.voiceController.stopSpeaking()
                            } else {
                                onRequestMicAndToggleVoice()
                            }
                        },
                        onSendOrStop = {
                            if (isBusy || status == HudStatus.SPEAKING) {
                                viewModel.stopCurrentWork()
                            } else if (input.isNotBlank() || attachedImage != null) {
                                viewModel.sendUserMessage(input, openChatIfHome = false)
                                input = ""
                            }
                        }
                    )
                }
            }
        }
    }

    if (showModeSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showModeSheet = false },
            sheetState = sheetState,
            containerColor = hud.surfacePanel,
            contentColor = hud.textPrimary
        ) {
            AgentAndModeBottomSheetContent(
                brainConfig = brainConfig,
                onToggleConversationMode = {
                    viewModel.updateBrain { it.copy(conversationMode = !it.conversationMode) }
                },
                onToggleAgentsMode = {
                    viewModel.updateBrain { it.copy(agentsMode = !it.agentsMode) }
                },
                onSelectAgent = { agentId ->
                    viewModel.updateBrain { it.copy(agentsMode = agentId != "auto", selectedAgentId = agentId) }
                },
                onToggleMute = { viewModel.toggleMute() },
                onOpenPalette = {
                    showModeSheet = false
                    viewModel.openOverlay(OverlayType.COMMAND_PALETTE)
                }
            )
        }
    }
}

@Composable
private fun AssistantTopBar(
    brainConfig: BrainConfig,
    status: HudStatus,
    orbState: OrbState,
    audioLevel: Float,
    speakingLevel: Float,
    hasMessages: Boolean,
    onOpenSidebar: () -> Unit,
    onOpenModePicker: () -> Unit,
    onVoiceShortcut: () -> Unit,
    onNewChat: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val activeAgent = remember(brainConfig.selectedAgentId) {
        JARVIS_AGENTS.firstOrNull { it.id == brainConfig.selectedAgentId }
    }
    val modeLabel = buildString {
        append("JARVIS")
        if (activeAgent != null && activeAgent.id != "auto") {
            append(" · ${activeAgent.handle}")
        } else if (brainConfig.conversationMode) {
            append(" · Voice")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(hud.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onOpenSidebar,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("main_menu_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.cd_open_sidebar),
                    tint = hud.textPrimary
                )
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val desc = stringResource(R.string.cd_agent_mode_picker)
                Row(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(hud.surfacePanel)
                        .border(1.dp, hud.borderSubtle, RoundedCornerShape(50))
                        .testTag("main_agent_mode_pill")
                        .semantics { contentDescription = desc }
                        .clickable(onClick = onOpenModePicker)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hasMessages || orbState != OrbState.IDLE) {
                        RgbAssistantOrb(
                            state = orbState,
                            audioLevel = audioLevel,
                            speakingLevel = speakingLevel,
                            sizeDp = 22.dp,
                            interactive = false
                        )
                    }
                    Text(
                        text = modeLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = hud.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = hud.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            IconButton(
                onClick = onVoiceShortcut,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("main_voice_mode_button")
            ) {
                Icon(
                    imageVector = if (status == HudStatus.LISTENING || status == HudStatus.SPEAKING) Icons.Rounded.Stop else Icons.Rounded.GraphicEq,
                    contentDescription = stringResource(R.string.cd_voice_mode),
                    tint = if (status == HudStatus.LISTENING || status == HudStatus.SPEAKING) hud.danger else hud.textSecondary
                )
            }

            IconButton(
                onClick = onNewChat,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("chat_new_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.AddComment,
                    contentDescription = stringResource(R.string.cd_new_chat),
                    tint = hud.textPrimary
                )
            }
        }
        HorizontalDivider(color = hud.borderSubtle, thickness = 1.dp)
    }
}

@Composable
private fun EmptyAssistantState(
    status: HudStatus,
    orbState: OrbState,
    audioLevel: Float,
    speakingLevel: Float,
    onOrbClick: () -> Unit,
    onPickStarter: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val hud = LocalJarvisColors.current
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = remember(hour) {
        when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    val prompts = remember {
        listOf(
            "What's on my agenda today?",
            "What's the weather this week?",
            "Play lofi on YouTube"
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            RgbAssistantOrb(
                state = orbState,
                audioLevel = audioLevel,
                speakingLevel = speakingLevel,
                sizeDp = 204.dp,
                interactive = true,
                onClick = onOrbClick
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "$greeting. How can I help?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = hud.textPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (orbState == OrbState.IDLE) "Ask anything, tap the orb to speak, or attach an image." else status.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (orbState == OrbState.IDLE) hud.textSecondary else hud.accent,
                    textAlign = TextAlign.Center
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                prompts.forEachIndexed { idx, prompt ->
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(50))
                            .background(hud.surfacePanel)
                            .border(1.dp, hud.borderSubtle, RoundedCornerShape(50))
                            .testTag("starter_chip_$idx")
                            .clickable { onPickStarter(prompt) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.labelMedium,
                            color = hud.textPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessageItem(
    msg: ChatMessageItem,
    bitmap: Bitmap?,
    onCopy: () -> Unit,
    onSpeak: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val isUser = msg.role == "user"
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    if (isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF1C212D))
                    .border(1.dp, hud.borderSubtle, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (msg.attachmentLabel.isNotBlank()) {
                    Text(
                        text = msg.attachmentLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = hud.accent
                    )
                }
                Text(
                    text = msg.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = hud.textPrimary
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (msg.isError) {
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(hud.danger.copy(alpha = 0.10f))
                            .border(1.dp, hud.danger.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    } else {
                        Modifier.padding(horizontal = 4.dp)
                    }
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (msg.agentId.isNotBlank() && msg.agentId != "auto") {
                Text(
                    text = "@${msg.agentId}",
                    style = MaterialTheme.typography.labelMedium,
                    color = hud.accent
                )
            }
            if (msg.toolActivities.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    msg.toolActivities.forEach { act ->
                        AssistantToolActivityRow(act)
                    }
                }
            }
            if (msg.content.isNotBlank()) {
                RichResponseRenderer(content = msg.content, textColor = hud.textPrimary)
            } else if (msg.isStreaming) {
                Text(
                    text = "Thinking…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = hud.textSecondary
                )
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = if (msg.qrText.isNotBlank()) stringResource(R.string.cd_qr_code) else stringResource(R.string.cd_generated_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (msg.qrText.isNotBlank()) Color.White else hud.surfacePanel)
                        .padding(if (msg.qrText.isNotBlank()) 8.dp else 0.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = fmt.format(Date(msg.timestampMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = hud.textSecondary
                )
                if (!msg.isStreaming && msg.content.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = "Copy reply",
                                tint = hud.textSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        IconButton(onClick = onSpeak, modifier = Modifier.size(40.dp)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                                contentDescription = "Read reply aloud",
                                tint = hud.textSecondary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantToolActivityRow(act: ToolActivityItem) {
    val hud = LocalJarvisColors.current
    val dotColor = when (act.status) {
        "succeeded" -> hud.success
        "failed" -> hud.danger
        "running" -> hud.accent
        else -> hud.warning
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(hud.surfacePanel)
            .border(1.dp, hud.borderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = act.label,
            style = MaterialTheme.typography.labelMedium,
            color = hud.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (act.durationMs > 0) {
            Text(
                text = "${act.durationMs}ms",
                style = MaterialTheme.typography.labelSmall,
                color = hud.textSecondary
            )
        }
    }
}

@Composable
private fun AssistantBottomComposer(
    input: String,
    onInputChange: (String) -> Unit,
    isBusy: Boolean,
    status: HudStatus,
    orbState: OrbState,
    audioLevel: Float,
    speakingLevel: Float,
    partialTranscript: String,
    attachedImage: Pair<String, Bitmap>?,
    onClearAttachment: () -> Unit,
    onAttachPhoto: () -> Unit,
    onMicClick: () -> Unit,
    onSendOrStop: () -> Unit
) {
    val hud = LocalJarvisColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (orbState != OrbState.IDLE || partialTranscript.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(hud.surfacePanel)
                    .border(1.dp, hud.borderSubtle, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RgbAssistantOrb(
                    state = orbState,
                    audioLevel = audioLevel,
                    speakingLevel = speakingLevel,
                    sizeDp = 26.dp,
                    interactive = false
                )
                Text(
                    text = if (partialTranscript.isNotBlank()) "\"$partialTranscript\"" else status.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = hud.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        attachedImage?.let { (label, bmp) ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(hud.surfacePanel)
                    .border(1.dp, hud.borderSubtle, RoundedCornerShape(16.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = hud.textPrimary
                )
                IconButton(
                    onClick = onClearAttachment,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Remove attached photo",
                        tint = hud.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(hud.surfacePanel)
                .border(1.dp, hud.borderSubtle, RoundedCornerShape(26.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(
                onClick = onAttachPhoto,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("chat_attach_photo_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.AttachFile,
                    contentDescription = stringResource(R.string.cd_attach_image),
                    tint = hud.textSecondary
                )
            }

            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = {
                    Text(
                        text = "Message JARVIS",
                        style = MaterialTheme.typography.bodyLarge,
                        color = hud.textSecondary
                    )
                },
                minLines = 1,
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSendOrStop() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = hud.textPrimary,
                    unfocusedTextColor = hud.textPrimary,
                    cursorColor = hud.accent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_composer_input")
            )

            IconButton(
                onClick = onMicClick,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("chat_mic_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = stringResource(R.string.cd_mic_button),
                    tint = if (status == HudStatus.LISTENING) hud.danger else hud.textSecondary
                )
            }

            val showStop = isBusy || status == HudStatus.SPEAKING
            val canSend = input.isNotBlank() || attachedImage != null
            IconButton(
                onClick = onSendOrStop,
                enabled = showStop || canSend,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("chat_send_button")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                showStop -> hud.danger
                                canSend -> hud.textPrimary
                                else -> hud.borderSubtle
                            }
                        )
                ) {
                    Icon(
                        imageVector = if (showStop) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                        contentDescription = if (showStop) stringResource(R.string.cd_stop_speaking) else stringResource(R.string.cd_send_message),
                        tint = if (showStop || canSend) hud.background else hud.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantSidebarDrawer(
    archivedThreads: List<ArchivedThreadSummary>,
    weather: WeatherSnapshot,
    telemetry: TelemetrySnapshot,
    agenda: List<AgendaItem>,
    activeTasksCount: Int,
    onNewChat: () -> Unit,
    onRestoreThread: (String) -> Unit,
    onDeleteThread: (String) -> Unit,
    onOpenOverlay: (OverlayType) -> Unit,
    onRefreshWeather: () -> Unit
) {
    val hud = LocalJarvisColors.current

    ModalDrawerSheet(
        drawerContainerColor = hud.surfacePanel,
        drawerContentColor = hud.textPrimary,
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_wordmark),
                    style = MaterialTheme.typography.titleLarge,
                    color = hud.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(50))
                        .background(hud.background)
                        .border(1.dp, hud.borderSubtle, RoundedCornerShape(50))
                        .testTag("sidebar_new_chat")
                        .clickable(onClick = onNewChat)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddComment,
                        contentDescription = null,
                        tint = hud.textPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "New chat",
                        style = MaterialTheme.typography.labelMedium,
                        color = hud.textPrimary
                    )
                }
            }

            HorizontalDivider(color = hud.borderSubtle)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Compact live status section (Weather, Battery, Agenda)
                SidebarCompactStatusBlock(
                    weather = weather,
                    telemetry = telemetry,
                    agenda = agenda,
                    onRefreshWeather = onRefreshWeather,
                    onOpenAgenda = { onOpenOverlay(OverlayType.ROUTINES) }
                )

                HorizontalDivider(
                    color = hud.borderSubtle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                SidebarNavRow(
                    icon = Icons.Rounded.Memory,
                    label = "Memory",
                    tag = "nav_memory",
                    onClick = { onOpenOverlay(OverlayType.MEMORY) }
                )
                SidebarNavRow(
                    icon = Icons.Rounded.AutoAwesome,
                    label = "Skills",
                    tag = "nav_skills",
                    onClick = { onOpenOverlay(OverlayType.SKILLS) }
                )
                SidebarNavRow(
                    icon = Icons.Rounded.Tune,
                    label = "Capabilities",
                    tag = "nav_capabilities",
                    onClick = { onOpenOverlay(OverlayType.CAPABILITIES) }
                )
                SidebarNavRow(
                    icon = Icons.Rounded.Bolt,
                    label = "Routines",
                    tag = "nav_routines",
                    onClick = { onOpenOverlay(OverlayType.ROUTINES) }
                )
                SidebarNavRow(
                    icon = Icons.Rounded.PanTool,
                    label = "Hands-Free",
                    tag = "nav_hands_free",
                    onClick = { onOpenOverlay(OverlayType.HANDS_FREE) }
                )
                SidebarNavRow(
                    icon = Icons.Rounded.Checklist,
                    label = "Tasks & Activity Logs",
                    badge = if (activeTasksCount > 0) "$activeTasksCount" else null,
                    tag = "top_tasks_button",
                    onClick = { onOpenOverlay(OverlayType.TASKS_ACTIVITY) }
                )
                SidebarNavRow(
                    icon = Icons.Rounded.Computer,
                    label = "Remote PC / Companion",
                    tag = "top_remote_button",
                    onClick = { onOpenOverlay(OverlayType.REMOTE_PC) }
                )
                SidebarNavRow(
                    icon = Icons.Rounded.Settings,
                    label = "Settings",
                    tag = "top_settings_button",
                    onClick = { onOpenOverlay(OverlayType.SETTINGS) }
                )

                HorizontalDivider(
                    color = hud.borderSubtle,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                Text(
                    text = "Recent chats",
                    style = MaterialTheme.typography.labelMedium,
                    color = hud.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                if (archivedThreads.isEmpty()) {
                    Text(
                        text = "No saved conversations yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = hud.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                } else {
                    archivedThreads.forEach { thread ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable { onRestoreThread(thread.id) }
                                .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = thread.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = hud.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${thread.messageCount} messages",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = hud.textSecondary,
                                    maxLines = 1
                                )
                            }
                            IconButton(
                                onClick = { onDeleteThread(thread.id) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = "Delete thread",
                                    tint = hud.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarCompactStatusBlock(
    weather: WeatherSnapshot,
    telemetry: TelemetrySnapshot,
    agenda: List<AgendaItem>,
    onRefreshWeather: () -> Unit,
    onOpenAgenda: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val openAgenda = remember(agenda) { agenda.filterNot { it.completed } }
    val nextAgenda = openAgenda.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(hud.background)
            .border(1.dp, hud.borderSubtle, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onRefreshWeather),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.WbSunny,
                contentDescription = null,
                tint = hud.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = weather.temperatureC?.let { temp ->
                    val place = weather.place.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                    "${temp}°C · ${weather.condition}$place"
                } ?: (weather.error ?: "Weather loading…"),
                style = MaterialTheme.typography.bodySmall,
                color = hud.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (telemetry.isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
                contentDescription = null,
                tint = if (telemetry.batteryPercent <= 20 && !telemetry.isCharging) hud.warning else hud.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Battery ${telemetry.batteryPercent}%${if (telemetry.isCharging) " · Charging" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = hud.textSecondary,
                maxLines = 1
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenAgenda),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.CalendarMonth,
                contentDescription = null,
                tint = hud.textSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = when {
                    nextAgenda != null -> "${openAgenda.size} today · ${nextAgenda.timeLabel} ${nextAgenda.task}"
                    agenda.isNotEmpty() -> "All ${agenda.size} agenda items done"
                    else -> "Today's agenda clear"
                },
                style = MaterialTheme.typography.bodySmall,
                color = hud.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SidebarNavRow(
    icon: ImageVector,
    label: String,
    tag: String,
    badge: String? = null,
    onClick: () -> Unit
) {
    val hud = LocalJarvisColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .testTag(tag)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = hud.textSecondary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = hud.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!badge.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(hud.accent.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = hud.accent
                )
            }
        }
    }
}

@Composable
private fun AgentAndModeBottomSheetContent(
    brainConfig: BrainConfig,
    onToggleConversationMode: () -> Unit,
    onToggleAgentsMode: () -> Unit,
    onSelectAgent: (String) -> Unit,
    onToggleMute: () -> Unit,
    onOpenPalette: () -> Unit
) {
    val hud = LocalJarvisColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Assistant Mode & Agent",
            style = MaterialTheme.typography.titleLarge,
            color = hud.textPrimary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Conversation Mode", style = MaterialTheme.typography.bodyMedium, color = hud.textPrimary)
                Text("Continuous back-and-forth voice replies", style = MaterialTheme.typography.bodySmall, color = hud.textSecondary)
            }
            Switch(
                checked = brainConfig.conversationMode,
                onCheckedChange = { onToggleConversationMode() },
                modifier = Modifier.testTag("chat_conversation_mode_chip")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Mute Voice Output", style = MaterialTheme.typography.bodyMedium, color = hud.textPrimary)
                Text("Silence spoken replies while keeping text", style = MaterialTheme.typography.bodySmall, color = hud.textSecondary)
            }
            Switch(
                checked = brainConfig.muted,
                onCheckedChange = { onToggleMute() },
                modifier = Modifier.testTag("top_mute_button")
            )
        }

        HorizontalDivider(color = hud.borderSubtle)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Specialist Agent", style = MaterialTheme.typography.titleSmall, color = hud.textPrimary, modifier = Modifier.weight(1f))
            FilterChip(
                selected = brainConfig.agentsMode,
                onClick = onToggleAgentsMode,
                label = { Text("Agents") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = hud.accent.copy(alpha = 0.2f),
                    selectedLabelColor = hud.accent
                ),
                modifier = Modifier.testTag("chat_agents_mode_chip")
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JARVIS_AGENTS.filter { !it.requiresPc || brainConfig.pcPaired }.forEach { agent ->
                val selected = brainConfig.selectedAgentId == agent.id
                FilterChip(
                    selected = selected,
                    onClick = { onSelectAgent(agent.id) },
                    label = { Text("${agent.name} (${agent.handle})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = hud.accent.copy(alpha = 0.2f),
                        selectedLabelColor = hud.accent
                    ),
                    modifier = Modifier.testTag("agent_chip_${agent.id}")
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(hud.background)
                .border(1.dp, hud.borderSubtle, RoundedCornerShape(14.dp))
                .testTag("top_search_button")
                .clickable(onClick = onOpenPalette)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = hud.textSecondary, modifier = Modifier.size(18.dp))
            Text("Open Command Palette", style = MaterialTheme.typography.bodyMedium, color = hud.textPrimary)
        }

        Spacer(Modifier.height(8.dp))
    }
}
