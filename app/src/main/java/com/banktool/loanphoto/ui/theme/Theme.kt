package com.banktool.loanphoto.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Fluent Design 浅色主题
 * 强制浅色主题（用户偏好：不喜欢暗色主题）
 * 不含 emoji
 */
private val FluentLightColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Card,
    primaryContainer = HighlightBg,
    onPrimaryContainer = AccentDark,
    secondary = AccentDark,
    onSecondary = Card,
    secondaryContainer = HighlightBg,
    onSecondaryContainer = AccentDark,
    tertiary = Accent,
    onTertiary = Card,
    background = Bg,
    onBackground = Text,
    surface = Card,
    onSurface = Text,
    surfaceVariant = Bg,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Accent,
    inverseSurface = Text,
    inverseOnSurface = Bg,
    error = Error,
    onError = Card,
    errorContainer = Error,
    onErrorContainer = Card,
    outline = Divider,
    outlineVariant = Divider,
    scrim = Text
)

/**
 * App 主题
 * 强制浅色主题（darkTheme = false）
 */
@Composable
fun LoanPhotoAppTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FluentLightColorScheme,
        typography = Typography,
        content = content
    )
}
