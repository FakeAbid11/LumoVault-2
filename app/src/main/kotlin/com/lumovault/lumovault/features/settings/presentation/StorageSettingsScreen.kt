package com.lumovault.lumovault.features.settings.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R

/**
 * Storage settings — trash retention.
 *
 * Ported from storage_settings_screen.dart, minus the usage tiles and cache /
 * database maintenance actions: those pull live engine data (storage usage
 * provider, thumbnail cache, Drift PRAGMAs) that has no counterpart in this
 * rewrite yet. The persisted retention setting is real and shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { SettingsTopBar(R.string.storage_title, onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item { SettingsSectionHeader(R.string.storage_section_trash) }
            item {
                SettingsRadioGroup(
                    options = listOf(
                        7 to R.string.storage_trash_days_7,
                        14 to R.string.storage_trash_days_14,
                        30 to R.string.storage_trash_days_30,
                        60 to R.string.storage_trash_days_60,
                        0 to R.string.storage_trash_forever,
                    ),
                    selected = settings.trashDurationDays,
                    onSelect = { days ->
                        viewModel.update { it.copy(trashDurationDays = days) }
                    },
                )
            }
        }
    }
}
