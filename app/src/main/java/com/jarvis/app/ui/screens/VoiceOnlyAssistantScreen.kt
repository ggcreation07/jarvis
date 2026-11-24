package com.jarvis.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jarvis.app.R
import com.jarvis.app.platform.AssistantLaunchEvent
import com.jarvis.app.ui.components.OrbState
import com.jarvis.app.ui.components.RgbAssistantOrb
import com.jarvis.app.ui.theme.LocalJarvisColors

private val VoiceCyan = Color(0xFF22D3EE)
private val VoiceBlue = Color(0xFF3B82F6)
private val VoiceViolet = Color(0xFF8B5CF6)
private val VoiceMagenta = Color(0xFFEC4899)

@Composable
fun VoiceOnlyAssistantScreen(
    orbState: OrbState,
    audioLevel: Float,
    speakingLevel: Float,
    launchEvent: AssistantLaunchEvent?,
    translucentOverlay: Boolean = false,
    onEntranceCompleted: (Long) -> Unit,
    onOrbClick: () -> Unit,
    onHideKeepSession: () -> Unit,
    onStopAssistant: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hud = LocalJarvisColors.current
    val launchId = launchEvent?.launchId ?: 0L
    val shouldAnimateEntrance = launchEvent != null && !launchEvent.entranceCompleted
    val entranceProgress = remember(launchId) {
        Animatable(if (shouldAnimateEntrance) 0f else 1f)
    }

    LaunchedEffect(launchId, shouldAnimateEntrance) {
        if (shouldAnimateEntrance && launchEvent != null) {
            entranceProgress.snapTo(0f)
            entranceProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 820, easing = FastOutSlowInEasing)
            )
            onEntranceCompleted(launchEvent.launchId)
        } else {
            entranceProgress.snapTo(1f)
        }
    }

    if (LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler(onBack = onHideKeepSession)
    }

    val p = entranceProgress.value
    val orbScale = if (p < 1f) {
        if (p < 0.52f) {
            val t = (p / 0.52f).coerceIn(0f, 1f)
            0.58f + (1.07f - 0.58f) * t
        } else {
            val t = ((p - 0.52f) / 0.48f).coerceIn(0f, 1f)
            1.07f - 0.07f * t
        }
    } else {
        1f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("voice_only_orb_screen")
            .background(
                if (translucentOverlay) {
                    Color(0xEB0B0D12)
                } else {
                    hud.background
                }
            )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (p < 0.99f) {
                Canvas(modifier = Modifier.size(340.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val baseRadius = size.minDimension * 0.28f
                    val sweep = Brush.sweepGradient(
                        0.00f to VoiceCyan,
                        0.26f to VoiceBlue,
                        0.52f to VoiceViolet,
                        0.76f to VoiceMagenta,
                        1.00f to VoiceCyan,
                        center = center
                    )
                    val glowAlpha = ((1f - p) * 0.45f).coerceIn(0f, 0.45f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                VoiceCyan.copy(alpha = glowAlpha),
                                VoiceViolet.copy(alpha = glowAlpha * 0.58f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = baseRadius * (1.25f + p * 0.65f)
                        ),
                        radius = baseRadius * (1.25f + p * 0.65f),
                        center = center
                    )
                    val ringRadius = baseRadius * (0.74f + p * 1.10f)
                    val ringAlpha = ((1f - p) * 0.72f).coerceIn(0f, 0.72f)
                    rotate(degrees = p * 105f, pivot = center) {
                        drawCircle(
                            brush = sweep,
                            radius = ringRadius,
                            center = center,
                            alpha = ringAlpha,
                            style = Stroke(width = (3.2f - p * 1.8f).coerceAtLeast(1f).dp.toPx())
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
                    sizeDp = 216.dp,
                    interactive = true,
                    onClick = onOrbClick
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(50))
                .background(hud.surfacePanel.copy(alpha = 0.88f))
                .border(1.dp, hud.borderSubtle, RoundedCornerShape(50))
                .testTag("voice_only_stop_button")
                .clickable(onClick = onStopAssistant)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Stop,
                contentDescription = stringResource(R.string.action_stop_assistant),
                tint = hud.danger,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.action_stop_assistant),
                style = MaterialTheme.typography.labelLarge,
                color = hud.textPrimary
            )
        }
    }
}
