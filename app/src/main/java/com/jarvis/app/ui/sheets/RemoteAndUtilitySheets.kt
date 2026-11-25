package com.jarvis.app.ui.sheets

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Wifi
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.R
import com.jarvis.app.domain.model.JARVIS_AGENTS
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.hudTextFieldColors
import com.jarvis.app.ui.theme.LocalJarvisColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RemotePcAndTvSheet(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val brain by viewModel.brainConfig.collectAsStateWithLifecycle()
    val remoteStatus by viewModel.remoteConnectionStatus.collectAsStateWithLifecycle()

    var pcHost by remember(brain.pcHost) { mutableStateOf(brain.pcHost) }
    var pcPort by remember(brain.pcPort) { mutableStateOf(brain.pcPort.toString()) }
    var pcMac by remember(brain.pcMacAddress) { mutableStateOf(brain.pcMacAddress) }
    var pcGoal by remember { mutableStateOf("") }

    var tvHost by remember(brain.tvHost) { mutableStateOf(brain.tvHost) }
    var tvPort by remember(brain.tvPort) { mutableStateOf(brain.tvPort.toString()) }

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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Remote PC & Android TV", style = MaterialTheme.typography.titleLarge, color = hud.textPrimary, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClose, modifier = Modifier.testTag("remote_close_button")) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_sheet), tint = hud.textPrimary)
                        }
                    }
                }

                item {
                    SectionCard("Windows / Linux Companion PC") {
                        Text("Status: $remoteStatus", style = MaterialTheme.typography.bodySmall, color = hud.accent)
                        OutlinedTextField(
                            value = pcHost,
                            onValueChange = { pcHost = it },
                            label = { Text("PC IP or Tailscale hostname") },
                            singleLine = true,
                            colors = hudTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = pcPort,
                                onValueChange = { pcPort = it },
                                label = { Text("Port") },
                                singleLine = true,
                                colors = hudTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = pcMac,
                                onValueChange = { pcMac = it },
                                label = { Text("MAC (for Wake-on-LAN)") },
                                singleLine = true,
                                colors = hudTextFieldColors(),
                                modifier = Modifier.weight(2f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val p = pcPort.toIntOrNull() ?: 8765
                                    viewModel.updateBrain { it.copy(pcHost = pcHost.trim(), pcPort = p, pcMacAddress = pcMac.trim()) }
                                    viewModel.testRemotePc(pcHost, p)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
                            ) { Text("Test PC") }
                            OutlinedButton(onClick = {
                                viewModel.updateBrain { it.copy(pcHost = pcHost.trim(), pcMacAddress = pcMac.trim()) }
                                viewModel.wakeConfiguredPc()
                            }) { Text("Wake PC") }
                            OutlinedButton(onClick = { viewModel.openOverlay(OverlayType.REMOTE_DESKTOP) }) {
                                Text("Live Pad")
                            }
                        }
                        OutlinedTextField(
                            value = pcGoal,
                            onValueChange = { pcGoal = it },
                            label = { Text("Quick PC command (e.g. Open Notepad)") },
                            singleLine = true,
                            colors = hudTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(onClick = {
                            if (pcGoal.isNotBlank()) {
                                viewModel.sendUserMessage("@desktop $pcGoal", openChatIfHome = true)
                                pcGoal = ""
                            }
                        }) { Text("Run on PC") }
                    }
                }

                item {
                    SectionCard("Android TV Control") {
                        OutlinedTextField(
                            value = tvHost,
                            onValueChange = { tvHost = it },
                            label = { Text("TV IP address") },
                            singleLine = true,
                            colors = hudTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = tvPort,
                            onValueChange = { tvPort = it },
                            label = { Text("ADB port (default 5555)") },
                            singleLine = true,
                            colors = hudTextFieldColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val p = tvPort.toIntOrNull() ?: 5555
                                    viewModel.testAndroidTv(tvHost, p)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
                            ) { Text("Test TV") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteDesktopScreen(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    var armed by remember { mutableStateOf(false) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var textInput by remember { mutableStateOf("") }

    BackHandler(onBack = onClose)

    Surface(color = hud.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = hud.textPrimary)
                }
                Text("Remote Desktop Pad", style = MaterialTheme.typography.titleLarge, color = hud.textPrimary, modifier = Modifier.weight(1f))
                Button(
                    onClick = { armed = !armed },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (armed) hud.success else hud.surfaceCard,
                        contentColor = if (armed) hud.background else hud.textPrimary
                    )
                ) { Text(if (armed) "Armed" else "Arm Pad") }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(hud.surfaceCard)
                    .border(1.5.dp, if (armed) hud.accent else hud.borderSubtle, RoundedCornerShape(18.dp))
                    .onSizeChanged { padSize = it }
                    .pointerInput(armed, padSize) {
                        detectTapGestures(
                            onTap = { pos ->
                                if (armed && padSize.width > 0 && padSize.height > 0) {
                                    val nx = ((pos.x / padSize.width) * 1000).toInt().coerceIn(0, 1000)
                                    val ny = ((pos.y / padSize.height) * 1000).toInt().coerceIn(0, 1000)
                                    viewModel.sendRemoteDesktopInput("click", nx, ny)
                                }
                            },
                            onDoubleTap = { pos ->
                                if (armed && padSize.width > 0 && padSize.height > 0) {
                                    val nx = ((pos.x / padSize.width) * 1000).toInt().coerceIn(0, 1000)
                                    val ny = ((pos.y / padSize.height) * 1000).toInt().coerceIn(0, 1000)
                                    viewModel.sendRemoteDesktopInput("double_click", nx, ny)
                                }
                            },
                            onLongPress = { pos ->
                                if (armed && padSize.width > 0 && padSize.height > 0) {
                                    val nx = ((pos.x / padSize.width) * 1000).toInt().coerceIn(0, 1000)
                                    val ny = ((pos.y / padSize.height) * 1000).toInt().coerceIn(0, 1000)
                                    viewModel.sendRemoteDesktopInput("right_click", nx, ny)
                                }
                            }
                        )
                    }
                    .pointerInput(armed, padSize) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (armed) {
                                viewModel.sendRemoteDesktopInput("move_rel", dragAmount.x.toInt(), dragAmount.y.toInt())
                            }
                        }
                    }
            ) {
                Text(
                    if (armed) "Touch, drag, double-tap, or long-press to control paired PC"
                    else "Tap 'Arm Pad' to enable live touch controls",
                    style = MaterialTheme.typography.bodyMedium,
                    color = hud.textSecondary
                )
            }

            // Virtual WASD + special keys
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("W", "A", "S", "D", "Space", "Enter", "Esc").forEach { key ->
                    OutlinedButton(
                        enabled = armed,
                        onClick = { viewModel.sendRemoteDesktopInput("key", text = key.lowercase()) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(key, style = MaterialTheme.typography.labelSmall) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    placeholder = { Text("Type text to PC…") },
                    singleLine = true,
                    colors = hudTextFieldColors(),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = armed && textInput.isNotBlank(),
                    onClick = {
                        viewModel.sendRemoteDesktopInput("type", text = textInput)
                        textInput = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
                ) { Text("Send") }
            }
        }
    }
}

