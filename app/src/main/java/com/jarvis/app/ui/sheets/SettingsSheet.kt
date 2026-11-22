package com.jarvis.app.ui.sheets

import android.Manifest
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.R
import com.jarvis.app.data.ai.KeyCheckStatus
import com.jarvis.app.data.security.SecureSecretStore
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.domain.model.PROVIDER_CATALOG
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.hudTextFieldColors
import com.jarvis.app.ui.theme.LocalJarvisColors

@Composable
fun SettingsSheet(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val context = LocalContext.current
    val brain by viewModel.brainConfig.collectAsStateWithLifecycle()
    val keyStatuses by viewModel.keyStatuses.collectAsStateWithLifecycle()
    val quotaSnapshots by viewModel.quotaSnapshots.collectAsStateWithLifecycle()
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsStateWithLifecycle()
    val notificationEnabled by viewModel.notificationAccessEnabled.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetryState.collectAsStateWithLifecycle()

    var search by remember { mutableStateOf("") }
    var pinnedCityInput by remember(brain.pinnedLocation) { mutableStateOf(brain.pinnedLocation) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
            }
            viewModel.setGrantedFolder(uri)
        }
    }

    val calendarPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.updateBrain { it.copy(calendarSyncEnabled = granted) }
        viewModel.refreshCalendarIfEnabled()
    }

    val locationPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.refreshTelemetryAndWeather()
    }

    val micPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.showBanner("Microphone permission granted.")
    }

    BackHandler(onBack = onClose)

    val matches: (String) -> Boolean = { title ->
        search.isBlank() || title.contains(search, ignoreCase = true)
    }

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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Settings", style = MaterialTheme.typography.titleLarge, color = hud.textPrimary, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClose, modifier = Modifier.testTag("settings_close_button")) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_sheet), tint = hud.textPrimary)
                        }
                    }
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search settings…") },
                        singleLine = true,
                        colors = hudTextFieldColors(),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("settings_search_input")
                    )
                }

                if (matches("Intelligence AI Model Provider Gemini Firebase")) {
                    item {
                        SectionCard("Intelligence & Model Routing") {
                            Text(
                                "Built-in Firebase AI Logic runs out of the box (no key needed). You can also switch or add optional BYO keys below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = hud.textSecondary
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PROVIDER_CATALOG.forEach { p ->
                                    FilterChip(
                                        selected = brain.activeProvider == p.id,
                                        onClick = {
                                            viewModel.updateBrain { it.copy(activeProvider = p.id, activeModel = p.defaultModel) }
                                        },
                                        label = { Text(p.label.substringBefore(" (")) },
                                        colors = hudFilterChipColors()
                                    )
                                }
                            }
                            val currentProvider = PROVIDER_CATALOG.firstOrNull { it.id == brain.activeProvider } ?: PROVIDER_CATALOG.first()
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                currentProvider.models.forEach { m ->
                                    FilterChip(
                                        selected = brain.activeModel == m,
                                        onClick = { viewModel.updateBrain { it.copy(activeModel = m) } },
                                        label = { Text(m) },
                                        colors = hudFilterChipColors()
                                    )
                                }
                            }
                        }
                    }
                }

                if (matches("API Keys Encrypted Keystore Groq OpenRouter NVIDIA Mistral Tavily")) {
                    item {
                        SectionCard("Optional BYO API Keys (Keystore Encrypted)") {
                            val entries = listOf(
                                Triple(SecureSecretStore.KEY_GROQ, "groq", "Groq API Key"),
                                Triple(SecureSecretStore.KEY_GEMINI, "gemini", "Google AI Studio Key"),
                                Triple(SecureSecretStore.KEY_OPENROUTER, "openrouter", "OpenRouter API Key"),
                                Triple(SecureSecretStore.KEY_NVIDIA, "nvidia", "NVIDIA NIM API Key"),
                                Triple(SecureSecretStore.KEY_MISTRAL, "mistral", "Mistral API Key"),
                                Triple(SecureSecretStore.KEY_TAVILY, "tavily", "Tavily Search API Key")
                            )
                            entries.forEach { (secretKey, providerId, label) ->
                                ApiKeyEntryRow(
                                    label = label,
                                    secretKey = secretKey,
                                    status = keyStatuses[secretKey] ?: KeyCheckStatus.EMPTY,
                                    viewModel = viewModel,
                                    onSave = { value -> viewModel.saveAndVerifyApiKey(secretKey, providerId, value) }
                                )
                            }
                        }
                    }
                }

                if (matches("Routing & Quota Usage")) {
                    item {
                        SectionCard("Routing & Quota") {
                            if (quotaSnapshots.isEmpty()) {
                                Text("No external BYO quota traffic recorded in this session.", style = MaterialTheme.typography.bodySmall, color = hud.textSecondary)
                            } else {
                                quotaSnapshots.forEach { q ->
                                    Text(
                                        "${q.providerId}: ${q.requestsToday} requests · ${q.lastStatus}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = hud.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                if (matches("Voice Speech Microphone TTS Mute Wake")) {
                    item {
                        SectionCard("Voice & Audio & Permissions") {
                            val hasMic = remember(telemetry) {
                                android.content.pm.PackageManager.PERMISSION_GRANTED == androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                )
                            }
                            Text(
                                text = "Microphone Permission: ${if (hasMic) "Granted" else "Not Granted"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hasMic) hud.success else hud.danger
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!hasMic) {
                                    Button(
                                        onClick = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                        colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
                                    ) {
                                        Text("Request Mic")
                                    }
                                } else {
                                    OutlinedButton(onClick = { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                                        Text("Re-request Mic")
                                    }
                                }
                                OutlinedButton(onClick = { viewModel.openDefaultAssistantSettings() }) {
                                    Text("Default Assistant Settings")
                                }
                            }

                            Text(
                                text = "Speech recognition: ${if (viewModel.voiceController.isSpeechRecognitionAvailable()) "Available" else "Unavailable on device"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = hud.textSecondary
                            )
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Speak replies aloud (TTS)", style = MaterialTheme.typography.bodyMedium, color = hud.textPrimary, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = brain.voiceTtsEnabled,
                                    onCheckedChange = { on -> viewModel.updateBrain { it.copy(voiceTtsEnabled = on) } }
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Mute all spoken audio", style = MaterialTheme.typography.bodyMedium, color = hud.textPrimary, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = brain.muted,
                                    onCheckedChange = { m -> viewModel.updateBrain { it.copy(muted = m) } }
                                )
                            }
                            Text("Wake sensitivity (${brain.wakeSensitivity}%)", style = MaterialTheme.typography.labelMedium, color = hud.textSecondary)
                            Slider(
                                value = brain.wakeSensitivity.toFloat(),
                                onValueChange = { v -> viewModel.updateBrain { it.copy(wakeSensitivity = v.toInt()) } },
                                valueRange = 10f..100f,
                                colors = SliderDefaults.colors(thumbColor = hud.accent, activeTrackColor = hud.accent)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.voiceController.speak("J.A.R.V.I.S voice systems online.") }) {
                                    Text("Test Speech")
                                }
                            }
                        }
                    }
                }

                if (matches("Activities Gestures Accessibility Notifications")) {
                    item {
                        SectionCard("Activities, Gestures & System Helpers") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.openOverlay(OverlayType.HANDS_FREE) }) {
                                    Text("Hands-free workspace")
                                }
                            }
                            Text(
                                text = "Notification Reader: ${if (notificationEnabled) "Granted" else "Not Granted"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (notificationEnabled) hud.success else hud.danger
                            )
                            if (!notificationEnabled) {
                                Button(
                                    onClick = { viewModel.openNotificationListenerSettings() },
                                    colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
                                ) {
                                    Text("Open Notification Settings")
                                }
                            } else {
                                OutlinedButton(onClick = { viewModel.openNotificationListenerSettings() }) {
                                    Text("Open Notification Settings")
                                }
                            }

                            Text(
                                text = "Accessibility Phone Control: ${if (accessibilityEnabled) "Granted" else "Not Granted"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (accessibilityEnabled) hud.success else hud.danger
                            )
                            if (!accessibilityEnabled) {
                                Button(
                                    onClick = { viewModel.openAccessibilitySettings() },
                                    colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
                                ) {
                                    Text("Open Accessibility Settings")
                                }
                            } else {
                                OutlinedButton(onClick = { viewModel.openAccessibilitySettings() }) {
                                    Text("Open Accessibility Settings")
                                }
                            }
                        }
                    }
                }

                if (matches("Interface Customize Quick Start")) {
                    item {
                        SectionCard("Interface") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.openOverlay(OverlayType.CUSTOMIZE) }) {
                                    Text("Customize HUD")
                                }
                                OutlinedButton(onClick = { viewModel.openOverlay(OverlayType.QUICK_START) }) {
                                    Text("Quick Start")
                                }
                            }
                        }
                    }
                }

                if (matches("Files Folder SAF Storage")) {
                    item {
                        SectionCard("Files & Granted Folder") {
                            Text(
                                if (brain.grantedFolderUri.isBlank()) "No folder granted yet."
                                else "Granted: ${brain.grantedFolderName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = hud.textSecondary
                            )
                            OutlinedButton(onClick = { folderPicker.launch(null) }) {
                                Text("Grant Folder Access")
                            }
                        }
                    }
                }

                if (matches("Location Weather GPS City")) {
                    item {
                        SectionCard("Location & Weather") {
                            OutlinedTextField(
                                value = pinnedCityInput,
                                onValueChange = { pinnedCityInput = it },
                                label = { Text("Pinned city (blank uses GPS / IP)") },
                                singleLine = true,
                                colors = hudTextFieldColors(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        viewModel.updateBrain { it.copy(pinnedLocation = pinnedCityInput.trim()) }
                                        viewModel.refreshTelemetryAndWeather()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
                                ) { Text("Save & Refresh") }
                                OutlinedButton(onClick = { locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                                    Text("Use GPS")
                                }
                            }
                        }
                    }
                }

                if (matches("Calendar Agenda Events")) {
                    item {
                        SectionCard("Calendar") {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Show Android Calendar in Today's Agenda", style = MaterialTheme.typography.bodyMedium, color = hud.textPrimary, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = brain.calendarSyncEnabled,
                                    onCheckedChange = { enable ->
                                        if (enable && !viewModel.deviceController.hasCalendarPermission()) {
                                            calendarPermLauncher.launch(Manifest.permission.READ_CALENDAR)
                                        } else {
                                            viewModel.updateBrain { it.copy(calendarSyncEnabled = enable) }
                                            viewModel.refreshCalendarIfEnabled()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                if (matches("Devices Remote PC TV Identity")) {
                    item {
                        SectionCard("Devices & Pairing") {
                            val info = remember { viewModel.deviceIdentity() }
                            Text("Device ID: ${info.deviceId}", style = MaterialTheme.typography.labelSmall, color = hud.accent)
                            Text("Fingerprint: ${info.fingerprint}", style = MaterialTheme.typography.labelSmall, color = hud.textSecondary)
                            OutlinedButton(onClick = { viewModel.openOverlay(OverlayType.REMOTE_PC) }) {
                                Text("Configure Remote PC & Android TV")
                            }
                        }
                    }
                }

                if (matches("System Diagnostics App Check Telemetry")) {
                    item {
                        SectionCard("System & Diagnostics") {
                            Text(
                                "RAM ${telemetry.ramUsedMb}/${telemetry.ramTotalMb} MB · Battery ${telemetry.batteryPercent}% · Network ${telemetry.network.transport}",
                                style = MaterialTheme.typography.bodySmall,
                                color = hud.textSecondary
                            )
                            Text("App Check: Active for Firebase AI Logic", style = MaterialTheme.typography.bodySmall, color = hud.success)
                            OutlinedButton(onClick = {
                                viewModel.refreshServiceStatus()
                                viewModel.refreshTelemetryAndWeather()
                                viewModel.refreshKeyStatuses()
                            }) { Text("Refresh diagnostics") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiKeyEntryRow(
    label: String,
    secretKey: String,
    status: KeyCheckStatus,
    viewModel: JarvisViewModel,
    onSave: (String) -> Unit
) {
    val hud = LocalJarvisColors.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(secretKey) {
        text = viewModel.secretStore.getSecret(secretKey)
    }

    val (badgeLabel, badgeColor) = when (status) {
        KeyCheckStatus.EMPTY -> "Not added" to hud.textSecondary
        KeyCheckStatus.CHECKING -> "Checking…" to hud.warning
        KeyCheckStatus.WORKING -> "Working" to hud.success
        KeyCheckStatus.REJECTED -> "Rejected" to hud.danger
        KeyCheckStatus.UNVERIFIED -> "Saved" to hud.accent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(hud.surfacePanel)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = hud.textPrimary, modifier = Modifier.weight(1f))
            Text(badgeLabel, style = MaterialTheme.typography.labelSmall, color = badgeColor)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Paste key or leave blank") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                colors = hudTextFieldColors(),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { onSave(text) },
                colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
            ) { Text("Save") }
        }
    }
}
