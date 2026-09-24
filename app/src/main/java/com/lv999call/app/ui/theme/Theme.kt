package com.lv999call.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 自定义扩展颜色 — 用于通话状态指示、消息气泡与通话页板块 */
@Immutable
data class ExtendedColors(
    val callEnd: Color,
    val listening: Color,
    val speaking: Color,
    val thinking: Color,
    val userBubble: Color,
    val aiBubble: Color,

    /** 气泡 / 板块的弱边界描边（半透明，只做视觉分界不抢视线） */
    val bubbleBorder: Color = BubbleBorder,

    /**
     * 通话页「舞台」板块（装 Live2D 模型）的描边与底色。
     *
     * 要求是**弱边界**：能让人看出这里有个容器，但不要像卡片那样框死。
     * 所以描边透明度很低（约 10%），底色只做极轻的区分。
     */
    val stageBorder: Color = Color(0x1FFFFFFF),
    val stageSurface: Color = Color(0x14FFFFFF),

    /** 通话页「对话」板块的描边与底色（比舞台略实一点，保证文字可读） */
    val panelBorder: Color = Color(0x24FFFFFF),
    val panelSurface: Color = Color(0x1A000000)
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        callEnd = CallEndRed,
        listening = ListeningBlue,
        speaking = SpeakingGreen,
        thinking = ThinkingYellow,
        userBubble = UserBubble,
        aiBubble = AiBubble
    )
}

private val DarkColorScheme = darkColorScheme(
    primary = md_primary,
    onPrimary = md_onPrimary,
    primaryContainer = md_primaryContainer,
    onPrimaryContainer = md_onPrimaryContainer,
    secondary = md_secondary,
    onSecondary = md_onSecondary,
    secondaryContainer = md_secondaryContainer,
    onSecondaryContainer = md_onSecondaryContainer,
    tertiary = md_tertiary,
    onTertiary = md_onTertiary,
    tertiaryContainer = md_tertiaryContainer,
    onTertiaryContainer = md_onTertiaryContainer,
    error = md_error,
    onError = md_onError,
    errorContainer = md_errorContainer,
    onErrorContainer = md_onErrorContainer,
    background = md_background,
    onBackground = md_onBackground,
    surface = md_surface,
    onSurface = md_onSurface,
    surfaceVariant = md_surfaceVariant,
    onSurfaceVariant = md_onSurfaceVariant,
    outline = md_outline,
    outlineVariant = md_outlineVariant,
    inverseSurface = md_inverseSurface,
    inverseOnSurface = md_inverseOnSurface,
    inversePrimary = md_inversePrimary,
    surfaceBright = md_surfaceBright,
    surfaceDim = md_surfaceDim,
    surfaceContainerLowest = md_surfaceContainerLowest,
    surfaceContainerLow = md_surfaceContainerLow,
    surfaceContainer = md_surfaceContainer,
    surfaceContainerHigh = md_surfaceContainerHigh,
    surfaceContainerHighest = md_surfaceContainerHighest,
    scrim = md_scrim
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF006B5E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF74F8E2),
    onTertiaryContainer = Color(0xFF00201B),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = Color(0xFFD0BCFF)
)

private val extendedDark = ExtendedColors(
    callEnd = CallEndRed,
    listening = ListeningBlue,
    speaking = SpeakingGreen,
    thinking = ThinkingYellow,
    userBubble = UserBubble,
    aiBubble = AiBubble
)

private val extendedLight = ExtendedColors(
    callEnd = Color(0xFFD50000),
    listening = Color(0xFF2962FF),
    speaking = Color(0xFF00C853),
    thinking = Color(0xFFFFAB00),
    // 浅色主题下的半透明灰：用黑色做底，叠在白背景上自然变灰
    userBubble = Color(0x1F000000),
    aiBubble = Color(0x14000000),
    bubbleBorder = Color(0x1A000000),
    stageBorder = Color(0x14000000),
    stageSurface = Color(0x0A000000),
    panelBorder = Color(0x1F000000),
    panelSurface = Color(0x0D000000)
)

@Composable
fun UltraFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Android 12+ Dynamic Color (Material You)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val extended = if (darkTheme) extendedDark else extendedLight

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // M3推荐：使用 enableEdgeToEdge() 替代直接设置状态栏颜色
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalExtendedColors provides extended
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/** 快捷访问扩展颜色 */
object UltraFlowTheme {
    val extendedColors: ExtendedColors
        @Composable
        get() = LocalExtendedColors.current
}
