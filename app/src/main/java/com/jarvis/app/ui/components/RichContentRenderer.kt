package com.jarvis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jarvis.app.ui.theme.LocalJarvisColors
import com.jarvis.app.ui.theme.ShareTechMonoFamily
import org.json.JSONObject

private sealed interface MessageSegment {
    data class PlainMarkdown(val text: String) : MessageSegment
    data class ChartBlock(val title: String, val labels: List<String>, val values: List<Double>) : MessageSegment
    data class TableBlock(val title: String, val headers: List<String>, val rows: List<List<String>>) : MessageSegment
    data class FlowchartBlock(val title: String, val steps: List<String>) : MessageSegment
    data class ScheduleBlock(val title: String, val items: List<Pair<String, String>>) : MessageSegment
}

@Composable
fun RichResponseRenderer(
    content: String,
    textColor: Color = LocalJarvisColors.current.textPrimary,
    modifier: Modifier = Modifier
) {
    val segments = remember(content) { parseSegments(content) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is MessageSegment.PlainMarkdown -> {
                    if (seg.text.isNotBlank()) {
                        Text(
                            text = formatInlineMarkdown(seg.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor
                        )
                    }
                }
                is MessageSegment.ChartBlock -> HudBarChartCard(seg)
                is MessageSegment.TableBlock -> HudTableCard(seg)
                is MessageSegment.FlowchartBlock -> HudFlowchartCard(seg)
                is MessageSegment.ScheduleBlock -> HudScheduleCard(seg)
            }
        }
    }
}

private fun parseSegments(raw: String): List<MessageSegment> {
    val pattern = Regex("\\[(CHART|TABLE|FLOWCHART|SCHEDULE)]([\\s\\S]*?)\\[/\\1]")
    val out = mutableListOf<MessageSegment>()
    var cursor = 0
    for (match in pattern.findAll(raw)) {
        if (match.range.first > cursor) {
            out.add(MessageSegment.PlainMarkdown(raw.substring(cursor, match.range.first).trim()))
        }
        val tag = match.groupValues[1]
        val body = match.groupValues[2].trim()
        val parsed = parseStructuredBlock(tag, body)
        if (parsed != null) out.add(parsed)
        else out.add(MessageSegment.PlainMarkdown(body))
        cursor = match.range.last + 1
    }
    if (cursor < raw.length) {
        out.add(MessageSegment.PlainMarkdown(raw.substring(cursor).trim()))
    }
    return out
}

private fun parseStructuredBlock(tag: String, jsonText: String): MessageSegment? {
    return try {
        val obj = JSONObject(jsonText)
        val title = obj.optString("title", tag.lowercase().replaceFirstChar { it.uppercase() })
        when (tag) {
            "CHART" -> {
                val lArr = obj.optJSONArray("labels") ?: return null
                val vArr = obj.optJSONArray("values") ?: return null
                val labels = (0 until lArr.length()).map { lArr.optString(it) }
                val values = (0 until vArr.length()).map { vArr.optDouble(it, 0.0) }
                MessageSegment.ChartBlock(title, labels, values)
            }
            "TABLE" -> {
                val hArr = obj.optJSONArray("headers") ?: return null
                val rArr = obj.optJSONArray("rows") ?: return null
                val headers = (0 until hArr.length()).map { hArr.optString(it) }
                val rows = (0 until rArr.length()).map { r ->
                    val rowArr = rArr.optJSONArray(r)
                    if (rowArr != null) (0 until rowArr.length()).map { c -> rowArr.optString(c) } else emptyList()
                }
                MessageSegment.TableBlock(title, headers, rows)
            }
            "FLOWCHART" -> {
                val sArr = obj.optJSONArray("steps") ?: return null
                val steps = (0 until sArr.length()).map { sArr.optString(it) }
                MessageSegment.FlowchartBlock(title, steps)
            }
            "SCHEDULE" -> {
                val iArr = obj.optJSONArray("items") ?: return null
                val items = (0 until iArr.length()).mapNotNull { idx ->
                    val row = iArr.optJSONObject(idx) ?: return@mapNotNull null
                    row.optString("time") to row.optString("task")
                }
                MessageSegment.ScheduleBlock(title, items)
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

private fun formatInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        val boldRegex = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
        var idx = 0
        for (m in boldRegex.findAll(text)) {
            if (m.range.first > idx) {
                append(text.substring(idx, m.range.first))
            }
            val boldWord = m.groups[1]?.value
            val codeWord = m.groups[2]?.value
            when {
                boldWord != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(boldWord) }
                codeWord != null -> withStyle(SpanStyle(fontFamily = ShareTechMonoFamily)) { append(codeWord) }
            }
            idx = m.range.last + 1
        }
        if (idx < text.length) append(text.substring(idx))
    }
}

@Composable
private fun HudBarChartCard(block: MessageSegment.ChartBlock) {
    val hud = LocalJarvisColors.current
    val maxVal = (block.values.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(hud.surfacePanel)
            .border(1.dp, hud.borderSubtle, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(block.title, style = MaterialTheme.typography.titleMedium, color = hud.accent)
        block.labels.zip(block.values).forEach { (label, value) ->
            val ratio = (value / maxVal).toFloat().coerceIn(0.05f, 1f)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = hud.textPrimary)
                    Text(
                        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(java.util.Locale.US, "%.1f", value),
                        style = MaterialTheme.typography.labelMedium,
                        color = hud.accent
                    )
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
                            .fillMaxWidth(ratio)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(hud.accent)
                    )
                }
            }
        }
    }
}

@Composable
private fun HudTableCard(block: MessageSegment.TableBlock) {
    val hud = LocalJarvisColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(hud.surfacePanel)
            .border(1.dp, hud.borderSubtle, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(block.title, style = MaterialTheme.typography.titleMedium, color = hud.accent)
        if (block.headers.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                block.headers.forEach { h ->
                    Text(
                        h,
                        style = MaterialTheme.typography.labelMedium,
                        color = hud.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                }
            }
            HorizontalDivider(color = hud.borderSubtle)
        }
        block.rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    Text(
                        cell,
                        style = MaterialTheme.typography.bodySmall,
                        color = hud.textPrimary,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HudFlowchartCard(block: MessageSegment.FlowchartBlock) {
    val hud = LocalJarvisColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(hud.surfacePanel)
            .border(1.dp, hud.borderSubtle, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(block.title, style = MaterialTheme.typography.titleMedium, color = hud.accent, modifier = Modifier.fillMaxWidth())
        block.steps.forEachIndexed { idx, step ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(hud.background)
                    .border(1.dp, hud.borderSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("${idx + 1}. $step", style = MaterialTheme.typography.bodySmall, color = hud.textPrimary)
            }
            if (idx < block.steps.lastIndex) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = hud.accent, modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HudScheduleCard(block: MessageSegment.ScheduleBlock) {
    val hud = LocalJarvisColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(hud.surfacePanel)
            .border(1.dp, hud.borderSubtle, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(block.title, style = MaterialTheme.typography.titleMedium, color = hud.accent)
        block.items.forEach { (time, task) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, style = MaterialTheme.typography.labelMedium, color = hud.accent, modifier = Modifier.width(68.dp))
                Spacer(Modifier.width(8.dp))
                Text(task, style = MaterialTheme.typography.bodySmall, color = hud.textPrimary, modifier = Modifier.weight(1f))
            }
        }
    }
}
