package com.lumovault.lumovault.core.theme

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes the persisted [AppSettings] to the theme and (later) to any screen
 * that reads appearance or privacy flags.
 *
 * This is the `appSettingsProvider` equivalent: one source of truth, already
 * loaded and following the repository's change flow, so a settings write from
 * a background worker reaches every collector.
 */
@HiltViewModel
class AppThemeViewModel @Inject constructor(
    val settings: StateFlow<AppSettings>,
) : ViewModel()
