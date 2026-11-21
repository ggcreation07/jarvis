package com.jarvis.app.ui.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.R
import com.jarvis.app.domain.model.CAPABILITIES_CATALOG
import com.jarvis.app.domain.model.HandsFreeActivity
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.hudTextFieldColors
import com.jarvis.app.ui.theme.LocalJarvisColors

@Composable
fun SkillsSheet(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val qrBitmap by viewModel.qrResultBitmap.collectAsStateWithLifecycle()
    val clipboardPreview by viewModel.clipboardPreview.collectAsStateWithLifecycle()
    var qrInput by remember { mutableStateOf("https://github.com/ggcreation07/jarvis") }

    BackHandler(onBack = onClose)

    Surface(color = hud.background.copy(alpha = 0.98f), modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().widthIn(max = 680.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Skills & Quick Tools", style = MaterialTheme.typography.titleLarge, color = hud.textPrimary, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClose, modifier = Modifier.testTag("skills_close_button")) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_sheet), tint = hud.textPrimary)
                        }
                    }
                }

                item {
                    Text("Hands-free modes", style = MaterialTheme.typography.labelMedium, color = hud.accent)
                }

                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkillActionTile(
                            title = "Read text",
                            subtitle = "Paged reader",
                            icon = Icons.AutoMirrored.Rounded.MenuBook,
                            tag = "skill_read_text",
                            modifier = Modifier.weight(1f)
                        ) {
                            viewModel.selectHandsFreeActivity(HandsFreeActivity.READING)
                            viewModel.openOverlay(OverlayType.HANDS_FREE)
                        }
                        SkillActionTile(
                            title = "Follow steps",
                            subtitle = "Checklist + timer",
                            icon = Icons.Rounded.Checklist,
                            tag = "skill_follow_steps",
                            modifier = Modifier.weight(1f)
                        ) {
                            viewModel.selectHandsFreeActivity(HandsFreeActivity.STEPS)
                            viewModel.openOverlay(OverlayType.HANDS_FREE)
                        }
                    }
                }

                item {
                    SkillActionTile(
                        title = "All activities & hand gestures",
                        subtitle = "Camera hand-pose controls for voice, media, reading & photos",
                        icon = Icons.Rounded.PanTool,
                        tag = "skill_all_activities",
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        viewModel.openOverlay(OverlayType.HANDS_FREE)
                    }
                }

                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(hud.surfaceCard)
                            .border(1.dp, hud.borderSubtle, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.QrCode2, contentDescription = null, tint = hud.accent)
                            Spacer(Modifier.width(8.dp))
                            Text("QR Code Generator", style = MaterialTheme.typography.titleMedium, color = hud.textPrimary)
                        }
                        OutlinedTextField(
                            value = qrInput,
                            onValueChange = { qrInput = it },
                            label = { Text("URL or text") },
                            singleLine = true,
                            colors = hudTextFieldColors(),
                            modifier = Modifier.fillMaxWidth().testTag("skills_qr_input")
                        )
                        Button(
                            onClick = { viewModel.generateQrCode(qrInput) },
                            colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background),
                            modifier = Modifier.testTag("skills_qr_generate_button")
                        ) {
                            Text("Generate QR Code")
                        }
                        qrBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = stringResource(R.string.cd_qr_code),
                                modifier = Modifier
                                    .size(200.dp)
                                    .align(Alignment.CenterHorizontally)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .padding(8.dp)
                            )
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(hud.surfaceCard)
                            .border(1.dp, hud.borderSubtle, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = hud.accent)
                            Spacer(Modifier.width(8.dp))
                            Text("Clipboard Helper", style = MaterialTheme.typography.titleMedium, color = hud.textPrimary, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.refreshClipboard() }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh clipboard", tint = hud.accent)
                            }
                        }
                        Text(
                            text = clipboardPreview.ifBlank { "Clipboard is currently empty." },
                            style = MaterialTheme.typography.bodySmall,
                            color = hud.textSecondary,
                            maxLines = 4
                        )
                        if (clipboardPreview.isNotBlank()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    viewModel.prefillChatPrompt("Summarize this:\n$clipboardPreview")
                                }) {
                                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Ask JARVIS")
                                }
                                OutlinedButton(onClick = {
                                    viewModel.deviceController.copyToClipboard("JARVIS", clipboardPreview)
                                    viewModel.showBanner("Copied to clipboard.")
                                }) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val hud = LocalJarvisColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(hud.surfaceCard)
            .border(1.dp, hud.borderSubtle, RoundedCornerShape(16.dp))
            .testTag(tag)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = hud.accent, modifier = Modifier.size(24.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = hud.textPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = hud.textSecondary)
    }
}

@Composable
fun CapabilitiesSheet(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    var search by remember { mutableStateOf("") }
    BackHandler(onBack = onClose)

    val filtered = remember(search) {
        if (search.isBlank()) CAPABILITIES_CATALOG
        else CAPABILITIES_CATALOG.filter {
            it.category.contains(search, true) ||
                it.title.contains(search, true) ||
                it.prompt.contains(search, true)
        }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.category } }

    Surface(color = hud.background.copy(alpha = 0.98f), modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(modifier = Modifier.fillMaxSize().widthIn(max = 680.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Capabilities", style = MaterialTheme.typography.titleLarge, color = hud.textPrimary, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.testTag("capabilities_close_button")) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_sheet), tint = hud.textPrimary)
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search what JARVIS can do…") },
                    singleLine = true,
                    colors = hudTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).testTag("capabilities_search_input")
                )
                LazyColumn(
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    grouped.forEach { (category, examples) ->
                        item(key = "cat_$category") {
                            Text(category, style = MaterialTheme.typography.labelMedium, color = hud.accent)
                        }
                        items(examples, key = { "${it.category}_${it.title}" }) { ex ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(hud.surfaceCard)
                                    .border(1.dp, hud.borderSubtle, RoundedCornerShape(14.dp))
                                    .clickable { viewModel.prefillChatPrompt(ex.prompt) }
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(ex.title, style = MaterialTheme.typography.titleSmall, color = hud.textPrimary)
                                Text("\"${ex.prompt}\"", style = MaterialTheme.typography.bodySmall, color = hud.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}
