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
        background = Color(0xFF0B0D12),
        surfacePanel = Color(0xFF151821),
        surfaceCard = Color(0xFF151821),
        accent = Color(0xFF38BDF8),
        secondaryAccent = Color(0xFF60A5FA),
        borderSubtle = Color(0xFF242936),
        textPrimary = Color(0xFFF3F5F8),
        textSecondary = Color(0xFF9AA3B2),
        success = Color(0xFF34D399),
        warning = Color(0xFFFBBF24),
        danger = Color(0xFFF87171),
        glowAlpha = 0f,
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
    val accent = Color(0xFF38BDF8)
    val secondary = Color(0xFF60A5FA)

    val hudColors = JarvisHudColors(
        background = Color(0xFF0B0D12),
        surfacePanel = Color(0xFF151821),
        surfaceCard = Color(0xFF151821),
        accent = accent,
        secondaryAccent = secondary,
        borderSubtle = Color(0xFF242936),
        textPrimary = Color(0xFFF3F5F8),
        textSecondary = Color(0xFF9AA3B2),
        success = Color(0xFF34D399),
        warning = Color(0xFFFBBF24),
        danger = Color(0xFFF87171),
        glowAlpha = 0f,
        isHudLook = false,
        cornerRadiusDp = screenConfig.cornerRadiusDp.coerceIn(10, 24)
    )

    val colorScheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF0B0D12),
        primaryContainer = Color(0xFF1E2533),
        onPrimaryContainer = Color(0xFFF3F5F8),
        secondary = secondary,
        onSecondary = Color(0xFF0B0D12),
        secondaryContainer = Color(0xFF1E2533),
        onSecondaryContainer = Color(0xFFF3F5F8),
        background = hudColors.background,
        onBackground = hudColors.textPrimary,
        surface = hudColors.surfacePanel,
        onSurface = hudColors.textPrimary,
        surfaceVariant = Color(0xFF1C202B),
        onSurfaceVariant = hudColors.textSecondary,
        outline = hudColors.borderSubtle,
        error = hudColors.danger,
        onError = Color(0xFF0B0D12)
    )

    val typography = Typography(
        displayLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
        headlineMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
        titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 14.5.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.2.sp),
        labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.2.sp)
    )

    val r = hudColors.cornerRadiusDp.dp
    val shapes = Shapes(
        extraSmall = RoundedCornerShape((r * 0.4f).coerceAtLeast(4.dp)),
        small = RoundedCornerShape((r * 0.65f).coerceAtLeast(8.dp)),
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
