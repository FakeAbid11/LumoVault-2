package com.lumovault.lumovault.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.lumovault.lumovault.core.theme.LumoVaultTheme
import com.lumovault.lumovault.features.settings.domain.model.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.hilt.navigation.compose.hiltViewModel
import com.lumovault.lumovault.MediaPermissionGate
import com.lumovault.lumovault.core.theme.AppThemeViewModel

/**
 * Root composable: reads persisted appearance settings, applies the theme, then
 * gates on media permission before running the nav graph.
 *
 * Ported from app.dart, which wrapped the router in `DynamicColorBuilder` and
 * read `settingsThemeModeProvider` / `settingsDynamicColorProvider` /
 * `settingsAnimationsProvider`. Gathering all three here (rather than in each
 * screen) is what made a settings change retheme the whole app without a
 * rebuild.
 *
 * The permission gate sits *inside* the theme so the rationale screen is
 * themed, and *outside* the nav graph so no destination has to defensively
 * handle an unreadable MediaStore.
 */
@Composable
fun AppRoot() {
    val themeViewModel: AppThemeViewModel = hiltViewModel()
    val settings by themeViewModel.settings.collectAsState()

    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (settings.themeMode) {
        ThemeMode.system -> systemDark
        ThemeMode.light -> false
        ThemeMode.dark -> true
    }

    LumoVaultTheme(
        darkTheme = darkTheme,
        dynamicColor = settings.useDynamicColor,
        animationsEnabled = settings.animationsEnabled,
    ) {
        // App lock wraps everything below it: when a PIN or biometric lock is
        // enabled with requireAuthOnAppOpen, the nav graph is not reachable
        // until the gate unlocks. The gate itself is a no-op when no lock is
        // configured, so this costs nothing on an unlocked install.
        com.lumovault.lumovault.features.applock.AppLockGate {
            MediaPermissionGate {
                LumoVaultNavGraph()
            }
        }
    }
}
