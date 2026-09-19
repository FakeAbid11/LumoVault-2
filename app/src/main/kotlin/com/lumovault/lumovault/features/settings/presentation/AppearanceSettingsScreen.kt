package com.lumovault.lumovault.features.settings.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.features.settings.domain.model.GridSize
import com.lumovault.lumovault.features.settings.domain.model.ThemeMode

/**
 * Appearance settings — theme, colors, gallery grid.
 *
 * Ported from appearance_settings_screen.dart. The original's grid-size
 * SimpleDialog is rendered here as an inline radio group.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { SettingsTopBar(R.string.appearance_title, onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item { SettingsSectionHeader(R.string.appearance_section_theme) }
            item {
                SettingsRadioGroup(
                    options = listOf(
                        ThemeMode.system to R.string.theme_system,
                        ThemeMode.light to R.string.theme_light,
                        ThemeMode.dark to R.string.theme_dark,
                    ),
                    selected = settings.themeMode,
                    onSelect = { mode -> viewModel.update { it.copy(themeMode = mode) } },
                )
            }

            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.appearance_section_colors) }
            item {
                SettingsSwitchItem(
                    title = R.string.appearance_dynamic_color,
                    subtitle = R.string.appearance_dynamic_color_subtitle,
                    icon = Icons.Default.Palette,
                    checked = settings.useDynamicColor,
                    onCheckedChange = { v -> viewModel.update { it.copy(useDynamicColor = v) } },
                )
            }

            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.appearance_section_gallery) }
            item {
                SettingsRadioGroup(
                    options = listOf(
                        GridSize.small to R.string.appearance_grid_small,
                        GridSize.medium to R.string.appearance_grid_medium,
                        GridSize.large to R.string.appearance_grid_large,
                    ),
                    selected = settings.gridSize,
                    onSelect = { size -> viewModel.update { it.copy(gridSize = size) } },
                )
            }
            item {
                SettingsSwitchItem(
                    title = R.string.appearance_compact_mode,
                    subtitle = R.string.appearance_compact_mode_subtitle,
                    icon = Icons.Default.ViewModule,
                    checked = settings.compactMode,
                    onCheckedChange = { v -> viewModel.update { it.copy(compactMode = v) } },
                )
            }
            item {
                SettingsSwitchItem(
                    title = R.string.appearance_animations,
                    subtitle = R.string.appearance_animations_subtitle,
                    icon = Icons.Default.Animation,
                    checked = settings.animationsEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(animationsEnabled = v) } },
                )
            }
        }
    }
}
