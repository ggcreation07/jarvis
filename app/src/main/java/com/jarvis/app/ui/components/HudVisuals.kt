package com.jarvis.app.ui.components

import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.jarvis.app.R
import com.jarvis.app.domain.model.HudStatus
import com.jarvis.app.domain.model.ScreenConfig
import com.jarvis.app.ui.theme.LocalJarvisColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HudBackgroundLayer(
    screenConfig: ScreenConfig,
    modifier: Modifier = Modifier
) {
    val hud = LocalJarvisColors.current
    val inf = rememberInfiniteTransition(label = "bg_aura")
    val drift by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse),
        label = "bg_drift"
    )

    Box(modifier = modifier.background(hud.background)) {
        if (screenConfig.backgroundStyle == "picture" && screenConfig.backgroundPictureUri.isNotBlank()) {
            AsyncImage(
                model = Uri.parse(screenConfig.backgroundPictureUri),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(hud.background.copy(alpha = 0.72f))
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val glow = hud.glowAlpha

            when (screenConfig.backgroundStyle) {
                "grid" -> {
                    val step = 34.dp.toPx()
                    val lineColor = hud.accent.copy(alpha = 0.055f + 0.03f * glow)
                    var x = 0f
                    while (x <= w) {
                        drawLine(lineColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                        x += step
                    }
                    var y = 0f
                    while (y <= h) {
                        drawLine(lineColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                        y += step
                    }
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(hud.accent.copy(alpha = 0.14f * glow), Color.Transparent),
                            center = Offset(w * 0.5f, h * 0.28f),
                            radius = w * 0.75f
                        ),
                        center = Offset(w * 0.5f, h * 0.28f),
                        radius = w * 0.75f
                    )
                }
                "glow" -> {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(hud.accent.copy(alpha = 0.22f * glow), Color.Transparent),
                            center = Offset(w * 0.5f, h * 0.26f),
                            radius = w * 0.85f
                        ),
                        center = Offset(w * 0.5f, h * 0.26f),
                        radius = w * 0.85f
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(hud.secondaryAccent.copy(alpha = 0.16f * glow), Color.Transparent),
                            center = Offset(w * 0.8f, h * 0.8f),
                            radius = w * 0.65f
                        ),
                        center = Offset(w * 0.8f, h * 0.8f),
                        radius = w * 0.65f
                    )
                }
                "aurora" -> {
                    val shift = (drift - 0.5f) * w * 0.22f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(hud.accent.copy(alpha = 0.20f * glow), Color.Transparent),
                            center = Offset(w * 0.35f + shift, h * 0.22f),
                            radius = w * 0.8f
                        ),
                        center = Offset(w * 0.35f + shift, h * 0.22f),
                        radius = w * 0.8f
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(hud.secondaryAccent.copy(alpha = 0.22f * glow), Color.Transparent),
                            center = Offset(w * 0.7f - shift, h * 0.55f),
                            radius = w * 0.85f
                        ),
                        center = Offset(w * 0.7f - shift, h * 0.55f),
                        radius = w * 0.85f
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
fun ModernOrb(
    status: HudStatus,
    audioLevel: Float,
    onClick: () -> Unit,
    sizeDp: Dp = 188.dp,
    modifier: Modifier = Modifier
) {
    val hud = LocalJarvisColors.current
    val inf = rememberInfiniteTransition(label = "orb_anim")
    val phase by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "orb_phase"
    )
    val ripple by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "orb_ripple"
    )
    val amp by animateFloatAsState(
        targetValue = when (status) {
            HudStatus.LISTENING -> 0.45f + audioLevel * 0.55f
            HudStatus.SPEAKING -> 0.72f
            HudStatus.THINKING, HudStatus.WORKING -> 0.55f
            HudStatus.IDLE -> 0.22f
        },
        label = "orb_amp"
    )
    val desc = stringResource(R.string.cd_arc_reactor)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .testTag("hero_centerpiece")
            .semantics { contentDescription = desc }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false),
                role = Role.Button,
                onClick = onClick
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension * 0.34f

            // Ambient halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        hud.accent.copy(alpha = (0.34f + 0.22f * amp) * hud.glowAlpha),
                        hud.secondaryAccent.copy(alpha = 0.14f * hud.glowAlpha),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = r * 1.68f
                ),
                center = Offset(cx, cy),
                radius = r * 1.68f
            )

            // Listening / Speaking pulse ripples
            if (status == HudStatus.LISTENING || status == HudStatus.SPEAKING) {
                val r1 = r * (1.02f + ripple * 0.52f)
                drawCircle(
                    color = hud.accent.copy(alpha = (1f - ripple) * 0.55f),
                    radius = r1,
                    center = Offset(cx, cy),
                    style = Stroke(width = 2.5.dp.toPx())
                )
                val r2p = (ripple + 0.5f) % 1f
                drawCircle(
                    color = hud.secondaryAccent.copy(alpha = (1f - r2p) * 0.42f),
                    radius = r * (1.02f + r2p * 0.52f),
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.8.dp.toPx())
                )
            }

            // Orbiting arc when thinking / working
            if (status == HudStatus.THINKING || status == HudStatus.WORKING) {
                rotate(phase * 1.8f, pivot = Offset(cx, cy)) {
                    drawArc(
                        color = hud.accent.copy(alpha = 0.85f),
                        startAngle = 0f,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = Offset(cx - r * 1.2f, cy - r * 1.2f),
                        size = androidx.compose.ui.geometry.Size(r * 2.4f, r * 2.4f),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // Core glowing sphere
            val rad = r * (0.94f + amp * 0.10f)
            val radPhase = Math.toRadians(phase.toDouble())
            val swirlCenter = Offset(
                cx + (cos(radPhase) * rad * 0.24f).toFloat(),
                cy + (sin(radPhase) * rad * 0.24f).toFloat()
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.82f),
                        hud.accent,
                        hud.secondaryAccent.copy(alpha = 0.85f),
                        hud.background
                    ),
                    center = swirlCenter,
                    radius = rad * 1.2f
                ),
                center = Offset(cx, cy),
                radius = rad
            )
            drawCircle(
                color = hud.accent.copy(alpha = 0.45f),
                center = Offset(cx, cy),
                radius = rad,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

@Composable
fun IronManArcReactor(
    status: HudStatus,
    audioLevel: Float,
    onClick: () -> Unit,
    sizeDp: Dp = 204.dp,
    modifier: Modifier = Modifier
) {
    val hud = LocalJarvisColors.current
    val inf = rememberInfiniteTransition(label = "reactor_anim")
    val tick by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "reactor_spin"
    )
    val speedMult by animateFloatAsState(
        targetValue = when (status) {
            HudStatus.IDLE -> 1f
            HudStatus.WORKING -> 2.1f
            HudStatus.THINKING -> 2.6f
            HudStatus.LISTENING -> 3.0f + audioLevel * 1.5f
            HudStatus.SPEAKING -> 3.8f
        },
        label = "reactor_speed"
    )
    val pulse by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "reactor_pulse"
    )
    val desc = stringResource(R.string.cd_arc_reactor)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .testTag("hero_centerpiece")
            .semantics { contentDescription = desc }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false),
                role = Role.Button,
                onClick = onClick
            )
    ) {
        // Back rings + glow
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension * 0.46f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        hud.accent.copy(alpha = 0.28f * hud.glowAlpha),
                        hud.secondaryAccent.copy(alpha = 0.08f * hud.glowAlpha),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = r * 1.15f
                ),
                center = Offset(cx, cy),
                radius = r * 1.15f
            )

            // Outer dotted ring (90 dots)
            rotate(tick * 0.5f * speedMult, pivot = Offset(cx, cy)) {
                for (i in 0 until 90) {
                    val a = (i / 90.0) * PI * 2.0
                    val strong = i % 3 == 0
                    val px = cx + (cos(a) * r * 0.96f).toFloat()
                    val py = cy + (sin(a) * r * 0.96f).toFloat()
                    drawCircle(
                        color = hud.accent.copy(alpha = if (strong) 0.58f else 0.24f),
                        radius = if (strong) 1.7.dp.toPx() else 1.1.dp.toPx(),
                        center = Offset(px, py)
                    )
                }
            }

            // Clockwise tick ring (60 ticks)
            rotate(-tick * 0.8f * speedMult, pivot = Offset(cx, cy)) {
                for (i in 0 until 60) {
                    val a = (i / 60.0) * PI * 2.0
                    val longTick = i % 5 == 0
                    val r0 = r * if (longTick) 0.77f else 0.83f
                    val r1 = r * 0.90f
                    drawLine(
                        color = if (longTick) hud.accent.copy(alpha = 0.65f) else hud.accent.copy(alpha = 0.24f),
                        start = Offset(cx + (cos(a) * r0).toFloat(), cy + (sin(a) * r0).toFloat()),
                        end = Offset(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat()),
                        strokeWidth = if (longTick) 1.8.dp.toPx() else 1.dp.toPx()
                    )
                }
            }

            // Breathing rim
            drawCircle(
                color = hud.accent.copy(alpha = 0.32f + 0.24f * pulse),
                center = Offset(cx, cy),
                radius = r * (0.90f + 0.015f * pulse),
                style = Stroke(width = 1.6.dp.toPx())
            )
        }

        // Kept Iron Man helmet inside the reactor aperture
        Image(
            painter = painterResource(id = R.drawable.img_ironman_helmet),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp * 0.44f)
                .clip(CircleShape)
        )

        // Front segmented + inner gauge arcs
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.minDimension * 0.46f

            rotate(-tick * 1.1f * speedMult, pivot = Offset(cx, cy)) {
                val arcR = r * 0.72f
                val tl = Offset(cx - arcR, cy - arcR)
                val sz = androidx.compose.ui.geometry.Size(arcR * 2f, arcR * 2f)
                for (i in 0 until 7) {
                    val start = i * (360f / 7f) + 10f
                    drawArc(
                        color = hud.accent.copy(alpha = 0.68f),
                        startAngle = start,
                        sweepAngle = 27f,
                        useCenter = false,
                        topLeft = tl,
                        size = sz,
                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            rotate(tick * 1.5f * speedMult, pivot = Offset(cx, cy)) {
                val innerR = r * 0.59f
                val tl = Offset(cx - innerR, cy - innerR)
                val sz = androidx.compose.ui.geometry.Size(innerR * 2f, innerR * 2f)
                drawArc(
                    color = hud.secondaryAccent.copy(alpha = 0.85f),
                    startAngle = -20f,
                    sweepAngle = 120f,
                    useCenter = false,
                    topLeft = tl,
                    size = sz,
                    style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = hud.accent.copy(alpha = 0.55f),
                    startAngle = 160f,
                    sweepAngle = 95f,
                    useCenter = false,
                    topLeft = tl,
                    size = sz,
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
fun HudWaveform(
    status: HudStatus,
    audioLevel: Float,
    bars: Int = 32,
    modifier: Modifier = Modifier
) {
    val hud = LocalJarvisColors.current
    val inf = rememberInfiniteTransition(label = "wave")
    val phase by inf.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "wave_phase"
    )

    val energy = when (status) {
        HudStatus.IDLE -> 0.16f
        HudStatus.WORKING -> 0.45f
        HudStatus.THINKING -> 0.58f
        HudStatus.LISTENING -> 0.45f + audioLevel * 0.55f
        HudStatus.SPEAKING -> 0.85f
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.height(28.dp)
    ) {
        for (i in 0 until bars) {
            val envelope = sin(((i + 0.5f) / bars) * PI).toFloat()
            val wave = 0.5f + 0.5f * sin(phase + i * 0.48f)
            val barFraction = (0.14f + envelope * energy * wave).coerceIn(0.12f, 1f)
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height((26f * barFraction).dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (i % 3 == 0 && status != HudStatus.IDLE) hud.secondaryAccent.copy(alpha = 0.85f)
                        else hud.accent.copy(alpha = 0.78f)
                    )
            )
        }
    }
}
