package com.jarvis.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jarvis.app.platform.AssistantLaunchEvent

private val EntranceBackdrop = Color(0xFF0B0D12)
private val EntranceCyan = Color(0xFF22D3EE)
private val EntranceBlue = Color(0xFF3B82F6)
private val EntranceViolet = Color(0xFF8B5CF6)
private val EntranceMagenta = Color(0xFFEC4899)

@Composable
fun AssistantLaunchEntranceOverlay(
    event: AssistantLaunchEvent,
    orbState: OrbState,
    audioLevel: Float,
    speakingLevel: Float,
    onEntranceCompleted: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = remember(event.launchId) { Animatable(0f) }

    LaunchedEffect(event.launchId) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 980, easing = FastOutSlowInEasing)
        )
        onEntranceCompleted(event.launchId)
    }

    val p = progress.value
    val overlayAlpha = when {
        p < 0.18f -> (p / 0.18f).coerceIn(0f, 1f)
        p > 0.72f -> (1f - (p - 0.72f) / 0.28f).coerceIn(0f, 1f)
        else -> 1f
    }

    val orbScale = when {
        p < 0.45f -> {
            val t = (p / 0.45f).coerceIn(0f, 1f)
            0.56f + (1.06f - 0.56f) * t
        }
        else -> {
            val t = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
            1.06f - 0.06f * t
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .testTag("assistant_launch_entrance")
            .graphicsLayer { alpha = overlayAlpha }
            .background(EntranceBackdrop.copy(alpha = 0.88f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onEntranceCompleted(event.launchId) }
            )
    ) {
        Canvas(modifier = Modifier.size(340.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.28f

            val chromaticSweep = Brush.sweepGradient(
                0.00f to EntranceCyan,
                0.26f to EntranceBlue,
                0.52f to EntranceViolet,
                0.76f to EntranceMagenta,
                1.00f to EntranceCyan,
                center = center
            )

            val glowAlpha = (1f - (p - 0.35f).coerceAtLeast(0f) / 0.65f) * 0.46f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        EntranceCyan.copy(alpha = glowAlpha),
                        EntranceViolet.copy(alpha = glowAlpha * 0.62f),
                        EntranceMagenta.copy(alpha = glowAlpha * 0.30f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * (1.25f + p * 0.65f)
                ),
                radius = baseRadius * (1.25f + p * 0.65f),
                center = center
            )

            val r1 = baseRadius * (0.72f + p * 1.12f)
            val a1 = ((1f - p) * 0.72f).coerceIn(0f, 0.72f)
            rotate(degrees = p * 110f, pivot = center) {
                drawCircle(
                    brush = chromaticSweep,
                    radius = r1,
                    center = center,
                    alpha = a1,
                    style = Stroke(width = (3.4f - p * 2.0f).coerceAtLeast(1f).dp.toPx())
                )
            }

            val p2 = ((p - 0.14f) / 0.86f).coerceIn(0f, 1f)
            if (p2 > 0f) {
                val r2 = baseRadius * (0.70f + p2 * 0.96f)
                val a2 = ((1f - p2) * 0.52f).coerceIn(0f, 0.52f)
                rotate(degrees = -p2 * 85f, pivot = center) {
                    drawCircle(
                        brush = chromaticSweep,
                        radius = r2,
                        center = center,
                        alpha = a2,
                        style = Stroke(width = (2.4f - p2 * 1.4f).coerceAtLeast(0.8f).dp.toPx())
                    )
                }
            }
        }

        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = orbScale
                scaleY = orbScale
            }
        ) {
            RgbAssistantOrb(
                state = orbState,
                audioLevel = audioLevel,
                speakingLevel = speakingLevel,
                sizeDp = 204.dp,
                interactive = false
            )
        }
    }
}
