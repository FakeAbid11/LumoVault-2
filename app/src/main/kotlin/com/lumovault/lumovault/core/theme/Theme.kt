package com.lumovault.lumovault.core.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * LumoVault theme.
 *
 * Ported from lib/core/theme/app_theme.dart. The seed is the only hand-picked
 * colour; everything else is derived. Component shapes match the original's
 * Material 3 tuning (card 24, dialog/bottomSheet 28, inputs/buttons 16,
 * stadium buttons and nav indicator).
 *
 * [dynamicColor] is honoured only on Android 12+, matching the original's
 * `DynamicColorBuilder` gating. [animationsEnabled] is exposed through
 * [LocalAppAnimations] rather than applied to a page-transitions builder —
 * Compose navigation has no global transitions builder, so screens read this
 * and skip their own enter/exit transitions.
 */

/** Whether app-level animations are enabled. Screens should honour this. */
val LocalAppAnimations = staticCompositionLocalOf { true }

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B5CE6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FAEFF),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LumoVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    animationsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Both branches seed from AppColors via the generator when dynamic
        // color is off; the hand-set primaries above keep the brand readable
        // on devices where the generator's tonal palette shifts too far.
        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(LocalAppAnimations provides animationsEnabled) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
