package com.jarvis.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.app.R
import com.jarvis.app.domain.model.ScreenConfig

val OrbitronFamily = FontFamily(Font(R.font.orbitron, FontWeight.Normal))
val ShareTechMonoFamily = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))
val InterFamily = FontFamily(Font(R.font.inter, FontWeight.Normal))

@Immutable
data class JarvisHudColors(
    val background: Color,
    val surfacePanel: Color,
    val surfaceCard: Color,
    val accent: Color,
    val secondaryAccent: Color,
    val borderSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val glowAlpha: Float,
    val isHudLook: Boolean,
    val cornerRadiusDp: Int
)

val LocalJarvisColors = staticCompositionLocalOf {
    JarvisHudColors(
        background = Color(0xFF04070D),
        surfacePanel = Color(0xFF0A111D),
        surfaceCard = Color(0xFF0E1728),
        accent = Color(0xFF00E5FF),
        secondaryAccent = Color(0xFF3B82F6),
        borderSubtle = Color(0x3300E5FF),
        textPrimary = Color(0xFFEAF4FF),
        textSecondary = Color(0xFF8CA3BF),
        success = Color(0xFF34D399),
        warning = Color(0xFFFBBF24),
        danger = Color(0xFFF87171),
        glowAlpha = 0.65f,
        isHudLook = false,
        cornerRadiusDp = 16
    )
}

fun parseHexColor(hex: String, fallback: Color): Color {
    return try {
        val clean = hex.trim().removePrefix("#")
        val value = clean.toLong(16)
        when (clean.length) {
            6 -> Color(0xFF000000L or value)
            8 -> Color(value)
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}

@Composable
fun JarvisTheme(
    screenConfig: ScreenConfig,
    content: @Composable () -> Unit
) {
    val accent = parseHexColor(screenConfig.mainHex, Color(0xFF00E5FF))
    val secondary = parseHexColor(screenConfig.secondHex, Color(0xFF3B82F6))
    val isHud = screenConfig.look == "ironman" || screenConfig.fontStyle == "hud"
    val glowFactor = (screenConfig.glowStrength.coerceIn(0, 100) / 100f)

    val hudColors = JarvisHudColors(
        background = Color(0xFF04070D),
        surfacePanel = Color(0xFF0A111D),
        surfaceCard = if (screenConfig.cardStyle == "hud") Color(0xCC091322) else Color(0xE60D1626),
        accent = accent,
        secondaryAccent = secondary,
        borderSubtle = accent.copy(alpha = 0.16f + 0.18f * glowFactor),
        textPrimary = Color(0xFFEBF5FF),
        textSecondary = Color(0xFF8AA2BE),
        success = Color(0xFF34D399),
        warning = Color(0xFFFBBF24),
        danger = Color(0xFFF87171),
        glowAlpha = glowFactor,
        isHudLook = isHud,
        cornerRadiusDp = screenConfig.cornerRadiusDp.coerceIn(4, 24)
    )

    val colorScheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF031018),
        primaryContainer = accent.copy(alpha = 0.18f),
        onPrimaryContainer = accent,
        secondary = secondary,
        onSecondary = Color.White,
        secondaryContainer = secondary.copy(alpha = 0.18f),
        onSecondaryContainer = Color(0xFFE2EEFF),
        background = hudColors.background,
        onBackground = hudColors.textPrimary,
        surface = hudColors.surfacePanel,
        onSurface = hudColors.textPrimary,
        surfaceVariant = hudColors.surfaceCard,
        onSurfaceVariant = hudColors.textSecondary,
        outline = hudColors.borderSubtle,
        error = hudColors.danger,
        onError = Color.Black
    )

    val displayFamily = if (isHud) OrbitronFamily else InterFamily
    val labelFamily = if (screenConfig.fontStyle == "hud") ShareTechMonoFamily else InterFamily
    val bodyFamily = InterFamily

    val typography = Typography(
        displayLarge = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = if (isHud) 2.sp else 0.sp),
        headlineMedium = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = if (isHud) 1.4.sp else 0.sp),
        titleLarge = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
        titleMedium = TextStyle(fontFamily = labelFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        bodyLarge = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = labelFamily, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, letterSpacing = 0.7.sp),
        labelSmall = TextStyle(fontFamily = labelFamily, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.8.sp)
    )

    val r = hudColors.cornerRadiusDp.dp
    val shapes = Shapes(
        extraSmall = RoundedCornerShape((r * 0.4f).coerceAtLeast(4.dp)),
        small = RoundedCornerShape((r * 0.65f).coerceAtLeast(6.dp)),
        medium = RoundedCornerShape(r),
        large = RoundedCornerShape((r * 1.25f).coerceAtMost(28.dp)),
        extraLarge = RoundedCornerShape(28.dp)
    )

    CompositionLocalProvider(LocalJarvisColors provides hudColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = shapes,
            content = content
        )
    }
}
