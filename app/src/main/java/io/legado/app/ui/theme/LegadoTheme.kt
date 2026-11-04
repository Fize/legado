package io.legado.app.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.WallpaperTheme

private val DefaultLightColorScheme = lightColorScheme(
    primary = Color(ThemeConfig.DEFAULT_LIGHT_ACCENT),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(ThemeConfig.DEFAULT_LIGHT_ACCENT),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E3FD),
    onSecondaryContainer = Color(0xFF001945),
    background = Color(ThemeConfig.DEFAULT_LIGHT_BACKGROUND),
    onBackground = Color(0xFF202124),
    surface = Color(ThemeConfig.DEFAULT_LIGHT_BACKGROUND),
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF535A66),
    surfaceContainer = Color(ThemeConfig.DEFAULT_LIGHT_SURFACE_CONTAINER),
    surfaceContainerHigh = Color(0xFFEBEEF2),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
)

private val DefaultDarkColorScheme = darkColorScheme(
    primary = Color(ThemeConfig.DEFAULT_DARK_ACCENT),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF254477),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(ThemeConfig.DEFAULT_DARK_ACCENT),
    onSecondary = Color(0xFF062E6F),
    secondaryContainer = Color(0xFF254477),
    onSecondaryContainer = Color(0xFFD9E2FF),
    background = Color(ThemeConfig.DEFAULT_DARK_BACKGROUND),
    onBackground = Color(0xFFE8EAED),
    surface = Color(ThemeConfig.DEFAULT_DARK_BACKGROUND),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFBDC1C6),
    surfaceContainer = Color(ThemeConfig.DEFAULT_DARK_SURFACE_CONTAINER),
    surfaceContainerHigh = Color(0xFF25282D),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
)

private val EInkColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color.White,
    onSecondaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color.White,
    onSurfaceVariant = Color.Black,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    outline = Color.Black,
    outlineVariant = Color.Black,
)

@Composable
fun LegadoTheme(
    darkTheme: Boolean = AppConfig.isNightTheme,
    eInkMode: Boolean = AppConfig.isEInkMode,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        eInkMode -> EInkColorScheme
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && WallpaperTheme.isFollowEnabled(context) -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> {
            val accent = Color(ThemeStore.accentColor(context))
            val bg = Color(ThemeStore.backgroundColor(context))
            val container = Color(ThemeStore.bottomBackground(context))
            DefaultDarkColorScheme.copy(
                primary = accent,
                secondary = accent,
                background = bg,
                surface = bg,
                surfaceContainer = container,
            )
        }
        else -> {
            val accent = Color(ThemeStore.accentColor(context))
            val bg = Color(ThemeStore.backgroundColor(context))
            val container = Color(ThemeStore.bottomBackground(context))
            DefaultLightColorScheme.copy(
                primary = accent,
                secondary = accent,
                background = bg,
                surface = bg,
                surfaceContainer = container,
            )
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

