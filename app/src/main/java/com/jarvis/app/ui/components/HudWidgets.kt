package com.jarvis.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.app.domain.model.ActionLogItem
import com.jarvis.app.domain.model.AgendaItem
import com.jarvis.app.domain.model.HudStatus
import com.jarvis.app.domain.model.RoutineItem
import com.jarvis.app.domain.model.ScreenConfig
import com.jarvis.app.domain.model.TelemetrySnapshot
import com.jarvis.app.domain.model.WeatherSnapshot
import com.jarvis.app.ui.theme.LocalJarvisColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HudWidgetCard(
    cardId: String,
    title: String,
    icon: ImageVector,
    badge: String = "",
    collapsed: Boolean,
    density: String,
    onToggleCollapse: () -> Unit,
    trailingHeaderAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val hud = LocalJarvisColors.current
    val shape = RoundedCornerShape(hud.cornerRadiusDp.dp)
    val pad = if (density == "compact") 11.dp else 15.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(hud.surfaceCard)
            .border(
                width = if (hud.isHudLook) 1.2.dp else 1.dp,
                color = if (hud.isHudLook) hud.accent.copy(alpha = 0.34f) else hud.borderSubtle,
                shape = shape
            )
            .padding(pad)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("widget_header_$cardId")
                .clickable(onClick = onToggleCollapse),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = hud.accent,
                modifier = Modifier.size(19.dp)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = if (hud.isHudLook) title.uppercase() else title,
                style = MaterialTheme.typography.titleMedium,
                color = hud.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (badge.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(hud.accent.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = hud.accent,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            if (trailingHeaderAction != null) {
                trailingHeaderAction()
            }
            Icon(
                imageVector = if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                contentDescription = if (collapsed) "Expand $title" else "Collapse $title",
                tint = hud.textSecondary
            )
        }
        AnimatedVisibility(visible = !collapsed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun StatusWidgetCard(
    status: HudStatus,
    modelLabel: String,
    factsCount: Int,
    tasksCount: Int,
    collapsed: Boolean,
    screenConfig: ScreenConfig,
    onToggleCollapse: () -> Unit,
    onOpenTasks: () -> Unit
) {
    val hud = LocalJarvisColors.current
    HudWidgetCard(
        cardId = "status",
        title = "Status",
        icon = Icons.Rounded.Bolt,
        badge = status.label,
        collapsed = collapsed,
        density = screenConfig.density,
        onToggleCollapse = onToggleCollapse
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricPill("Model", modelLabel, Modifier.weight(1f))
            MetricPill("Memory", "$factsCount facts", Modifier.weight(1f))
            MetricPill("Tasks", "$tasksCount runs", Modifier.weight(1f), onClick = onOpenTasks)
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val hud = LocalJarvisColors.current
    val base = modifier
        .clip(RoundedCornerShape(10.dp))
        .background(hud.surfacePanel)
        .border(1.dp, hud.borderSubtle, RoundedCornerShape(10.dp))
    val clickableMod = if (onClick != null) base.clickable(onClick = onClick) else base
    Column(modifier = clickableMod.padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = hud.textSecondary, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = hud.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun RoutinesWidgetCard(
    routines: List<RoutineItem>,
    collapsed: Boolean,
    density: String,
    onToggleCollapse: () -> Unit,
    onSaveRoutine: (RoutineItem) -> Unit,
    onRunRoutine: (RoutineItem) -> Unit,
    onDeleteRoutine: (Long) -> Unit
) {
    val hud = LocalJarvisColors.current
    var showAdd by remember { mutableStateOf(false) }
    var nameInput by remember { mutableStateOf("") }
    var promptInput by remember { mutableStateOf("") }
    var timeInput by remember { mutableStateOf("") }

    HudWidgetCard(
        cardId = "routines",
        title = "Routines",
        icon = Icons.Rounded.Bolt,
        badge = "${routines.size}",
        collapsed = collapsed,
        density = density,
        onToggleCollapse = onToggleCollapse,
        trailingHeaderAction = {
            IconButton(
                onClick = {
                    if (collapsed) onToggleCollapse()
                    showAdd = !showAdd
                },
                modifier = Modifier.testTag("routine_add_toggle_button")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Add routine", tint = hud.accent)
            }
        }
    ) {
        if (showAdd) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(hud.surfacePanel)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Routine name") },
                    singleLine = true,
                    colors = hudTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("routine_name_input")
                )
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    label = { Text("What should JARVIS do?") },
                    colors = hudTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("routine_prompt_input")
                )
                OutlinedTextField(
                    value = timeInput,
                    onValueChange = { timeInput = it },
                    label = { Text("Time (optional, e.g. 08:00)") },
                    singleLine = true,
                    colors = hudTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("routine_time_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (nameInput.isNotBlank() && promptInput.isNotBlank()) {
                                onSaveRoutine(
                                    RoutineItem(
                                        name = nameInput.trim(),
                                        prompt = promptInput.trim(),
                                        triggerTime = timeInput.trim()
                                    )
                                )
                                nameInput = ""
                                promptInput = ""
                                timeInput = ""
                                showAdd = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background),
                        modifier = Modifier.testTag("routine_save_button")
                    ) { Text("Save routine") }
                    TextButton(onClick = { showAdd = false }) { Text("Cancel", color = hud.textSecondary) }
                }
            }
        }

        if (routines.isEmpty() && !showAdd) {
            Text(
                "One-tap automations or scheduled briefings.",
                style = MaterialTheme.typography.bodySmall,
                color = hud.textSecondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onSaveRoutine(
                            RoutineItem(
                                name = "Movie night",
                                prompt = "Turn on Do Not Disturb, dim media volume to 40%, and suggest a good movie.",
                                triggerTime = ""
                            )
                        )
                    },
                    modifier = Modifier.testTag("starter_routine_movie")
                ) { Text("+ Movie night") }
                OutlinedButton(
                    onClick = {
                        onSaveRoutine(
                            RoutineItem(
                                name = "Bedtime",
                                prompt = "Enable Do Not Disturb, summarize tomorrow's agenda, and wish me goodnight.",
                                triggerTime = "22:30"
                            )
                        )
                    },
                    modifier = Modifier.testTag("starter_routine_bedtime")
                ) { Text("+ Bedtime") }
            }
        } else {
            routines.forEach { routine ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(hud.surfacePanel)
                        .border(1.dp, hud.borderSubtle, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(routine.name, style = MaterialTheme.typography.titleSmall, color = hud.textPrimary)
                        val meta = listOfNotNull(
                            routine.triggerTime.takeIf { it.isNotBlank() },
                            routine.days.takeIf { routine.triggerTime.isNotBlank() }
                        ).joinToString(" · ")
                        Text(
                            if (meta.isNotBlank()) "$meta — ${routine.prompt}" else routine.prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = hud.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { onRunRoutine(routine) },
                        modifier = Modifier.testTag("run_routine_${routine.id}")
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Run ${routine.name}", tint = hud.accent)
                    }
                    IconButton(
                        onClick = { onDeleteRoutine(routine.id) },
                        modifier = Modifier.testTag("delete_routine_${routine.id}")
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete ${routine.name}", tint = hud.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceWidgetCard(
    telemetry: TelemetrySnapshot,
    gaugeStyle: String,
    collapsed: Boolean,
    density: String,
    onToggleCollapse: () -> Unit
) {
    val hud = LocalJarvisColors.current
    HudWidgetCard(
        cardId = "device",
        title = "Device",
        icon = Icons.Rounded.Memory,
        badge = "${telemetry.ramPercent}% RAM",
        collapsed = collapsed,
        density = density,
        onToggleCollapse = onToggleCollapse
    ) {
        if (gaugeStyle == "rings") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                GaugeRing(
                    label = "RAM",
                    percent = telemetry.ramPercent,
                    subtitle = "${telemetry.ramUsedMb}/${telemetry.ramTotalMb} MB",
                    color = hud.accent
                )
                GaugeRing(
                    label = "Storage",
                    percent = telemetry.storagePercent,
                    subtitle = String.format(Locale.US, "%.0f/%.0f GB", telemetry.storageUsedGb, telemetry.storageTotalGb),
                    color = hud.secondaryAccent
                )
            }
        } else {
            GaugeBar(
                label = "Memory (RAM)",
                percent = telemetry.ramPercent,
                detail = "${telemetry.ramUsedMb} / ${telemetry.ramTotalMb} MB",
                color = hud.accent
            )
            GaugeBar(
                label = "Internal storage",
                percent = telemetry.storagePercent,
                detail = String.format(Locale.US, "%.1f / %.1f GB", telemetry.storageUsedGb, telemetry.storageTotalGb),
                color = hud.secondaryAccent
            )
        }
    }
}

@Composable
fun BatteryWidgetCard(
    telemetry: TelemetrySnapshot,
    collapsed: Boolean,
    density: String,
    onToggleCollapse: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val badge = "${telemetry.batteryPercent}%${if (telemetry.isCharging) " · Charging" else ""}"
    HudWidgetCard(
        cardId = "battery",
        title = "Battery",
        icon = if (telemetry.isCharging) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryStd,
        badge = badge,
        collapsed = collapsed,
        density = density,
        onToggleCollapse = onToggleCollapse
    ) {
        GaugeBar(
            label = if (telemetry.isCharging) "Charging (${telemetry.batteryHealth})" else "Discharging (${telemetry.batteryHealth})",
            percent = telemetry.batteryPercent,
            detail = String.format(Locale.US, "%.1f°C", telemetry.batteryTempC),
            color = if (telemetry.batteryPercent <= 20 && !telemetry.isCharging) hud.warning else hud.success
        )
    }
}

@Composable
fun AgendaWidgetCard(
    agenda: List<AgendaItem>,
    collapsed: Boolean,
    density: String,
    onToggleCollapse: () -> Unit,
    onAddAgenda: (String, String) -> Unit,
    onToggleItem: (AgendaItem) -> Unit,
    onDeleteItem: (Long) -> Unit
) {
    val hud = LocalJarvisColors.current
    var showAdd by remember { mutableStateOf(false) }
    var timeInput by remember { mutableStateOf("09:00") }
    var taskInput by remember { mutableStateOf("") }

    HudWidgetCard(
        cardId = "agenda",
        title = "Agenda",
        icon = Icons.Rounded.CalendarMonth,
        badge = "${agenda.count { !it.completed }} open",
        collapsed = collapsed,
        density = density,
        onToggleCollapse = onToggleCollapse,
        trailingHeaderAction = {
            IconButton(
                onClick = {
                    if (collapsed) onToggleCollapse()
                    showAdd = !showAdd
                },
                modifier = Modifier.testTag("agenda_add_toggle_button")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Add agenda item", tint = hud.accent)
            }
        }
    ) {
        if (showAdd) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(hud.surfacePanel)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = timeInput,
                        onValueChange = { timeInput = it },
                        label = { Text("Time") },
                        singleLine = true,
                        colors = hudTextFieldColors(),
                        modifier = Modifier.width(96.dp).testTag("agenda_time_input")
                    )
                    OutlinedTextField(
                        value = taskInput,
                        onValueChange = { taskInput = it },
                        label = { Text("Task") },
                        singleLine = true,
                        colors = hudTextFieldColors(),
                        modifier = Modifier.weight(1f).testTag("agenda_task_input")
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (taskInput.isNotBlank()) {
                                onAddAgenda(timeInput.ifBlank { "Today" }, taskInput.trim())
                                taskInput = ""
                                showAdd = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background),
                        modifier = Modifier.testTag("agenda_save_button")
                    ) { Text("Add") }
                    TextButton(onClick = { showAdd = false }) { Text("Cancel", color = hud.textSecondary) }
                }
            }
        }

        if (agenda.isEmpty()) {
            Text(
                "Nothing on today's agenda yet.",
                style = MaterialTheme.typography.bodySmall,
                color = hud.textSecondary
            )
        } else {
            agenda.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(hud.surfacePanel)
                        .clickable { onToggleItem(item) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (item.completed) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (item.completed) "Completed" else "Incomplete",
                        tint = if (item.completed) hud.success else hud.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(item.timeLabel, style = MaterialTheme.typography.labelMedium, color = hud.accent)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.task,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.completed) hud.textSecondary else hud.textPrimary,
                        textDecoration = if (item.completed) TextDecoration.LineThrough else TextDecoration.None,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.source != "calendar") {
                        IconButton(
                            onClick = { onDeleteItem(item.id) },
                            modifier = Modifier.testTag("delete_agenda_${item.id}")
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete agenda item", tint = hud.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WeatherWidgetCard(
    weather: WeatherSnapshot,
    collapsed: Boolean,
    density: String,
    onToggleCollapse: () -> Unit,
    onRefresh: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val badge = weather.temperatureC?.let { "${it}°C · ${weather.condition}" } ?: (weather.error ?: "Loading…")
    HudWidgetCard(
        cardId = "weather",
        title = "Weather",
        icon = Icons.Rounded.WbSunny,
        badge = badge,
        collapsed = collapsed,
        density = density,
        onToggleCollapse = onToggleCollapse,
        trailingHeaderAction = {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.testTag("weather_refresh_button")
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh weather", tint = hud.accent)
            }
        }
    ) {
        if (weather.error != null) {
            Text(weather.error, style = MaterialTheme.typography.bodySmall, color = hud.textSecondary)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${weather.temperatureC ?: "--"}°C",
                        style = MaterialTheme.typography.headlineMedium,
                        color = hud.accent
                    )
                    Text(
                        text = weather.place.ifBlank { "Local forecast" },
                        style = MaterialTheme.typography.bodySmall,
                        color = hud.textSecondary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(weather.condition, style = MaterialTheme.typography.titleSmall, color = hud.textPrimary)
                    Text(
                        "Feels ${weather.feelsLikeC ?: "--"}°C · Wind ${weather.windKmh ?: "--"} km/h",
                        style = MaterialTheme.typography.labelSmall,
                        color = hud.textSecondary
                    )
                }
            }
            if (weather.hourlySummary.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    weather.hourlySummary.forEach { (hr, t) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(hud.surfacePanel)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(hr, style = MaterialTheme.typography.labelSmall, color = hud.textSecondary)
                            Text(t, style = MaterialTheme.typography.labelMedium, color = hud.textPrimary)
                        }
                    }
                }
            }
            if (weather.forecastDays.isNotEmpty()) {
                HorizontalDivider(color = hud.borderSubtle)
                weather.forecastDays.forEach { dayLine ->
                    Text(dayLine, style = MaterialTheme.typography.bodySmall, color = hud.textSecondary)
                }
            }
        }
    }
}

