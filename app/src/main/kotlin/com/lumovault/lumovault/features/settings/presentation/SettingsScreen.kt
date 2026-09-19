package com.lumovault.lumovault.features.settings.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import com.lumovault.lumovault.features.settings.domain.model.ThemeMode

/**
 * Main settings hub.
 *
 * Ported from settings_screen.dart: sectioned navigation rows to the
 * sub-routes, plus the two most-used backup toggles inline. The original's
 * media-collection shortcuts (Hidden / Favorites / Archive / Albums / Trash /
 * Duplicates) are tab destinations in this rewrite, not settings sub-screens,
 * so they are not repeated here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigate: (String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) })
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item { SettingsSectionHeader(R.string.settings_section_account) }
            item {
                SettingsNavItem(
                    title = R.string.settings_account_row_title,
                    subtitle = R.string.settings_account_row_subtitle,
                    icon = Icons.Default.Person,
                    onClick = { onNavigate(SettingsRoutes.ACCOUNT) },
                )
            }
            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.settings_section_backup_storage) }
            item {
                SettingsSwitchItem(
                    title = R.string.settings_auto_backup,
                    subtitle = R.string.settings_auto_backup_subtitle,
                    icon = Icons.Default.Backup,
                    checked = settings.autoBackupEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(autoBackupEnabled = v) } },
                )
            }
            item {
                SettingsSwitchItem(
                    title = R.string.settings_wifi_only,
                    subtitle = R.string.settings_wifi_only_subtitle,
                    icon = Icons.Default.Wifi,
                    checked = settings.wifiOnly,
                    onCheckedChange = { v -> viewModel.update { it.copy(wifiOnly = v) } },
                )
            }
            item {
                SettingsNavItem(
                    title = R.string.settings_backup_settings,
                    subtitle = R.string.settings_backup_settings_subtitle,
                    icon = Icons.Default.Settings,
                    onClick = { onNavigate(SettingsRoutes.BACKUP_SETTINGS) },
                )
            }
            item {
                SettingsNavItem(
                    title = R.string.settings_storage_usage,
                    subtitle = R.string.settings_storage_usage_subtitle,
                    icon = Icons.Default.Storage,
                    onClick = { onNavigate(SettingsRoutes.STORAGE) },
                )
            }
            item {
                SettingsNavItem(
                    title = R.string.settings_storage_insights,
                    subtitle = R.string.settings_storage_insights_subtitle,
                    icon = Icons.Default.Insights,
                    onClick = { onNavigate(SettingsRoutes.STORAGE_INSIGHTS) },
                )
            }

            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.settings_section_vault_privacy) }
            item {
                SettingsNavItem(
                    title = R.string.settings_privacy_row_title,
                    subtitleText = privacyStatus(settings),
                    icon = Icons.Default.Lock,
                    onClick = { onNavigate(SettingsRoutes.PRIVACY) },
                )
            }
            item {
                SettingsNavItem(
                    title = R.string.settings_media_folders,
                    subtitle = R.string.settings_media_folders_subtitle,
                    icon = Icons.Default.Folder,
                    onClick = { onNavigate(SettingsRoutes.MEDIA) },
                )
            }

            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.settings_section_preferences) }
            item {
                SettingsNavItem(
                    title = R.string.settings_appearance,
                    subtitleText = themeModeName(settings.themeMode),
                    icon = Icons.Default.Palette,
                    onClick = { onNavigate(SettingsRoutes.APPEARANCE) },
                )
            }
            item {
                SettingsNavItem(
                    title = R.string.settings_language,
                    subtitleText = languageName(settings.languageCode),
                    icon = Icons.Default.Language,
                    onClick = { onNavigate(SettingsRoutes.GENERAL) },
                )
            }
            item {
                SettingsNavItem(
                    title = R.string.settings_notifications,
                    icon = Icons.Default.Notifications,
                    onClick = { onNavigate(SettingsRoutes.NOTIFICATIONS) },
                )
            }

            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.settings_section_about_system) }
            item {
                SettingsNavItem(
                    title = R.string.settings_about,
                    icon = Icons.Default.Info,
                    onClick = { onNavigate(SettingsRoutes.ABOUT) },
                )
            }
            item {
                SettingsNavItem(
                    title = R.string.settings_developer,
                    subtitle = R.string.settings_developer_subtitle,
                    icon = Icons.Default.Code,
                    onClick = { onNavigate(SettingsRoutes.DEVELOPER) },
                )
            }
        }
    }
}

@Composable
private fun privacyStatus(s: AppSettings): String = when {
    s.biometricLockEnabled -> stringResource(R.string.settings_privacy_biometric)
    s.pinLockEnabled -> stringResource(R.string.settings_privacy_pin)
    else -> stringResource(R.string.settings_privacy_none)
}

@Composable
internal fun themeModeName(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.system -> R.string.theme_system
        ThemeMode.light -> R.string.theme_light
        ThemeMode.dark -> R.string.theme_dark
    },
)

@Composable
internal fun languageName(code: String): String = when (code) {
    "en" -> stringResource(R.string.language_english)
    "es" -> stringResource(R.string.language_spanish)
    "fr" -> stringResource(R.string.language_french)
    else -> code
}
