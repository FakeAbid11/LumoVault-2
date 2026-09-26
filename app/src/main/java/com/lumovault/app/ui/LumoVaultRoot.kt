package com.lumovault.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.ui.onboarding.OnboardingFlow
import com.lumovault.app.ui.theme.LumoVaultTheme

/**
 * The launch decision from PRD section 80: unresolved state → wait, incomplete onboarding → the
 * six screens, complete → the main app on Photos.
 *
 * Onboarding completion is persisted but the Telegram session is not part of that flag, so a
 * session that lapses later asks for a reconnect in place rather than reopening the whole flow.
 */
@Composable
fun LumoVaultRoot(viewModel: LumoVaultViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LumoVaultTheme(mode = uiState.themeMode) {
        when {
            !uiState.onboardingResolved -> StartupSurface()
            !uiState.onboardingCompleted -> OnboardingFlow(viewModel = viewModel())
            else -> LumoVaultApp(onCycleThemeMode = viewModel::cycleThemeMode)
        }
    }
}

/**
 * Shown for the frames it takes Room to answer. Guessing "not onboarded" here would flash the
 * onboarding flow at every existing user on every cold start.
 */
@Composable
private fun StartupSurface() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}
