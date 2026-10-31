package com.shortdrama.assist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * 主题入口。
 *
 * @param darkOverride `null` = 跟随系统（默认）；`true`/`false` = 用户手动覆盖。
 */
@Composable
fun ShortDramaTheme(
    darkOverride: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = darkOverride ?: isSystemInDarkTheme()
    val t = if (dark) DarkTokens else LightTokens

    val scheme = if (dark) {
        darkColorScheme(
            primary = t.accent,
            onPrimary = Color(0xFF1A1200),
            background = t.background,
            onBackground = t.text,
            surface = t.surface,
            onSurface = t.text,
            surfaceVariant = t.surface,
            onSurfaceVariant = t.textSecondary,
            outline = t.hairline,
            error = t.danger,
        )
    } else {
        lightColorScheme(
            primary = t.accentInk,
            onPrimary = Color.White,
            background = t.background,
            onBackground = t.text,
            surface = t.surface,
            onSurface = t.text,
            surfaceVariant = t.surface,
            onSurfaceVariant = t.textSecondary,
            outline = t.hairline,
            error = t.danger,
        )
    }

    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
