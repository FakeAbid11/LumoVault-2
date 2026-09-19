package com.lumovault.lumovault.features.settings.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R

/**
 * General settings — language.
 *
 * Ported from general_settings_screen.dart, minus the reset actions: the
 * onboarding flow is not wired in this rewrite yet, and wiping every setting
 * from a settings screen is a footgun the rewrite intentionally leaves out.
 * The language row is display-only because no translations are wired up —
 * saying so is more honest than presenting a dead control as working (the
 * original made the same call in its subtitle).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { SettingsTopBar(R.string.general_title, onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item { SettingsSectionHeader(R.string.general_section_language) }
            item {
                SettingsNavItem(
                    title = R.string.general_app_language,
                    subtitleText = stringResource(
                        R.string.general_language_coming_soon,
                        languageName(settings.languageCode),
                    ),
                    icon = Icons.Default.Language,
                    onClick = {},
                )
            }
        }
    }
}
