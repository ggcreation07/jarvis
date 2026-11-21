package com.jarvis.app.ui.sheets

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.R
import com.jarvis.app.domain.model.ChatMessageItem
import com.jarvis.app.domain.model.HudStatus
import com.jarvis.app.domain.model.JARVIS_AGENTS
import com.jarvis.app.domain.model.ToolActivityItem
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.RichResponseRenderer
import com.jarvis.app.ui.theme.LocalJarvisColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatSheet(
    viewModel: JarvisViewModel,
    onRequestMicAndToggleVoice: () -> Unit,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val brainConfig by viewModel.brainConfig.collectAsStateWithLifecycle()
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val streamingMsg by viewModel.streamingMessage.collectAsStateWithLifecycle()
    val archivedThreads by viewModel.archivedThreads.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val status by viewModel.hudStatus.collectAsStateWithLifecycle()
    val attachedImage by viewModel.attachedImage.collectAsStateWithLifecycle()
    val generatedBitmaps by viewModel.generatedBitmaps.collectAsStateWithLifecycle()
    val prefill by viewModel.prefillInput.collectAsStateWithLifecycle()

    var input by remember { mutableStateOf("") }
    var showRecents by remember { mutableStateOf(false) }
    var recentsSearch by remember { mutableStateOf("") }

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

    BackHandler {
        if (showRecents) showRecents = false else onClose()
    }

    val allMessages = remember(turns, streamingMsg) {
        if (streamingMsg != null) turns + streamingMsg!! else turns
    }
    val listState = rememberLazyListState()
    LaunchedEffect(allMessages.size, streamingMsg?.content?.length) {
        if (allMessages.isNotEmpty() && !showRecents) {
            listState.animateScrollToItem(allMessages.lastIndex)
        }
    }

    Surface(
        color = hud.background.copy(alpha = 0.98f),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (showRecents) showRecents = false else onClose() },
                        modifier = Modifier.testTag("chat_back_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = hud.textPrimary
                        )
                    }
                    Text(
                        text = if (showRecents) "Recent Chats" else "Conversation",
                        style = MaterialTheme.typography.titleLarge,
                        color = hud.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { showRecents = !showRecents },
                        modifier = Modifier.testTag("chat_recents_button")
                    ) {
                        Icon(Icons.Rounded.History, contentDescription = "Recent chats", tint = hud.accent)
                    }
                    IconButton(
                        onClick = {
                            showRecents = false
                            viewModel.startNewChat()
                        },
                        modifier = Modifier.testTag("chat_new_button")
                    ) {
                        Icon(Icons.Rounded.AddComment, contentDescription = "New chat", tint = hud.accent)
                    }
                }

                // Mode & Agents Strip
                if (!showRecents) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = brainConfig.conversationMode,
                            onClick = { viewModel.updateBrain { it.copy(conversationMode = !it.conversationMode) } },
                            label = { Text("Conversation Mode") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = hud.accent.copy(alpha = 0.22f),
                                selectedLabelColor = hud.accent
                            ),
                            modifier = Modifier.testTag("chat_conversation_mode_chip")
                        )
                        FilterChip(
                            selected = brainConfig.agentsMode,
                            onClick = { viewModel.updateBrain { it.copy(agentsMode = !it.agentsMode) } },
                            label = { Text("Agents") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = hud.secondaryAccent.copy(alpha = 0.22f),
                                selectedLabelColor = hud.accent
                            ),
                            modifier = Modifier.testTag("chat_agents_mode_chip")
                        )
                    }

                    if (brainConfig.agentsMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            JARVIS_AGENTS.filter { !it.requiresPc || brainConfig.pcPaired }.forEach { agent ->
                                val selected = brainConfig.selectedAgentId == agent.id
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.updateBrain { it.copy(selectedAgentId = agent.id) } },
                                    label = { Text(agent.handle) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = hud.accent.copy(alpha = 0.2f),
                                        selectedLabelColor = hud.accent
                                    ),
                                    modifier = Modifier.testTag("agent_chip_${agent.id}")
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = hud.borderSubtle)
                }

                if (showRecents) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = recentsSearch,
                            onValueChange = { recentsSearch = it },
                            placeholder = { Text("Search saved chats…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("recents_search_input")
                        )
                        val filtered = remember(archivedThreads, recentsSearch) {
                            if (recentsSearch.isBlank()) archivedThreads
                            else archivedThreads.filter {
                                it.title.contains(recentsSearch, true) || it.preview.contains(recentsSearch, true)
                            }
                        }
                        if (filtered.isEmpty()) {
                            Text("No archived chats yet.", style = MaterialTheme.typography.bodyMedium, color = hud.textSecondary)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(filtered, key = { it.id }) { thread ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(hud.surfaceCard)
                                            .border(1.dp, hud.borderSubtle, RoundedCornerShape(14.dp))
                                            .clickable {
                                                viewModel.restoreThread(thread.id)
                                                showRecents = false
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(thread.title, style = MaterialTheme.typography.titleSmall, color = hud.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${thread.messageCount} messages · ${thread.preview}", style = MaterialTheme.typography.bodySmall, color = hud.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                        IconButton(onClick = { viewModel.deleteArchivedThread(thread.id) }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete thread", tint = hud.textSecondary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (allMessages.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(top = 36.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("Ready when you are.", style = MaterialTheme.typography.titleLarge, color = hud.accent)
                                    Text(
                                        "Ask a question, attach a photo, or tag @phone / @research / @scheduler / @content.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = hud.textSecondary
                                    )
                                }
                            }
                        }
                        items(allMessages, key = { "${it.id}_${it.timestampMs}_${it.isStreaming}" }) { msg ->
                            ChatBubble(
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

                    // Attachment pill
                    attachedImage?.let { (label, _) ->
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(hud.accent.copy(alpha = 0.14f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = hud.accent)
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Remove attached photo",
                                tint = hud.accent,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { viewModel.clearAttachedImage() }
                            )
                        }
                    }

                    // Bottom Composer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(hud.surfaceCard)
                            .border(1.dp, hud.borderSubtle, RoundedCornerShape(18.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.testTag("chat_attach_photo_button")
                        ) {
                            Icon(
                                Icons.Rounded.AttachFile,
                                contentDescription = stringResource(R.string.cd_attach_image),
                                tint = hud.textSecondary
                            )
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("Message JARVIS…", color = hud.textSecondary) },
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (input.isNotBlank() || attachedImage != null) {
                                    viewModel.sendUserMessage(input)
                                    input = ""
                                }
                            }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = hud.textPrimary,
                                unfocusedTextColor = hud.textPrimary,
                                cursorColor = hud.accent
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_composer_input")
                        )
                        IconButton(
                            onClick = onRequestMicAndToggleVoice,
                            modifier = Modifier.testTag("chat_mic_button")
                        ) {
                            Icon(
                                Icons.Rounded.Mic,
                                contentDescription = stringResource(R.string.cd_mic_button),
                                tint = if (status == HudStatus.LISTENING) hud.danger else hud.accent
                            )
                        }
                        IconButton(
                            onClick = {
                                if (isBusy) {
                                    viewModel.stopCurrentWork()
                                } else if (input.isNotBlank() || attachedImage != null) {
                                    viewModel.sendUserMessage(input)
                                    input = ""
                                }
                            },
                            modifier = Modifier.testTag("chat_send_button")
                        ) {
                            Icon(
                                if (isBusy) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                                contentDescription = if (isBusy) stringResource(R.string.cd_stop_speaking) else stringResource(R.string.cd_send_message),
                                tint = if (isBusy) hud.danger else hud.accent
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(
    msg: ChatMessageItem,
    bitmap: android.graphics.Bitmap?,
    onCopy: () -> Unit,
    onSpeak: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val isUser = msg.role == "user"
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (!isUser && msg.agentId.isNotBlank() && msg.agentId != "auto") {
            Text(
                text = "@${msg.agentId}",
                style = MaterialTheme.typography.labelSmall,
                color = hud.accent,
                modifier = Modifier.padding(bottom = 3.dp, start = 4.dp)
            )
        }
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when {
                        msg.isError -> hud.danger.copy(alpha = 0.14f)
                        isUser -> hud.accent.copy(alpha = 0.18f)
                        else -> hud.surfaceCard
                    }
                )
                .border(
                    1.dp,
                    if (isUser) hud.accent.copy(alpha = 0.35f) else hud.borderSubtle,
                    RoundedCornerShape(16.dp)
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (msg.attachmentLabel.isNotBlank()) {
                Text("Image: ${msg.attachmentLabel}", style = MaterialTheme.typography.labelSmall, color = hud.accent)
            }
            if (msg.toolActivities.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    msg.toolActivities.forEach { act ->
                        ToolActivityRow(act)
                    }
                }
            }
            if (msg.content.isNotBlank()) {
                RichResponseRenderer(content = msg.content, textColor = hud.textPrimary)
            } else if (msg.isStreaming) {
                Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = hud.textSecondary)
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = if (msg.qrText.isNotBlank()) stringResource(R.string.cd_qr_code) else stringResource(R.string.cd_generated_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = if (msg.qrText.isNotBlank()) 1f else 0.04f))
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    fmt.format(Date(msg.timestampMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = hud.textSecondary
                )
                if (!isUser && !msg.isStreaming && msg.content.isNotBlank()) {
                    Row {
                        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy reply", tint = hud.textSecondary, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onSpeak, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Read reply aloud", tint = hud.textSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolActivityRow(act: ToolActivityItem) {
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
            .clip(RoundedCornerShape(8.dp))
            .background(hud.surfacePanel)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(7.dp))
        Text(act.label, style = MaterialTheme.typography.labelSmall, color = hud.textPrimary, modifier = Modifier.weight(1f))
        if (act.durationMs > 0) {
            Text("${act.durationMs}ms", style = MaterialTheme.typography.labelSmall, color = hud.textSecondary)
        }
    }
}
