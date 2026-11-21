package com.jarvis.app.ui.sheets

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.R
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.hudTextFieldColors
import com.jarvis.app.ui.theme.LocalJarvisColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemorySheet(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val playbooks by viewModel.playbooks.collectAsStateWithLifecycle()
    val threads by viewModel.archivedThreads.collectAsStateWithLifecycle()
    val brainConfig by viewModel.brainConfig.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    var newFactInput by remember { mutableStateOf("") }
    var confirmDeleteFactId by remember { mutableStateOf<Long?>(null) }
    var timelineCutoff by remember { mutableIntStateOf(100) }
    var newPlaybookName by remember { mutableStateOf("") }
    var newPlaybookSteps by remember { mutableStateOf("") }

    BackHandler(onBack = onClose)

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
                    Text("Memory", style = MaterialTheme.typography.titleLarge, color = hud.textPrimary, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.testTag("memory_close_button")) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_sheet), tint = hud.textPrimary)
                    }
                }

                TabRow(selectedTabIndex = selectedTab, containerColor = hud.surfacePanel, contentColor = hud.accent) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("About you (${facts.size})") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Playbooks (${playbooks.size})") })
                    Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Saved chats (${threads.size})") })
                }

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search memory…") },
                    singleLine = true,
                    colors = hudTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp).testTag("memory_search_input")
                )

                when (selectedTab) {
                    0 -> {
                        // About You
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newFactInput,
                                onValueChange = { newFactInput = it },
                                placeholder = { Text("Remember something about you…") },
                                singleLine = true,
                                colors = hudTextFieldColors(),
                                modifier = Modifier.weight(1f).testTag("memory_add_fact_input")
                            )
                            Button(
                                onClick = {
                                    if (newFactInput.isNotBlank()) {
                                        viewModel.addFact(newFactInput)
                                        newFactInput = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background),
                                modifier = Modifier.testTag("memory_remember_button")
                            ) { Text("Remember") }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Nightly memory digest", style = MaterialTheme.typography.bodyMedium, color = hud.textPrimary, modifier = Modifier.weight(1f))
                            Switch(
                                checked = brainConfig.nightlyDigestEnabled,
                                onCheckedChange = { enabled -> viewModel.updateBrain { it.copy(nightlyDigestEnabled = enabled) } }
                            )
                        }

                        val filteredFacts = remember(facts, search, timelineCutoff) {
                            val matching = if (search.isBlank()) facts else facts.filter { it.text.contains(search, true) }
                            val limit = ((timelineCutoff / 100f) * matching.size).toInt().coerceAtLeast(if (matching.isEmpty()) 0 else 1)
                            matching.take(limit)
                        }

                        if (facts.size > 3) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                                Text("Timeline scrubber (${filteredFacts.size}/${facts.size})", style = MaterialTheme.typography.labelSmall, color = hud.textSecondary)
                                Slider(
                                    value = timelineCutoff.toFloat(),
                                    onValueChange = { timelineCutoff = it.toInt() },
                                    valueRange = 10f..100f,
                                    colors = SliderDefaults.colors(thumbColor = hud.accent, activeTrackColor = hud.accent)
                                )
                            }
                        }

                        LazyColumn(
                            contentPadding = PaddingValues(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredFacts, key = { it.id }) { fact ->
                                val armed = confirmDeleteFactId == fact.id
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(hud.surfaceCard)
                                        .border(1.dp, hud.borderSubtle, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(fact.text, style = MaterialTheme.typography.bodyMedium, color = hud.textPrimary)
                                        Text(fact.category, style = MaterialTheme.typography.labelSmall, color = hud.accent)
                                    }
                                    if (armed) {
                                        TextButton(onClick = {
                                            viewModel.forgetFact(fact.id)
                                            confirmDeleteFactId = null
                                        }) { Text("Forget", color = hud.danger) }
                                    } else {
                                        IconButton(onClick = { confirmDeleteFactId = fact.id }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Forget fact", tint = hud.textSecondary)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // Playbooks
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newPlaybookName,
                                onValueChange = { newPlaybookName = it },
                                label = { Text("New playbook name") },
                                singleLine = true,
                                colors = hudTextFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newPlaybookSteps,
                                onValueChange = { newPlaybookSteps = it },
                                label = { Text("Procedure steps") },
                                colors = hudTextFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    if (newPlaybookName.isNotBlank() && newPlaybookSteps.isNotBlank()) {
                                        viewModel.addPlaybook(newPlaybookName, newPlaybookSteps)
                                        newPlaybookName = ""
                                        newPlaybookSteps = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Add Playbook")
                            }
                        }

                        val filteredPlaybooks = remember(playbooks, search) {
                            if (search.isBlank()) playbooks else playbooks.filter {
                                it.name.contains(search, true) || it.steps.contains(search, true)
                            }
                        }

                        LazyColumn(
                            contentPadding = PaddingValues(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredPlaybooks, key = { it.id }) { pb ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(hud.surfaceCard)
                                        .border(1.dp, hud.borderSubtle, RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(pb.name, style = MaterialTheme.typography.titleMedium, color = hud.textPrimary, modifier = Modifier.weight(1f))
                                        val nextStatus = when (pb.status) {
                                            "draft" -> "candidate"
                                            "candidate" -> "promoted"
                                            "promoted" -> "disabled"
                                            else -> "draft"
                                        }
                                        Text(
                                            text = pb.status,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = hud.accent,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .background(hud.accent.copy(alpha = 0.14f))
                                                .clickable { viewModel.updatePlaybookStatus(pb, nextStatus) }
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                        IconButton(onClick = { viewModel.deletePlaybook(pb.id) }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete playbook", tint = hud.textSecondary)
                                        }
                                    }
                                    Text(pb.steps, style = MaterialTheme.typography.bodySmall, color = hud.textSecondary)
                                }
                            }
                        }
                    }

                    else -> {
                        // Saved chats
                        val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
                        val filteredThreads = remember(threads, search) {
                            if (search.isBlank()) threads else threads.filter {
                                it.title.contains(search, true) || it.preview.contains(search, true)
                            }
                        }
                        LazyColumn(
                            contentPadding = PaddingValues(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredThreads, key = { it.id }) { thread ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(hud.surfaceCard)
                                        .border(1.dp, hud.borderSubtle, RoundedCornerShape(12.dp))
                                        .clickable {
                                            viewModel.restoreThread(thread.id)
                                            viewModel.openOverlay(OverlayType.CHAT)
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(thread.title, style = MaterialTheme.typography.titleSmall, color = hud.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${fmt.format(Date(thread.updatedAtMs))} · ${thread.messageCount} turns · ${thread.preview}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = hud.textSecondary,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { viewModel.deleteArchivedThread(thread.id) }) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete thread", tint = hud.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
