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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forumassist.app.data.Settings
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDark
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLight

/**
 * 主题配色。
 *
 * 主色仍由用户自选（设置里的六种强调色），但中性色全部换成 HyperOS 的一套：
 * 浅色页面底 #F2F3F5 + 纯白卡片，深色纯黑底 + #1C1C1E 卡片，文字用带一点灰的
 * 中性色而不是纯黑纯白，长时间看正文不刺眼。
 */
val AccentPresets = listOf(
    "靛蓝" to 0xFF3482FF.toInt(),
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
    background = Color(0xFFF2F3F5),
    onBackground = Color(0xFF111114),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFF5F6F8),
    onSurfaceVariant = Color(0xFF7D818B),
    outline = Color(0xFFE2E4EA),
    outlineVariant = Color(0xFFECEDF0),
    error = Color(0xFFE94634)
)

private fun darkScheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.24f).compositeOver(Color(0xFF1C1C1E)),
    onPrimaryContainer = accent,
    secondary = accent,
    onSecondary = Color.White,
    background = Color(0xFF000000),
    onBackground = Color(0xFFE9E9EC),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFE9E9EC),
    surfaceVariant = Color(0xFF17171A),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2C2C2E),
    outlineVariant = Color(0xFF232326),
    error = Color(0xFFFF6B6B)
)

/** MIUIX 配色：页面底、卡片、分割线、输入框底都按 HyperOS 来，主色跟用户选的强调色走 */
private fun miuixColors(accent: Color, dark: Boolean): Colors =
    if (dark) {
        miuixDark(
            primary = accent,
            onPrimary = Color.White,
            primaryVariant = accent,
            onPrimaryVariant = accent.copy(alpha = 0.55f).compositeOver(Color.Black),
            primaryContainer = accent,
            onPrimaryContainer = Color.White,
            tertiaryContainer = accent.copy(alpha = 0.18f).compositeOver(Color(0xFF1C1C1E)),
            onTertiaryContainer = accent,
            tertiaryContainerVariant = accent.copy(alpha = 0.18f).compositeOver(Color(0xFF1C1C1E)),
            secondary = Color(0xFF2A2A2D),
            secondaryVariant = Color(0xFF232326),
            secondaryContainer = Color(0xFF1E1E21),
            onSecondaryContainer = Color(0xFF8A8A8F),
            secondaryContainerVariant = Color(0xFF232326),
            onSecondaryContainerVariant = Color(0xFF9A9AA0),
            background = Color(0xFF000000),
            onBackground = Color(0xFFE9E9EC),
            onBackgroundVariant = Color(0xFF8E8E93),
            surface = Color(0xFF1C1C1E),
            onSurface = Color(0xFFE9E9EC),
            surfaceVariant = Color(0xFF1C1C1E),
            onSurfaceSecondary = Color(0xCCE9E9EC),
            onSurfaceVariantSummary = Color(0x99E9E9EC),
            onSurfaceVariantActions = Color(0x66E9E9EC),
            surfaceContainer = Color(0xFF1C1C1E),
            onSurfaceContainer = Color(0xFFE9E9EC),
            onSurfaceContainerVariant = Color(0xFF8E8E93),
            surfaceContainerHigh = Color(0xFF232326),
            onSurfaceContainerHigh = Color(0xFFB5B5BA),
            surfaceContainerHighest = Color(0xFF2A2A2D),
            onSurfaceContainerHighest = Color(0xFFE9E9EC),
            outline = Color(0xFF2C2C2E),
            dividerLine = Color(0xFF26262A),
            error = Color(0xFFFF6B6B)
        )
    } else {
        miuixLight(
            primary = accent,
            onPrimary = Color.White,
            primaryVariant = accent,
            onPrimaryVariant = accent.copy(alpha = 0.55f).compositeOver(Color.White),
            primaryContainer = accent,
            onPrimaryContainer = Color.White,
            tertiaryContainer = accent.copy(alpha = 0.10f).compositeOver(Color.White),
            onTertiaryContainer = accent,
            tertiaryContainerVariant = accent.copy(alpha = 0.08f).compositeOver(Color.White),
            secondary = Color(0xFFF0F1F3),
            secondaryVariant = Color(0xFFF5F6F8),
            secondaryContainer = Color(0xFFF2F3F5),
            onSecondaryContainer = Color(0xFF8A8E98),
            secondaryContainerVariant = Color(0xFFF5F6F8),
            onSecondaryContainerVariant = Color(0xFF8A8E98),
            background = Color(0xFFF2F3F5),
            onBackground = Color(0xFF111114),
            onBackgroundVariant = Color(0xFF7D818B),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF111114),
            surfaceVariant = Color(0xFFFFFFFF),
            onSurfaceSecondary = Color(0xCC111114),
            onSurfaceVariantSummary = Color(0x99111114),
            onSurfaceVariantActions = Color(0x66111114),
            surfaceContainer = Color(0xFFFFFFFF),
            onSurfaceContainer = Color(0xFF111114),
            onSurfaceContainerVariant = Color(0xFF7D818B),
            surfaceContainerHigh = Color(0xFFEBECEF),
            onSurfaceContainerHigh = Color(0xFF6F737C),
            surfaceContainerHighest = Color(0xFFEBECEF),
            onSurfaceContainerHighest = Color(0xFF111114),
            outline = Color(0xFFE2E4EA),
            dividerLine = Color(0xFFECEDF0),
            error = Color(0xFFE94634)
        )
    }

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
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp)
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
    val miuix = remember(accent, dark) { miuixColors(accent, dark) }
    val scheme = remember(accent, dark) { if (dark) darkScheme(accent) else lightScheme(accent) }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density,
            fontScale = settings.fontScale
        )
    ) {
        // 两层主题：MIUIX 负责新界面，Material3 只留给尚未改写的零散控件，配色保持一致
        MiuixTheme(colors = miuix) {
            MaterialTheme(
                colorScheme = scheme,
                shapes = AppShapes,
                typography = appTypography(),
                content = content
            )
        }
    }
}
