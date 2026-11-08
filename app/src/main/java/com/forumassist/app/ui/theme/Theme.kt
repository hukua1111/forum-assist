package com.forumassist.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forumassist.app.data.Settings

val AccentPresets = listOf(
    "靛蓝" to 0xFF4C6FFF.toInt(),
    "松绿" to 0xFF12B886.toInt(),
    "暖橙" to 0xFFF76707.toInt(),
    "莓红" to 0xFFE03131.toInt(),
    "夜紫" to 0xFF8B5CF6.toInt(),
    "青蓝" to 0xFF1098AD.toInt()
)

private fun lightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.12f).compositeOver(Color.White),
    onPrimaryContainer = accent,
    secondary = accent,
    onSecondary = Color.White,
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF12141A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12141A),
    surfaceVariant = Color(0xFFEEF1F6),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFE1E5EE),
    outlineVariant = Color(0xFFEDF0F5),
    error = Color(0xFFD93B3B)
)

private fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.22f).compositeOver(Color(0xFF12151C)),
    onPrimaryContainer = accent,
    secondary = accent,
    onSecondary = Color.White,
    background = Color(0xFF0C0E13),
    onBackground = Color(0xFFE8EBF2),
    surface = Color(0xFF14171E),
    onSurface = Color(0xFFE8EBF2),
    surfaceVariant = Color(0xFF1C212A),
    onSurfaceVariant = Color(0xFF98A1B2),
    outline = Color(0xFF282E39),
    outlineVariant = Color(0xFF1F242D),
    error = Color(0xFFFF6B6B)
)

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(
        red = red * a + bg.red * (1 - a),
        green = green * a + bg.green * (1 - a),
        blue = blue * a + bg.blue * (1 - a),
        alpha = 1f
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private fun appTypography(): Typography {
    val base = Typography()
    fun TextStyle.tight() = copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)
    return Typography(
        displayLarge = base.displayLarge.tight(),
        displayMedium = base.displayMedium.tight(),
        displaySmall = base.displaySmall.tight(),
        headlineLarge = base.headlineLarge.tight(),
        headlineMedium = base.headlineMedium.tight(),
        headlineSmall = base.headlineSmall.tight(),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 21.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium, fontSize = 14.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.5.sp, lineHeight = 23.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
        labelMedium = base.labelMedium.copy(fontSize = 11.5.sp),
        labelSmall = base.labelSmall.copy(fontSize = 10.5.sp)
    )
}

@Composable
fun ForumTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val accent = Color(settings.accent)
    val scheme = if (dark) darkScheme(accent) else lightScheme(accent)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides androidx.compose.ui.unit.Density(
            density = density.density,
            fontScale = settings.fontScale
        )
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = AppShapes,
            typography = appTypography(),
            content = content
        )
    }
}
