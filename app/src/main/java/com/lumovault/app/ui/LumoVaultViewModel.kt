package com.lumovault.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * `onboardingResolved` starts false because the answer lives in Room: until the first row arrives
 * the app shows a neutral surface rather than guessing, which would flash onboarding at returning
 * users on every cold start.
 */
data class LumoVaultUiState(
    val themeMode: ThemeMode = ThemeMode.Default,
    val onboardingResolved: Boolean = false,
    val onboardingCompleted: Boolean = false,
)

class LumoVaultViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LumoVaultApplication).container

    /**
     * Theme and onboarding completion come from the same settings row, so the first emission of
     * this pair is also the moment the app stops guessing.
     */
    val uiState: StateFlow<LumoVaultUiState> =
        combine(container.settingsRepository.themeMode, container.onboardingRepository.progress) { theme, progress ->
            LumoVaultUiState(
                themeMode = theme,
                onboardingResolved = true,
                onboardingCompleted = progress.onboardingCompleted,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LumoVaultUiState(),
        )

    /** Theme lives above the flow so the onboarding screens honour it too. */
    fun cycleThemeMode() {
        viewModelScope.launch {
            container.settingsRepository.setThemeMode(uiState.value.themeMode.next())
        }
    }
}

private fun ThemeMode.next(): ThemeMode = when (this) {
    ThemeMode.Dark -> ThemeMode.System
    ThemeMode.System -> ThemeMode.Light
    ThemeMode.Light -> ThemeMode.Dark
}
