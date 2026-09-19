package com.lumovault.lumovault.features.settings.presentation

import androidx.lifecycle.ViewModel
import com.lumovault.lumovault.features.settings.data.SettingsRepository
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Shared view model for every settings screen.
 *
 * Ported from the Riverpod `appSettingsProvider` / `SettingsNotifier` pair:
 * one source of truth (already loaded, following the repository's change flow)
 * and a single [update] entry point, mirroring the original's `updateField`.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    val settings: StateFlow<AppSettings>,
    private val repository: SettingsRepository,
) : ViewModel() {

    fun update(transform: (AppSettings) -> AppSettings) {
        repository.update(transform)
    }
}
