package com.lumovault.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.lumovault.app.domain.model.ThemeMode

private val LumoVaultDarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = ErrorRed,
    onError = OnErrorRed,
)

private val LumoVaultLightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = ErrorRed,
    onError = OnErrorRed,
)

/**
 * Single entry point for colors and typography: screens read the scheme, never a literal color.
 *
 * Dynamic color is deliberately not used — LumoVault's identity is a fixed blue accent
 * (PRD section 44), and wallpaper-derived palettes would wash it out.
 */
@Composable
fun LumoVaultTheme(
    mode: ThemeMode = ThemeMode.Default,
    content: @Composable () -> Unit,
) {
    val useDark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    MaterialTheme(
        colorScheme = if (useDark) LumoVaultDarkColors else LumoVaultLightColors,
        typography = LumoVaultTypography,
        content = content,
    )
}
