package com.lumovault.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.domain.model.ThemeMode
import com.lumovault.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LumoVaultUiState(val themeMode: ThemeMode = ThemeMode.Default)

class LumoVaultViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository: SettingsRepository =
        (application as LumoVaultApplication).container.settingsRepository

    val uiState: StateFlow<LumoVaultUiState> = settingsRepository.themeMode
        .map { LumoVaultUiState(themeMode = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LumoVaultUiState(),
        )

    fun cycleThemeMode() {
        viewModelScope.launch {
            settingsRepository.setThemeMode(uiState.value.themeMode.next())
        }
    }
}

private fun ThemeMode.next(): ThemeMode = when (this) {
    ThemeMode.Dark -> ThemeMode.System
    ThemeMode.System -> ThemeMode.Light
    ThemeMode.Light -> ThemeMode.Dark
}
