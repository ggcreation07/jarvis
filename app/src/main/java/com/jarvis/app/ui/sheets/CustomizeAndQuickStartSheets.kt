package com.jarvis.app.ui.sheets

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.R
import com.jarvis.app.domain.model.ALL_CARD_IDS
import com.jarvis.app.domain.model.DEFAULT_ACCENT_PRESETS
import com.jarvis.app.domain.model.DEFAULT_TOP_BAR_BUTTONS
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.hudTextFieldColors
import com.jarvis.app.ui.theme.LocalJarvisColors
import com.jarvis.app.ui.theme.parseHexColor

@Composable
fun CustomizeSheet(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val cfg by viewModel.screenConfig.collectAsStateWithLifecycle()
    var presetNameInput by remember { mutableStateOf("") }
    var mainHexInput by remember(cfg.mainHex) { mutableStateOf(cfg.mainHex) }
    var secondHexInput by remember(cfg.secondHex) { mutableStateOf(cfg.secondHex) }

    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.updateScreen { it.copy(backgroundStyle = "picture", backgroundPictureUri = uri.toString()) }
        }
    }

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
                        Text("Customize Screen", style = MaterialTheme.typography.titleLarge, color = hud.textPrimary, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClose, modifier = Modifier.testTag("customize_close_button")) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_sheet), tint = hud.textPrimary)
                        }
                    }
                }

                // Built-in looks + custom presets
                item {
                    SectionCard("Looks & Presets") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = cfg.look == "modern",
                                onClick = { viewModel.applyBuiltInLook("modern") },
                                label = { Text("Modern") },
                                colors = hudFilterChipColors(),
                                modifier = Modifier.testTag("look_modern_chip")
                            )
                            FilterChip(
                                selected = cfg.look == "ironman",
                                onClick = { viewModel.applyBuiltInLook("ironman") },
                                label = { Text("Iron Man") },
                                colors = hudFilterChipColors(),
                                modifier = Modifier.testTag("look_ironman_chip")
                            )
                        }
                        if (cfg.customPresets.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                cfg.customPresets.forEach { preset ->
                                    OutlinedButton(onClick = { viewModel.applyCustomLookPreset(preset) }) {
                                        Text(preset.name)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = presetNameInput,
                                onValueChange = { presetNameInput = it },
                                placeholder = { Text("Save current look as…") },
                                singleLine = true,
                                colors = hudTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    viewModel.saveCustomLookPreset(presetNameInput)
                                    presetNameInput = ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
                            ) { Text("Save") }
                        }
                    }
                }

                // Accents
                item {
                    SectionCard("Accent Colors") {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DEFAULT_ACCENT_PRESETS.forEach { preset ->
                                val swatchColor = parseHexColor(preset.mainHex, hud.accent)
                                val selected = cfg.accentKey == preset.key
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(swatchColor)
                                        .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else hud.borderSubtle, CircleShape)
                                        .testTag("accent_swatch_${preset.key}")
                                        .clickable {
                                            viewModel.updateScreen {
                                                it.copy(accentKey = preset.key, mainHex = preset.mainHex, secondHex = preset.secondHex)
                                            }
                                        }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = mainHexInput,
                                onValueChange = {
                                    mainHexInput = it
                                    if (it.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
                                        viewModel.updateScreen { cur -> cur.copy(mainHex = it.uppercase()) }
                                    }
                                },
                                label = { Text("Main hex") },
                                singleLine = true,
                                colors = hudTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = secondHexInput,
                                onValueChange = {
                                    secondHexInput = it
                                    if (it.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
                                        viewModel.updateScreen { cur -> cur.copy(secondHex = it.uppercase()) }
                                    }
                                },
                                label = { Text("Second hex") },
                                singleLine = true,
                                colors = hudTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // HUD Style Controls
                item {
                    SectionCard("Visual Style & Motion") {
                        StyleToggleRow(
                            label = "Centrepiece",
                            options = listOf("orb" to "Orb", "reactor" to "Arc reactor"),
                            selected = cfg.centerpiece,
                            onSelect = { v -> viewModel.updateScreen { it.copy(centerpiece = v) } }
                        )
                        StyleToggleRow(
                            label = "Font",
                            options = listOf("clean" to "Clean", "hud" to "HUD"),
                            selected = cfg.fontStyle,
                            onSelect = { v -> viewModel.updateScreen { it.copy(fontStyle = v) } }
                        )
                        StyleToggleRow(
                            label = "Cards",
                            options = listOf("clean" to "Clean", "hud" to "HUD"),
                            selected = cfg.cardStyle,
                            onSelect = { v -> viewModel.updateScreen { it.copy(cardStyle = v) } }
                        )
                        StyleToggleRow(
                            label = "Gauges",
                            options = listOf("bars" to "Bars", "rings" to "Rings"),
                            selected = cfg.gaugeStyle,
                            onSelect = { v -> viewModel.updateScreen { it.copy(gaugeStyle = v) } }
                        )
                        StyleToggleRow(
                            label = "Density",
                            options = listOf("comfortable" to "Comfortable", "compact" to "Compact"),
                            selected = cfg.density,
                            onSelect = { v -> viewModel.updateScreen { it.copy(density = v) } }
                        )
                        Text("Corners (${cfg.cornerRadiusDp} dp)", style = MaterialTheme.typography.labelMedium, color = hud.textSecondary)
                        Slider(
                            value = cfg.cornerRadiusDp.toFloat(),
                            onValueChange = { v -> viewModel.updateScreen { it.copy(cornerRadiusDp = v.toInt()) } },
                            valueRange = 4f..24f,
                            colors = SliderDefaults.colors(thumbColor = hud.accent, activeTrackColor = hud.accent)
                        )
                        Text("Glow strength (${cfg.glowStrength}%)", style = MaterialTheme.typography.labelMedium, color = hud.textSecondary)
                        Slider(
                            value = cfg.glowStrength.toFloat(),
                            onValueChange = { v -> viewModel.updateScreen { it.copy(glowStrength = v.toInt()) } },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(thumbColor = hud.accent, activeTrackColor = hud.accent)
                        )
                    }
                }

                // Background
                item {
                    SectionCard("Background") {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("grid" to "Grid", "glow" to "Glow", "aurora" to "Aurora", "minimal" to "Minimal", "picture" to "Picture").forEach { (id, label) ->
                                FilterChip(
                                    selected = cfg.backgroundStyle == id,
                                    onClick = { viewModel.updateScreen { it.copy(backgroundStyle = id) } },
                                    label = { Text(label) },
                                    colors = hudFilterChipColors()
                                )
                            }
                        }
                        OutlinedButton(onClick = {
                            bgPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) {
                            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Choose background picture")
                        }
                    }
                }

                // Home Cards visibility & ordering
                item {
                    SectionCard("Home Cards & Order") {
                        val order = (cfg.cardOrder + ALL_CARD_IDS).distinct()
                        order.forEachIndexed { idx, cardId ->
                            val visible = cardId !in cfg.hiddenCards
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(cardId.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, color = hud.textPrimary, modifier = Modifier.weight(1f))
                                IconButton(
                                    enabled = idx > 0,
                                    onClick = {
                                        val mutable = order.toMutableList()
                                        val tmp = mutable[idx - 1]
                                        mutable[idx - 1] = mutable[idx]
                                        mutable[idx] = tmp
                                        viewModel.updateScreen { it.copy(cardOrder = mutable) }
                                    }
                                ) { Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move $cardId up", tint = hud.textSecondary) }
                                IconButton(
                                    enabled = idx < order.lastIndex,
                                    onClick = {
                                        val mutable = order.toMutableList()
                                        val tmp = mutable[idx + 1]
                                        mutable[idx + 1] = mutable[idx]
                                        mutable[idx] = tmp
                                        viewModel.updateScreen { it.copy(cardOrder = mutable) }
                                    }
                                ) { Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move $cardId down", tint = hud.textSecondary) }
                                Switch(
                                    checked = visible,
                                    onCheckedChange = { show ->
                                        viewModel.updateScreen {
                                            it.copy(hiddenCards = if (show) it.hiddenCards - cardId else it.hiddenCards + cardId)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Top bar buttons
                item {
                    SectionCard("Top Bar Shortcuts") {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DEFAULT_TOP_BAR_BUTTONS.forEach { btnId ->
                                val active = btnId in cfg.topBarButtons
                                FilterChip(
                                    selected = active,
                                    onClick = {
                                        viewModel.updateScreen {
                                            val next = if (active) it.topBarButtons - btnId else it.topBarButtons + btnId
                                            it.copy(topBarButtons = next)
                                        }
                                    },
                                    label = { Text(btnId.replaceFirstChar { c -> c.uppercase() }) },
                                    colors = hudFilterChipColors()
                                )
                            }
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { viewModel.openOverlay(OverlayType.QUICK_START) }) {
                            Text("Replay Quick Start")
                        }
                        TextButton(onClick = { viewModel.resetScreenDefaults() }) {
                            Text("Reset defaults", color = hud.danger)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickStartSheet(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    var step by remember { mutableIntStateOf(0) }
    BackHandler {
        viewModel.updateScreen { it.copy(quickStartCompleted = true) }
        onClose()
    }

    Surface(color = hud.background.copy(alpha = 0.96f), modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(hud.surfaceCard)
                    .border(1.dp, hud.accent.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    if (step == 0) "Welcome to J.A.R.V.I.S" else "Choose Your Look",
                    style = MaterialTheme.typography.headlineMedium,
                    color = hud.accent
                )
                Text(
                    if (step == 0) {
                        "Your mobile command HUD is powered by built-in Gemini AI, local memory, routines, weather, and hands-free gestures. Tap the core or Mic anytime to speak."
                    } else {
                        "Pick a centerpiece and visual style. You can fine-tune accents, cards, and shortcuts anytime in Customize."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = hud.textPrimary
                )
                if (step == 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.applyBuiltInLook("modern") },
                            modifier = Modifier.weight(1f)
                        ) { Text("Modern Orb") }
                        OutlinedButton(
                            onClick = { viewModel.applyBuiltInLook("ironman") },
                            modifier = Modifier.weight(1f)
                        ) { Text("Iron Man HUD") }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (step == 0) {
                        Button(
                            onClick = { step = 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background),
                            modifier = Modifier.testTag("quickstart_next_button")
                        ) { Text("Next") }
                    } else {
                        Button(
                            onClick = {
                                viewModel.updateScreen { it.copy(quickStartCompleted = true) }
                                onClose()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background),
                            modifier = Modifier.testTag("quickstart_finish_button")
                        ) { Text("Launch HUD") }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    val hud = LocalJarvisColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(hud.surfaceCard)
            .border(1.dp, hud.borderSubtle, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = hud.accent)
        content()
    }
}

@Composable
private fun StyleToggleRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val hud = LocalJarvisColors.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = hud.textPrimary, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (id, title) ->
                FilterChip(
                    selected = selected == id,
                    onClick = { onSelect(id) },
                    label = { Text(title) },
                    colors = hudFilterChipColors()
                )
            }
        }
    }
}

@Composable
fun hudFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = LocalJarvisColors.current.accent.copy(alpha = 0.22f),
    selectedLabelColor = LocalJarvisColors.current.accent
)