@Composable
fun NetworkWidgetCard(
    telemetry: TelemetrySnapshot,
    collapsed: Boolean,
    density: String,
    onToggleCollapse: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val net = telemetry.network
    HudWidgetCard(
        cardId = "network",
        title = "Network",
        icon = if (net.connected) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
        badge = net.transport,
        collapsed = collapsed,
        density = density,
        onToggleCollapse = onToggleCollapse
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricPill("Local IP", net.localIp, Modifier.weight(1f))
            MetricPill("Public IP", net.publicIp, Modifier.weight(1f))
        }
        MetricPill("Region", net.cityRegion, Modifier.fillMaxWidth())
    }
}

@Composable
fun ActionLogWidgetCard(
    logs: List<ActionLogItem>,
    collapsed: Boolean,
    density: String,
    onToggleCollapse: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    HudWidgetCard(
        cardId = "log",
        title = "Action log",
        icon = Icons.Rounded.History,
        badge = "${logs.size}",
        collapsed = collapsed,
        density = density,
        onToggleCollapse = onToggleCollapse
    ) {
        if (logs.isEmpty()) {
            Text(
                "No recent tool actions recorded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = hud.textSecondary
            )
        } else {
            logs.take(6).forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (entry.ok) hud.success else hud.danger)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${fmt.format(Date(entry.timestampMs))} · ${entry.toolName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = hud.accent
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = hud.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun GaugeBar(
    label: String,
    percent: Int,
    detail: String,
    color: Color
) {
    val hud = LocalJarvisColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = hud.textPrimary)
            Text("$percent% · $detail", style = MaterialTheme.typography.labelMedium, color = hud.textSecondary)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(hud.background)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((percent.coerceIn(2, 100) / 100f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun GaugeRing(
    label: String,
    percent: Int,
    subtitle: String,
    color: Color
) {
    val hud = LocalJarvisColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp)) {
            Canvas(modifier = Modifier.size(76.dp)) {
                val stroke = 6.dp.toPx()
                val tl = Offset(stroke, stroke)
                val sz = Size(size.width - stroke * 2, size.height - stroke * 2)
                drawArc(
                    color = hud.background,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = tl,
                    size = sz,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = (percent.coerceIn(0, 100) / 100f) * 360f,
                    useCenter = false,
                    topLeft = tl,
                    size = sz,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            Text("$percent%", style = MaterialTheme.typography.titleMedium, color = hud.textPrimary)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = hud.accent)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = hud.textSecondary)
    }
}

@Composable
fun hudTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalJarvisColors.current.accent,
    unfocusedBorderColor = LocalJarvisColors.current.borderSubtle,
    focusedTextColor = LocalJarvisColors.current.textPrimary,
    unfocusedTextColor = LocalJarvisColors.current.textPrimary,
    focusedLabelColor = LocalJarvisColors.current.accent,
    unfocusedLabelColor = LocalJarvisColors.current.textSecondary,
    cursorColor = LocalJarvisColors.current.accent
)