@Composable
fun DevicePowerSheet(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val telemetry by viewModel.telemetryState.collectAsStateWithLifecycle()
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsStateWithLifecycle()
    val accessibilityConnected by viewModel.accessibilityConnected.collectAsStateWithLifecycle()

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
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Device & Power", style = MaterialTheme.typography.titleLarge, color = hud.textPrimary, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClose, modifier = Modifier.testTag("power_close_button")) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_sheet), tint = hud.textPrimary)
                        }
                    }
                }

                item {
                    SectionCard("Quick Controls") {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val res = viewModel.deviceController.toggleFlashlight()
                                    viewModel.showBanner(res.summary)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.FlashlightOn, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Flashlight")
                            }
                            OutlinedButton(
                                onClick = {
                                    val res = viewModel.deviceController.setDoNotDisturb(true)
                                    viewModel.showBanner(res.summary)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.NotificationsOff, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("DND On")
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.openAndroidSettings(Settings.ACTION_WIFI_SETTINGS) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Wifi, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Wi-Fi")
                            }
                            OutlinedButton(
                                onClick = { viewModel.openAndroidSettings(Settings.ACTION_BLUETOOTH_SETTINGS) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Bluetooth, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Bluetooth")
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.deviceController.adjustVolume("down") },
                                modifier = Modifier.weight(1f)
                            ) { Text("Vol -") }
                            OutlinedButton(
                                onClick = { viewModel.deviceController.adjustVolume("mute") },
                                modifier = Modifier.weight(1f)
                            ) { Text("Mute") }
                            OutlinedButton(
                                onClick = { viewModel.deviceController.adjustVolume("up") },
                                modifier = Modifier.weight(1f)
                            ) { Text("Vol +") }
                        }
                    }
                }

                item {
                    SectionCard("Hardware Health") {
                        Text(
                            "Battery: ${telemetry.batteryPercent}% (${if (telemetry.isCharging) "Charging" else "Discharging"}, ${telemetry.batteryTempC}°C)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = hud.textPrimary
                        )
                        Text(
                            "RAM: ${telemetry.ramUsedMb}/${telemetry.ramTotalMb} MB (${telemetry.ramPercent}%)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = hud.textSecondary
                        )
                        val accessibilityStatusText = when {
                            accessibilityConnected -> "Connected"
                            accessibilityEnabled -> "Enabled but disconnected — reconnect in Accessibility Settings"
                            else -> "Not granted"
                        }
                        val accessibilityStatusColor = when {
                            accessibilityConnected -> hud.success
                            accessibilityEnabled -> hud.warning
                            else -> hud.danger
                        }
                        Text(
                            "Accessibility Phone Control: $accessibilityStatusText",
                            style = MaterialTheme.typography.bodySmall,
                            color = accessibilityStatusColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CommandPaletteDialog(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    BackHandler(onBack = onClose)

    val staticCommands = remember {
        listOf(
            "Open Conversation" to { viewModel.openOverlay(OverlayType.CHAT) },
            "Open Memory & Playbooks" to { viewModel.openOverlay(OverlayType.MEMORY) },
            "Open Skills & QR Generator" to { viewModel.openOverlay(OverlayType.SKILLS) },
            "Open Capabilities Catalog" to { viewModel.openOverlay(OverlayType.CAPABILITIES) },
            "Open Hands-Free & Gestures" to { viewModel.openOverlay(OverlayType.HANDS_FREE) },
            "Customize HUD Look" to { viewModel.openOverlay(OverlayType.CUSTOMIZE) },
            "Open Settings" to { viewModel.openOverlay(OverlayType.SETTINGS) },
            "Open Remote PC & TV" to { viewModel.openOverlay(OverlayType.REMOTE_PC) },
            "Open Device & Power" to { viewModel.openOverlay(OverlayType.DEVICE_POWER) },
            "Switch to Iron Man Look" to { viewModel.applyBuiltInLook("ironman"); onClose() },
            "Switch to Modern Look" to { viewModel.applyBuiltInLook("modern"); onClose() },
            "Toggle Flashlight" to { viewModel.deviceController.toggleFlashlight(); onClose() }
        )
    }

    val filtered = remember(query, routines) {
        val routineItems = routines.map { r -> "Run routine: ${r.name}" to { viewModel.runRoutine(r) } }
        val agentItems = JARVIS_AGENTS.map { a -> "Switch agent to ${a.handle} (${a.name})" to {
            viewModel.updateBrain { it.copy(agentsMode = true, selectedAgentId = a.id) }
            viewModel.openOverlay(OverlayType.CHAT)
        } }
        val all = staticCommands + routineItems + agentItems
        if (query.isBlank()) all else all.filter { it.first.contains(query, ignoreCase = true) }
    }

    Surface(color = hud.background.copy(alpha = 0.96f), modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(modifier = Modifier.fillMaxSize().widthIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Command Palette", style = MaterialTheme.typography.titleLarge, color = hud.accent, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.testTag("palette_close_button")) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_sheet), tint = hud.textPrimary)
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Jump to anything or ask JARVIS…") },
                    singleLine = true,
                    colors = hudTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("palette_search_input")
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (query.isNotBlank()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(hud.accent.copy(alpha = 0.18f))
                                    .border(1.dp, hud.accent, RoundedCornerShape(12.dp))
                                    .clickable { viewModel.sendUserMessage(query, openChatIfHome = true) }
                                    .padding(12.dp)
                            ) {
                                Text("Ask JARVIS: \"$query\"", style = MaterialTheme.typography.titleSmall, color = hud.accent)
                            }
                        }
                    }
                    items(filtered, key = { it.first }) { (label, action) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(hud.surfaceCard)
                                .border(1.dp, hud.borderSubtle, RoundedCornerShape(12.dp))
                                .clickable(onClick = action)
                                .padding(12.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = hud.textPrimary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TasksActivitySheet(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val tasks by viewModel.taskRecords.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    BackHandler(onBack = onClose)

    Surface(color = hud.background.copy(alpha = 0.98f), modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(modifier = Modifier.fillMaxSize().widthIn(max = 680.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tasks & Activity", style = MaterialTheme.typography.titleLarge, color = hud.textPrimary, modifier = Modifier.weight(1f))
                    if (isBusy) {
                        Button(
                            onClick = { viewModel.stopCurrentWork() },
                            colors = ButtonDefaults.buttonColors(containerColor = hud.danger, contentColor = hud.background)
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Stop")
                        }
                    }
                    IconButton(onClick = onClose, modifier = Modifier.testTag("tasks_close_button")) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_sheet), tint = hud.textPrimary)
                    }
                }

                if (tasks.isEmpty()) {
                    Text("No background or chat tasks recorded yet.", style = MaterialTheme.typography.bodyMedium, color = hud.textSecondary)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(tasks, key = { it.id }) { task ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(hud.surfaceCard)
                                    .border(1.dp, hud.borderSubtle, RoundedCornerShape(14.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("@${task.agentId}", style = MaterialTheme.typography.labelMedium, color = hud.accent)
                                    Spacer(Modifier.width(8.dp))
                                    Text(task.state.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (task.state == "succeeded") hud.success else hud.warning)
                                    Spacer(Modifier.weight(1f))
                                    Text(fmt.format(Date(task.updatedAtMs)), style = MaterialTheme.typography.labelSmall, color = hud.textSecondary)
                                }
                                Text(task.objective, style = MaterialTheme.typography.titleSmall, color = hud.textPrimary)
                                if (task.summary.isNotBlank()) {
                                    Text(task.summary, style = MaterialTheme.typography.bodySmall, color = hud.textSecondary, maxLines = 3)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
