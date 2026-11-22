package com.jarvis.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jarvis.app.R
import com.jarvis.app.domain.model.HudStatus
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

enum class OrbState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

fun resolveOrbState(
    isListening: Boolean,
    isSpeaking: Boolean,
    isBusy: Boolean,
    status: HudStatus
): OrbState = when {
    isListening || status == HudStatus.LISTENING -> OrbState.LISTENING
    isSpeaking || status == HudStatus.SPEAKING -> OrbState.SPEAKING
    isBusy || status == HudStatus.THINKING || status == HudStatus.WORKING -> OrbState.THINKING
    else -> OrbState.IDLE
}

private val VoidDark = Color(0xFF04060B)
private val IdleGlassBase = Color(0xFF0C1830)
private val ThinkingGlassBase = Color(0xFF090A18)

private val OrbCyan = Color(0xFF22D3EE)
private val OrbAquaBright = Color(0xFF67E8F9)
private val OrbTurquoise = Color(0xFF2DD4BF)
private val OrbEmerald = Color(0xFF4ADE80)
private val OrbElectricBlue = Color(0xFF3B82F6)
private val OrbViolet = Color(0xFF8B5CF6)
private val OrbLavender = Color(0xFFC4B5FD)
private val OrbMagenta = Color(0xFFEC4899)
private val OrbPinkSpark = Color(0xFFF472B6)
private val OrbOrchid = Color(0xFFE879F9)

private data class ListeningDotSeed(
    val xFrac: Float,
    val yOffsetFrac: Float,
    val radiusScale: Float,
    val phase: Float,
    val isBrightCore: Boolean
)

// Deterministic left-side cyan particle dispersion cloud (zero per-frame allocations)
private val LISTENING_DOTS: List<ListeningDotSeed> = buildList {
    // Subtle structured dot-matrix columns near center-left
    val cols = 10
    val rows = 5
    for (c in 0 until cols) {
        val xf = 0.08f + (c.toFloat() / cols) * 0.25f
        for (r in 0 until rows) {
            val yf = ((r - (rows - 1) / 2f) / 2.4f) * (0.35f + 0.55f * (1f - c / cols.toFloat()))
            val seedIdx = c * rows + r
            add(
                ListeningDotSeed(
                    xFrac = xf,
                    yOffsetFrac = yf,
                    radiusScale = if (seedIdx % 3 == 0) 1.05f else 0.72f,
                    phase = seedIdx * 0.67f,
                    isBrightCore = (c >= 6 && kotlin.math.abs(yf) < 0.22f)
                )
            )
        }
    }
    // Floating outer ambient cyan motes
    for (k in 0 until 22) {
        val angle = k * 2.39996f
        val xf = 0.03f + ((k * 17) % 38) / 100f
        val yf = sin(angle) * (0.14f + (k % 5) * 0.13f)
        add(
            ListeningDotSeed(
                xFrac = xf,
                yOffsetFrac = yf,
                radiusScale = 0.65f + (k % 4) * 0.30f,
                phase = k * 1.15f,
                isBrightCore = k % 5 == 0
            )
        )
    }
}

private data class SpeakingParticleSeed(
    val diagAlong: Float,
    val diagSpread: Float,
    val driftSpeed: Float,
    val pulsePhase: Float,
    val sizeScale: Float,
    val isBrightWhite: Boolean
)

// Diagonal swirling pink/magenta/white sparkle cluster inside Speaking dark core
private val SPEAKING_PARTICLES: List<SpeakingParticleSeed> = List(38) { idx ->
    val golden = idx * 2.3999632f
    val along = ((idx / 37f) - 0.5f) * 1.08f
    val spread = sin(golden) * (0.08f + (idx % 6) * 0.048f)
    SpeakingParticleSeed(
        diagAlong = along,
        diagSpread = spread,
        driftSpeed = if (idx % 2 == 0) 0.22f else -0.18f,
        pulsePhase = idx * 0.83f,
        sizeScale = 0.62f + (idx % 5) * 0.26f,
        isBrightWhite = idx % 4 == 0
    )
}

@Composable
fun RgbAssistantOrb(
    state: OrbState,
    audioLevel: Float,
    speakingLevel: Float,
    sizeDp: Dp = 204.dp,
    interactive: Boolean = true,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isCompact = sizeDp <= 44.dp
    val lifecycleOwner = LocalLifecycleOwner.current

    val smoothAudio by animateFloatAsState(
        targetValue = if (state == OrbState.LISTENING) audioLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "orb_audio_smooth"
    )
    val smoothSpeaking by animateFloatAsState(
        targetValue = if (state == OrbState.SPEAKING) speakingLevel.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium),
        label = "orb_speaking_smooth"
    )

    val idleMix by animateFloatAsState(
        targetValue = if (state == OrbState.IDLE) 1f else 0f,
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "orb_idle_mix"
    )
    val listeningMix by animateFloatAsState(
        targetValue = if (state == OrbState.LISTENING) 1f else 0f,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "orb_listen_mix"
    )
    val thinkingMix by animateFloatAsState(
        targetValue = if (state == OrbState.THINKING) 1f else 0f,
        animationSpec = tween(380, easing = FastOutSlowInEasing),
        label = "orb_think_mix"
    )
    val speakingMix by animateFloatAsState(
        targetValue = if (state == OrbState.SPEAKING) 1f else 0f,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "orb_speak_mix"
    )

    // LISTENING widens horizontally to display the left particle stream + right mesh waveform
    val animatedWidth by animateDpAsState(
        targetValue = if (state == OrbState.LISTENING) {
            if (isCompact) 38.dp else (sizeDp * 1.48f).coerceAtMost(308.dp)
        } else {
            sizeDp
        },
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "orb_stage_width"
    )
    val animatedHeight by animateDpAsState(
        targetValue = if (state == OrbState.LISTENING && !isCompact) {
            (sizeDp * 0.84f).coerceAtLeast(160.dp)
        } else {
            sizeDp
        },
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "orb_stage_height"
    )

    val audioRef = remember { mutableFloatStateOf(0f) }
    val speakingRef = remember { mutableFloatStateOf(0f) }
    val thinkingRef = remember { mutableFloatStateOf(0f) }
    audioRef.floatValue = smoothAudio
    speakingRef.floatValue = smoothSpeaking
    thinkingRef.floatValue = thinkingMix

    var liquidPhase by remember { mutableFloatStateOf(0f) }
    var wavePhase by remember { mutableFloatStateOf(0f) }
    var particlePhase by remember { mutableFloatStateOf(0f) }
    var breathPhase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastNanos = 0L
            while (isActive) {
                withFrameNanos { frameNanos ->
                    if (lastNanos != 0L) {
                        val dt = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                        val twoPi = 2f * PI.toFloat()
                        liquidPhase = (liquidPhase + dt * (0.68f + thinkingRef.floatValue * 0.48f)) % twoPi
                        wavePhase = (wavePhase + dt * (3.1f + audioRef.floatValue * 3.6f + thinkingRef.floatValue * 1.8f)) % twoPi
                        particlePhase = (particlePhase + dt * (1.05f + speakingRef.floatValue * 1.45f + audioRef.floatValue * 0.9f)) % twoPi
                        breathPhase = (breathPhase + dt * 1.3f) % twoPi
                    }
                    lastNanos = frameNanos
                }
            }
        }
    }

    val desc = stringResource(R.string.cd_orb_placeholder)
    val baseModifier = modifier
        .width(animatedWidth)
        .height(animatedHeight)
        .graphicsLayer {
            val idleBreath = 1f + 0.022f * sin(breathPhase) * idleMix
            val speakPulse = 1f + smoothSpeaking * 0.045f * speakingMix
            val totalScale = (idleBreath * speakPulse).coerceIn(0.92f, 1.12f)
            scaleX = totalScale
            scaleY = totalScale
        }

    val finalModifier = if (interactive && onClick != null) {
        baseModifier
            .testTag("hero_centerpiece")
            .semantics { contentDescription = desc }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = sizeDp * 0.52f),
                role = Role.Button,
                onClick = onClick
            )
    } else {
        baseModifier
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = finalModifier
            .drawWithCache {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val center = Offset(cx, cy)
                val sphereRadius = size.minDimension * if (isCompact) 0.43f else 0.38f

                // Reusable Paths (zero per-frame allocations)
                val spherePath = Path().apply {
                    addOval(
                        Rect(
                            left = cx - sphereRadius,
                            top = cy - sphereRadius,
                            right = cx + sphereRadius,
                            bottom = cy + sphereRadius
                        )
                    )
                }
                val specularPath = Path()
                val waveUpperPath = Path()
                val waveLowerPath = Path()
                val waveSecondaryPath = Path()

                // --- IDLE Brushes ---
                val idleAuraBrush = Brush.radialGradient(
                    colors = listOf(
                        OrbCyan.copy(alpha = 0.16f),
                        OrbElectricBlue.copy(alpha = 0.10f),
                        OrbOrchid.copy(alpha = 0.07f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = sphereRadius * 1.30f
                )
                val idleEmeraldCoreBrush = Brush.radialGradient(
                    colors = listOf(
                        OrbEmerald.copy(alpha = 0.68f),
                        OrbCyan.copy(alpha = 0.42f),
                        OrbElectricBlue.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = sphereRadius * 0.64f
                )
                val idleCyanPocketBrush = Brush.radialGradient(
                    colors = listOf(
                        OrbAquaBright.copy(alpha = 0.62f),
                        OrbCyan.copy(alpha = 0.34f),
                        Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = sphereRadius * 0.72f
                )
                val idleMagentaPocketBrush = Brush.radialGradient(
                    colors = listOf(
                        OrbOrchid.copy(alpha = 0.60f),
                        OrbMagenta.copy(alpha = 0.30f),
                        Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = sphereRadius * 0.68f
                )
                val idleBluePocketBrush = Brush.radialGradient(
                    colors = listOf(
                        OrbElectricBlue.copy(alpha = 0.56f),
                        OrbViolet.copy(alpha = 0.25f),
                        Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = sphereRadius * 0.74f
                )
                val idleRimBrush = Brush.linearGradient(
                    colors = listOf(
                        OrbAquaBright.copy(alpha = 0.92f),
                        OrbElectricBlue.copy(alpha = 0.75f),
                        OrbOrchid.copy(alpha = 0.94f),
                        OrbLavender.copy(alpha = 0.78f)
                    ),
                    start = Offset(cx - sphereRadius, cy + sphereRadius * 0.25f),
                    end = Offset(cx + sphereRadius, cy - sphereRadius * 0.45f)
                )
                val idleCausticBrush = Brush.linearGradient(
                    colors = listOf(
                        OrbAquaBright.copy(alpha = 0.42f),
                        Color.White.copy(alpha = 0.50f),
                        OrbOrchid.copy(alpha = 0.38f)
                    ),
                    start = Offset(cx - sphereRadius, cy - sphereRadius),
                    end = Offset(cx + sphereRadius, cy + sphereRadius)
                )

                // --- LISTENING Brushes ---
                val listeningWaveBrush = Brush.horizontalGradient(
                    0.00f to Color.Transparent,
                    0.22f to OrbAquaBright,
                    0.40f to OrbCyan,
                    0.62f to OrbElectricBlue,
                    0.82f to OrbViolet,
                    0.95f to OrbOrchid,
                    1.00f to Color.Transparent,
                    startX = 0f,
                    endX = size.width
                )
                val listeningSpineBrush = Brush.horizontalGradient(
                    0.00f to Color.Transparent,
                    0.15f to OrbCyan.copy(alpha = 0.75f),
                    0.35f to Color.White.copy(alpha = 0.95f),
                    0.56f to OrbAquaBright.copy(alpha = 0.85f),
                    0.82f to OrbOrchid.copy(alpha = 0.70f),
                    1.00f to Color.Transparent,
                    startX = 0f,
                    endX = size.width
                )
                val listeningFloorGlow = Brush.radialGradient(
                    colors = listOf(
                        OrbElectricBlue.copy(alpha = 0.34f),
                        OrbViolet.copy(alpha = 0.22f),
                        Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = size.width * 0.34f
                )

                // --- THINKING Brushes ---
                val thinkingFloorGlow = Brush.radialGradient(
                    colors = listOf(
                        OrbViolet.copy(alpha = 0.34f),
                        OrbElectricBlue.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = sphereRadius * 1.15f
                )
                val thinkingMeshWaveBrush = Brush.horizontalGradient(
                    0.00f to OrbCyan.copy(alpha = 0.25f),
                    0.25f to OrbViolet,
                    0.55f to OrbLavender,
                    0.82f to OrbViolet,
                    1.00f to OrbCyan.copy(alpha = 0.25f),
                    startX = cx - sphereRadius,
                    endX = cx + sphereRadius
                )
                val thinkingCyanRibbonBrush = Brush.horizontalGradient(
                    0.00f to OrbCyan.copy(alpha = 0.20f),
                    0.22f to OrbAquaBright,
                    0.50f to Color.White.copy(alpha = 0.92f),
                    0.78f to OrbAquaBright,
                    1.00f to OrbCyan.copy(alpha = 0.20f),
                    startX = cx - sphereRadius,
                    endX = cx + sphereRadius
                )
                val thinkingRimBrush = Brush.linearGradient(
                    colors = listOf(
                        OrbLavender.copy(alpha = 0.90f),
                        OrbViolet.copy(alpha = 0.72f),
                        OrbAquaBright.copy(alpha = 0.65f),
                        OrbLavender.copy(alpha = 0.85f)
                    ),
                    start = Offset(cx, cy - sphereRadius),
                    end = Offset(cx, cy + sphereRadius)
                )

                // --- SPEAKING Brushes ---
                val speakingOuterHaloBrush = Brush.radialGradient(
                    colors = listOf(
                        OrbTurquoise.copy(alpha = 0.56f),
                        OrbCyan.copy(alpha = 0.32f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = sphereRadius * 1.34f
                )
                val speakingRimBrush = Brush.linearGradient(
                    colors = listOf(
                        OrbAquaBright.copy(alpha = 0.95f),
                        OrbTurquoise.copy(alpha = 0.95f),
                        OrbCyan.copy(alpha = 0.88f),
                        OrbTurquoise.copy(alpha = 0.95f)
                    ),
                    start = Offset(cx - sphereRadius, cy - sphereRadius),
                    end = Offset(cx + sphereRadius, cy + sphereRadius)
                )
                val speakingVioletRimPocket = Brush.radialGradient(
                    colors = listOf(
                        OrbOrchid.copy(alpha = 0.68f),
                        OrbViolet.copy(alpha = 0.42f),
                        Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = sphereRadius * 0.74f
                )
                val speakingBlueRimPocket = Brush.radialGradient(
                    colors = listOf(
                        OrbElectricBlue.copy(alpha = 0.62f),
                        OrbTurquoise.copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    center = Offset.Zero,
                    radius = sphereRadius * 0.74f
                )
                val speakingPitchCoreBrush = Brush.radialGradient(
                    colors = listOf(
                        VoidDark.copy(alpha = 0.96f),
                        VoidDark.copy(alpha = 0.78f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = sphereRadius * 0.72f
                )

                val specularArcBrush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    start = Offset(cx - sphereRadius * 0.74f, cy - sphereRadius * 0.80f),
                    end = Offset(cx + sphereRadius * 0.30f, cy - sphereRadius * 0.10f)
                )

                val rimStrokePx = (if (isCompact) 1.1.dp else 1.8.dp).toPx()
                val meshStrandPx = (if (isCompact) 0.55.dp else 0.85.dp).toPx()
                val coreWaveStrokePx = (if (isCompact) 1.1.dp else 2.0.dp).toPx()

                onDrawBehind {
                    // 1. IDLE — Iridescent 3D Glass Bubble (green-cyan core + cyan/pink rim + refracting arcs)
                    if (idleMix > 0.01f) {
                        drawIdleIridescentGlassOrb(
                            cx = cx,
                            cy = cy,
                            center = center,
                            radius = sphereRadius,
                            alphaMix = idleMix,
                            liquidPhase = liquidPhase,
                            breathPhase = breathPhase,
                            spherePath = spherePath,
                            specularPath = specularPath,
                            auraBrush = idleAuraBrush,
                            emeraldCoreBrush = idleEmeraldCoreBrush,
                            cyanPocketBrush = idleCyanPocketBrush,
                            magentaPocketBrush = idleMagentaPocketBrush,
                            bluePocketBrush = idleBluePocketBrush,
                            rimBrush = idleRimBrush,
                            causticBrush = idleCausticBrush,
                            specularBrush = specularArcBrush,
                            rimStrokePx = rimStrokePx,
                            isCompact = isCompact
                        )
                    }

                    // 2. LISTENING — Left Particle Dispersion + Right 3D Vertical-Mesh Waveform
                    if (listeningMix > 0.01f) {
                        drawListeningParticleMeshWaveform(
                            width = size.width,
                            height = size.height,
                            cy = cy,
                            alphaMix = listeningMix,
                            audioLevel = smoothAudio,
                            wavePhase = wavePhase,
                            particlePhase = particlePhase,
                            waveBrush = listeningWaveBrush,
                            spineBrush = listeningSpineBrush,
                            floorGlowBrush = listeningFloorGlow,
                            upperPath = waveUpperPath,
                            lowerPath = waveLowerPath,
                            secondaryPath = waveSecondaryPath,
                            strandPx = meshStrandPx,
                            coreStrokePx = coreWaveStrokePx,
                            isCompact = isCompact
                        )
                    }

                    // 3. THINKING — Violet/Cyan Glass Bubble with Dual 3D Mesh Wave Ribbons
                    if (thinkingMix > 0.01f) {
                        drawThinkingMeshGlassOrb(
                            cx = cx,
                            cy = cy,
                            center = center,
                            radius = sphereRadius,
                            alphaMix = thinkingMix,
                            wavePhase = wavePhase,
                            liquidPhase = liquidPhase,
                            spherePath = spherePath,
                            specularPath = specularPath,
                            upperPath = waveUpperPath,
                            lowerPath = waveLowerPath,
                            floorGlowBrush = thinkingFloorGlow,
                            meshWaveBrush = thinkingMeshWaveBrush,
                            cyanRibbonBrush = thinkingCyanRibbonBrush,
                            rimBrush = thinkingRimBrush,
                            specularBrush = specularArcBrush,
                            rimStrokePx = rimStrokePx,
                            strandPx = meshStrandPx,
                            coreStrokePx = coreWaveStrokePx,
                            isCompact = isCompact
                        )
                    }

                    // 4. SPEAKING — Turquoise Halo Glass Orb + Diagonal Lens Folds + Pink Sparkle Core
                    if (speakingMix > 0.01f) {
                        drawSpeakingSparkleGlassOrb(
                            cx = cx,
                            cy = cy,
                            center = center,
                            radius = sphereRadius,
                            alphaMix = speakingMix,
                            speakingLevel = smoothSpeaking,
                            liquidPhase = liquidPhase,
                            particlePhase = particlePhase,
                            spherePath = spherePath,
                            specularPath = specularPath,
                            outerHaloBrush = speakingOuterHaloBrush,
                            rimBrush = speakingRimBrush,
                            violetPocketBrush = speakingVioletRimPocket,
                            bluePocketBrush = speakingBlueRimPocket,
                            pitchCoreBrush = speakingPitchCoreBrush,
                            specularBrush = specularArcBrush,
                            rimStrokePx = rimStrokePx,
                            isCompact = isCompact
                        )
                    }
                }
            }
    ) {
        // Pure visual animation: zero text or labels inside
    }
}

private fun DrawScope.drawIdleIridescentGlassOrb(
    cx: Float,
    cy: Float,
    center: Offset,
    radius: Float,
    alphaMix: Float,
    liquidPhase: Float,
    breathPhase: Float,
    spherePath: Path,
    specularPath: Path,
    auraBrush: Brush,
    emeraldCoreBrush: Brush,
    cyanPocketBrush: Brush,
    magentaPocketBrush: Brush,
    bluePocketBrush: Brush,
    rimBrush: Brush,
    causticBrush: Brush,
    specularBrush: Brush,
    rimStrokePx: Float,
    isCompact: Boolean
) {
    val breath = 0.5f + 0.5f * sin(breathPhase)

    // Soft outer ambient edge glow
    drawCircle(
        brush = auraBrush,
        radius = radius * 1.30f,
        center = center,
        alpha = (0.75f + 0.25f * breath) * alphaMix
    )

    clipPath(spherePath) {
        // Deep luminous translucent blue glass base
        drawCircle(
            color = IdleGlassBase,
            radius = radius,
            center = center,
            alpha = alphaMix
        )

        // Left aqua-cyan volumetric glow
        translate(
            left = cx - radius * 0.44f + cos(liquidPhase * 0.8f) * radius * 0.14f,
            top = cy + radius * 0.12f + sin(liquidPhase) * radius * 0.18f
        ) {
            drawCircle(brush = cyanPocketBrush, radius = radius * 0.78f, center = Offset.Zero, alpha = alphaMix)
        }

        // Top-right & lower-right pink/orchid iridescent pocket
        translate(
            left = cx + radius * 0.44f + sin(liquidPhase * 0.7f) * radius * 0.12f,
            top = cy - radius * 0.26f + cos(liquidPhase * 0.9f) * radius * 0.22f
        ) {
            drawCircle(brush = magentaPocketBrush, radius = radius * 0.76f, center = Offset.Zero, alpha = alphaMix)
        }

        // Electric blue volumetric fill
        translate(
            left = cx + cos(liquidPhase + 2.1f) * radius * 0.28f,
            top = cy + radius * 0.34f + sin(liquidPhase * 1.1f) * radius * 0.16f
        ) {
            drawCircle(brush = bluePocketBrush, radius = radius * 0.82f, center = Offset.Zero, alpha = alphaMix)
        }

        // Central luminous green-cyan core (signature glow of Screenshot 1)
        translate(
            left = cx + cos(liquidPhase * 0.6f) * radius * 0.08f,
            top = cy + sin(liquidPhase * 0.8f) * radius * 0.08f
        ) {
            drawCircle(
                brush = emeraldCoreBrush,
                radius = radius * (0.58f + 0.05f * breath),
                center = Offset.Zero,
                alpha = alphaMix
            )
        }

        // Diagonal 3D curved translucent glass folds & fine orbital light caustics
        if (!isCompact) {
            val tiltBase = -34f + sin(liquidPhase) * 6f
            rotate(degrees = tiltBase, pivot = center) {
                drawOval(
                    color = OrbAquaBright.copy(alpha = 0.14f * alphaMix),
                    topLeft = Offset(cx - radius * 0.95f, cy - radius * 0.46f),
                    size = Size(radius * 1.90f, radius * 0.92f),
                    style = Stroke(width = rimStrokePx * 2.4f)
                )
                drawOval(
                    brush = causticBrush,
                    topLeft = Offset(cx - radius * 0.88f, cy - radius * 0.30f),
                    size = Size(radius * 1.76f, radius * 0.60f),
                    alpha = 0.62f * alphaMix,
                    style = Stroke(width = 1.1.dp.toPx())
                )
            }

            rotate(degrees = 26f + cos(liquidPhase * 0.9f) * 8f, pivot = center) {
                drawOval(
                    color = Color.White.copy(alpha = 0.28f * alphaMix),
                    topLeft = Offset(cx - radius * 0.78f, cy - radius * 0.42f),
                    size = Size(radius * 1.56f, radius * 0.84f),
                    style = Stroke(width = 0.9.dp.toPx())
                )
            }

            rotate(degrees = -8f + sin(liquidPhase * 1.3f) * 7f, pivot = center) {
                drawOval(
                    color = OrbAquaBright.copy(alpha = 0.26f * alphaMix),
                    topLeft = Offset(cx - radius * 0.86f, cy - radius * 0.20f),
                    size = Size(radius * 1.72f, radius * 0.40f),
                    style = Stroke(width = 0.85.dp.toPx())
                )
            }

            drawGlassSpecularHighlight(
                cx = cx,
                cy = cy,
                radius = radius,
                alphaMix = alphaMix,
                specularPath = specularPath,
                specularBrush = specularBrush
            )
        }
    }

    // Crisp double-edged iridescent glass sphere rim
    drawCircle(
        brush = rimBrush,
        radius = radius * 0.955f,
        center = center,
        alpha = 0.45f * alphaMix,
        style = Stroke(width = rimStrokePx * 0.75f)
    )
    drawCircle(
        brush = rimBrush,
        radius = radius,
        center = center,
        alpha = 0.92f * alphaMix,
        style = Stroke(width = rimStrokePx * 1.15f)
    )
}

private fun DrawScope.drawListeningParticleMeshWaveform(
    width: Float,
    height: Float,
    cy: Float,
    alphaMix: Float,
    audioLevel: Float,
    wavePhase: Float,
    particlePhase: Float,
    waveBrush: Brush,
    spineBrush: Brush,
    floorGlowBrush: Brush,
    upperPath: Path,
    lowerPath: Path,
    secondaryPath: Path,
    strandPx: Float,
    coreStrokePx: Float,
    isCompact: Boolean
) {
    val activeAmp = (0.20f + audioLevel * 0.80f).coerceIn(0.20f, 1.0f)
    val maxPeak = height * if (isCompact) 0.34f else 0.39f

    // Soft floor glow reflection beneath the wave
    if (!isCompact) {
        translate(left = width * 0.62f, top = cy + maxPeak * 0.92f) {
            scale(scaleX = 1.1f, scaleY = 0.26f, pivot = Offset.Zero) {
                drawCircle(
                    brush = floorGlowBrush,
                    radius = width * 0.34f,
                    center = Offset.Zero,
                    alpha = (0.55f + audioLevel * 0.45f) * alphaMix
                )
            }
        }
    }

    // Left-side converging cyan dot-matrix & particle cloud
    if (!isCompact) {
        val baseDotPx = 1.4.dp.toPx()
        for (dot in LISTENING_DOTS) {
            val driftX = ((dot.xFrac + (particlePhase * 0.015f)) % 0.40f)
            val px = width * (0.03f + driftX)
            val py = cy + dot.yOffsetFrac * maxPeak * (0.78f + audioLevel * 0.38f)
            val shimmer = 0.40f + 0.60f * sin(particlePhase * 1.8f + dot.phase)
            val dotAlpha = ((0.22f + shimmer * 0.58f + audioLevel * 0.20f) * alphaMix).coerceIn(0f, 0.95f)
            val dotRadius = baseDotPx * dot.radiusScale * (0.85f + audioLevel * 0.40f)

            drawCircle(
                color = OrbCyan.copy(alpha = dotAlpha * 0.30f),
                radius = dotRadius * 2.5f,
                center = Offset(px, py)
            )
            drawCircle(
                color = (if (dot.isBrightCore) Color.White else OrbAquaBright).copy(alpha = dotAlpha),
                radius = dotRadius,
                center = Offset(px, py)
            )
        }
    }

    // Bright horizontal laser spine converging into the waveform
    drawLine(
        brush = spineBrush,
        start = Offset(width * 0.04f, cy),
        end = Offset(width * 0.97f, cy),
        strokeWidth = if (isCompact) 1.dp.toPx() else 1.5.dp.toPx(),
        alpha = (0.65f + audioLevel * 0.35f) * alphaMix,
        cap = StrokeCap.Round
    )

    // Focal bright cyan flare where particle stream enters the mesh wave
    if (!isCompact) {
        drawCircle(
            color = OrbAquaBright.copy(alpha = (0.28f + audioLevel * 0.30f) * alphaMix),
            radius = height * 0.08f,
            center = Offset(width * 0.35f, cy)
        )
        drawCircle(
            color = Color.White.copy(alpha = (0.55f + audioLevel * 0.35f) * alphaMix),
            radius = height * 0.025f,
            center = Offset(width * 0.35f, cy)
        )
    }

    // Center-left to right 3D vertical-mesh audio wave curtain
    val waveStartFrac = if (isCompact) 0.06f else 0.25f
    val waveEndFrac = 0.96f
    val waveStartX = width * waveStartFrac
    val waveSpan = width * (waveEndFrac - waveStartFrac)
    val sampleCount = if (isCompact) 20 else 56

    upperPath.reset()
    lowerPath.reset()
    secondaryPath.reset()

    for (i in 0..sampleCount) {
        val t = i.toFloat() / sampleCount
        val x = waveStartX + waveSpan * t
        val env = sin(PI * t).toFloat().pow(1.25f)
        val mainOsc = sin(t * 3.1f * 2f * PI.toFloat() - wavePhase) +
            0.42f * sin(t * 5.3f * 2f * PI.toFloat() + wavePhase * 0.7f)
        val modAmp = mainOsc * 0.72f * env * maxPeak * activeAmp

        val yTop = cy + modAmp
        val yBot = cy - modAmp * (0.72f + 0.18f * cos(t * 4f * PI.toFloat() + wavePhase * 0.5f))
        val ySec = cy + modAmp * 0.45f * cos(t * 2.2f * PI.toFloat() - wavePhase * 0.9f)

        if (i == 0) {
            upperPath.moveTo(x, yTop)
            lowerPath.moveTo(x, yBot)
            secondaryPath.moveTo(x, ySec)
        } else {
            upperPath.lineTo(x, yTop)
            lowerPath.lineTo(x, yBot)
            secondaryPath.lineTo(x, ySec)
        }

        // Fine vertical mesh strands between upper and lower ridges
        if (i in 1 until sampleCount && (!isCompact || i % 2 == 0)) {
            drawLine(
                brush = waveBrush,
                start = Offset(x, yTop),
                end = Offset(x, yBot),
                strokeWidth = strandPx,
                alpha = (0.34f + audioLevel * 0.22f) * alphaMix
            )
        }
    }

    // Soft outer glow + crisp glowing core ridges
    if (!isCompact) {
        drawPath(
            path = upperPath,
            brush = waveBrush,
            alpha = (0.28f + audioLevel * 0.20f) * alphaMix,
            style = Stroke(width = coreStrokePx * 3.4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
    drawPath(
        path = upperPath,
        brush = waveBrush,
        alpha = 0.96f * alphaMix,
        style = Stroke(width = coreStrokePx * 1.05f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(
        path = lowerPath,
        brush = waveBrush,
        alpha = 0.62f * alphaMix,
        style = Stroke(width = coreStrokePx * 0.78f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    if (!isCompact) {
        drawPath(
            path = secondaryPath,
            brush = spineBrush,
            alpha = 0.42f * alphaMix,
            style = Stroke(width = coreStrokePx * 0.58f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

private fun DrawScope.drawThinkingMeshGlassOrb(
    cx: Float,
    cy: Float,
    center: Offset,
    radius: Float,
    alphaMix: Float,
    wavePhase: Float,
    liquidPhase: Float,
    spherePath: Path,
    specularPath: Path,
    upperPath: Path,
    lowerPath: Path,
    floorGlowBrush: Brush,
    meshWaveBrush: Brush,
    cyanRibbonBrush: Brush,
    rimBrush: Brush,
    specularBrush: Brush,
    rimStrokePx: Float,
    strandPx: Float,
    coreStrokePx: Float,
    isCompact: Boolean
) {
    // Soft purple floor reflection beneath sphere (Screenshot 3)
    if (!isCompact) {
        translate(left = cx, top = cy + radius * 1.24f) {
            scale(scaleX = 1.15f, scaleY = 0.30f, pivot = Offset.Zero) {
                drawCircle(
                    brush = floorGlowBrush,
                    radius = radius * 1.10f,
                    center = Offset.Zero,
                    alpha = 0.85f * alphaMix
                )
            }
        }
    }

    clipPath(spherePath) {
        drawCircle(
            color = ThinkingGlassBase,
            radius = radius,
            center = center,
            alpha = alphaMix
        )

        // Internal dual 3D fine-mesh wave ribbons
        val sampleCount = if (isCompact) 18 else 44
        val startX = cx - radius * 1.02f
        val span = radius * 2.04f
        val maxAmp = radius * 0.48f

        upperPath.reset()
        lowerPath.reset()

        for (i in 0..sampleCount) {
            val t = i.toFloat() / sampleCount
            val x = startX + span * t
            val env = sin(PI * t).toFloat().pow(0.9f)
            val violetWave = sin(t * 2.0f * 2f * PI.toFloat() - wavePhase * 0.85f) * 0.65f +
                0.35f * cos(t * 1.2f * 2f * PI.toFloat() + liquidPhase)
            val cyanWave = sin(t * 1.6f * 2f * PI.toFloat() + wavePhase * 0.95f) * 0.42f

            val yUpper = cy - radius * 0.14f - kotlin.math.abs(violetWave) * maxAmp * env
            val yCyan = cy + radius * 0.08f + cyanWave * maxAmp * 0.72f * env

            if (i == 0) {
                upperPath.moveTo(x, yUpper)
                lowerPath.moveTo(x, yCyan)
            } else {
                upperPath.lineTo(x, yUpper)
                lowerPath.lineTo(x, yCyan)
            }

            if (i in 1 until sampleCount && (!isCompact || i % 2 == 0)) {
                drawLine(
                    brush = meshWaveBrush,
                    start = Offset(x, yUpper),
                    end = Offset(x, yCyan),
                    strokeWidth = strandPx,
                    alpha = 0.42f * alphaMix
                )
            }
        }

        // Glow + crisp ribbon strokes
        drawPath(
            path = upperPath,
            brush = meshWaveBrush,
            alpha = 0.92f * alphaMix,
            style = Stroke(width = coreStrokePx * 0.95f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        if (!isCompact) {
            drawPath(
                path = lowerPath,
                brush = cyanRibbonBrush,
                alpha = 0.34f * alphaMix,
                style = Stroke(width = coreStrokePx * 3.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        drawPath(
            path = lowerPath,
            brush = cyanRibbonBrush,
            alpha = 0.98f * alphaMix,
            style = Stroke(width = coreStrokePx * 1.15f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        if (!isCompact) {
            drawGlassSpecularHighlight(
                cx = cx,
                cy = cy,
                radius = radius,
                alphaMix = alphaMix,
                specularPath = specularPath,
                specularBrush = specularBrush
            )
        }
    }

    // Crisp purple-lavender glass shell rim
    drawCircle(
        brush = rimBrush,
        radius = radius,
        center = center,
        alpha = 0.88f * alphaMix,
        style = Stroke(width = rimStrokePx)
    )
}

private fun DrawScope.drawSpeakingSparkleGlassOrb(
    cx: Float,
    cy: Float,
    center: Offset,
    radius: Float,
    alphaMix: Float,
    speakingLevel: Float,
    liquidPhase: Float,
    particlePhase: Float,
    spherePath: Path,
    specularPath: Path,
    outerHaloBrush: Brush,
    rimBrush: Brush,
    violetPocketBrush: Brush,
    bluePocketBrush: Brush,
    pitchCoreBrush: Brush,
    specularBrush: Brush,
    rimStrokePx: Float,
    isCompact: Boolean
) {
    val activeBoost = speakingLevel.coerceIn(0f, 1f)

    // Bright cyan-turquoise outer halo (Screenshot 4)
    drawCircle(
        brush = outerHaloBrush,
        radius = radius * (1.28f + activeBoost * 0.08f),
        center = center,
        alpha = (0.70f + activeBoost * 0.30f) * alphaMix
    )

    clipPath(spherePath) {
        drawCircle(
            color = VoidDark,
            radius = radius,
            center = center,
            alpha = alphaMix
        )

        // Perimeter violet/magenta and electric-blue/turquoise energy pockets
        translate(
            left = cx + cos(liquidPhase * 0.9f) * radius * 0.44f,
            top = cy - radius * 0.42f + sin(liquidPhase) * radius * 0.18f
        ) {
            drawCircle(
                brush = violetPocketBrush,
                radius = radius * 0.78f,
                center = Offset.Zero,
                alpha = (0.75f + activeBoost * 0.25f) * alphaMix
            )
        }

        translate(
            left = cx - radius * 0.44f + sin(liquidPhase * 1.1f) * radius * 0.14f,
            top = cy + radius * 0.36f + cos(liquidPhase) * radius * 0.20f
        ) {
            drawCircle(
                brush = bluePocketBrush,
                radius = radius * 0.78f,
                center = Offset.Zero,
                alpha = (0.75f + activeBoost * 0.25f) * alphaMix
            )
        }

        // Pitch-black center core so pink particles stand out sharply
        drawCircle(
            brush = pitchCoreBrush,
            radius = radius * 0.72f,
            center = center,
            alpha = alphaMix
        )

        // Diagonal glass crescent slices & crisp refractive edge arcs (Screenshot 4)
        if (!isCompact) {
            val sliceAngle = -38f + sin(liquidPhase * 0.7f) * 5f
            rotate(degrees = sliceAngle, pivot = center) {
                drawOval(
                    color = OrbOrchid.copy(alpha = (0.28f + activeBoost * 0.14f) * alphaMix),
                    topLeft = Offset(cx - radius * 0.98f, cy - radius * 0.56f),
                    size = Size(radius * 1.96f, radius * 1.12f),
                    style = Stroke(width = rimStrokePx * 2.2f)
                )
                drawOval(
                    color = OrbAquaBright.copy(alpha = (0.52f + activeBoost * 0.24f) * alphaMix),
                    topLeft = Offset(cx - radius * 0.96f, cy - radius * 0.46f),
                    size = Size(radius * 1.92f, radius * 0.92f),
                    style = Stroke(width = 1.05.dp.toPx())
                )
            }
            rotate(degrees = -12f + cos(liquidPhase * 0.8f) * 4f, pivot = center) {
                drawOval(
                    color = OrbTurquoise.copy(alpha = 0.36f * alphaMix),
                    topLeft = Offset(cx - radius * 0.92f, cy - radius * 0.68f),
                    size = Size(radius * 1.84f, radius * 1.36f),
                    style = Stroke(width = 0.95.dp.toPx())
                )
            }
        }

        // Diagonal pink/magenta/white glowing particle cluster inside the black center
        val clusterAngleRad = (-44f + sin(liquidPhase * 0.5f) * 6f) * (PI.toFloat() / 180f)
        val cosA = cos(clusterAngleRad)
        val sinA = sin(clusterAngleRad)
        val baseParticlePx = (if (isCompact) 0.9.dp else 1.55.dp).toPx()
        val particleStep = if (isCompact) 3 else 1

        for (i in SPEAKING_PARTICLES.indices step particleStep) {
            val seed = SPEAKING_PARTICLES[i]
            val localAlong = seed.diagAlong * radius * (0.76f + activeBoost * 0.14f) +
                sin(particlePhase * seed.driftSpeed + seed.pulsePhase) * radius * 0.05f
            val localSpread = seed.diagSpread * radius * (0.88f + activeBoost * 0.30f)

            val px = cx + localAlong * cosA - localSpread * sinA
            val py = cy + localAlong * sinA + localSpread * cosA

            val twinkle = 0.45f + 0.55f * sin(particlePhase * 2.1f + seed.pulsePhase)
            val pAlpha = ((0.42f + 0.48f * twinkle + activeBoost * 0.20f) * alphaMix).coerceIn(0f, 1f)
            val pRadius = baseParticlePx * seed.sizeScale * (0.85f + activeBoost * 0.55f)
            val pColor = if (seed.isBrightWhite) Color.White else if (i % 2 == 0) OrbPinkSpark else OrbOrchid

            drawCircle(
                color = OrbOrchid.copy(alpha = pAlpha * 0.30f),
                radius = pRadius * 2.5f,
                center = Offset(px, py)
            )
            drawCircle(
                color = pColor.copy(alpha = pAlpha),
                radius = pRadius,
                center = Offset(px, py)
            )
        }

        if (!isCompact) {
            drawGlassSpecularHighlight(
                cx = cx,
                cy = cy,
                radius = radius,
                alphaMix = alphaMix,
                specularPath = specularPath,
                specularBrush = specularBrush
            )
        }
    }

    // Crisp double turquoise/cyan outer glass rim
    drawCircle(
        brush = rimBrush,
        radius = radius * 0.945f,
        center = center,
        alpha = (0.55f + activeBoost * 0.25f) * alphaMix,
        style = Stroke(width = rimStrokePx * 0.8f)
    )
    drawCircle(
        brush = rimBrush,
        radius = radius,
        center = center,
        alpha = (0.90f + activeBoost * 0.10f).coerceIn(0f, 1f) * alphaMix,
        style = Stroke(width = rimStrokePx * (1.25f + activeBoost * 0.35f))
    )
}

private fun DrawScope.drawGlassSpecularHighlight(
    cx: Float,
    cy: Float,
    radius: Float,
    alphaMix: Float,
    specularPath: Path,
    specularBrush: Brush
) {
    specularPath.reset()
    val arcRect = Rect(
        left = cx - radius * 0.88f,
        top = cy - radius * 0.88f,
        right = cx + radius * 0.88f,
        bottom = cy + radius * 0.88f
    )
    specularPath.addArc(oval = arcRect, startAngleDegrees = 208f, sweepAngleDegrees = 72f)
    drawPath(
        path = specularPath,
        brush = specularBrush,
        alpha = 0.72f * alphaMix,
        style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round)
    )
}
