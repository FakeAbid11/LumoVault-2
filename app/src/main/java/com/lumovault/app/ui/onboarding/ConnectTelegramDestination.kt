package com.lumovault.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.domain.telegram.TelegramAuthState

/**
 * The sign-in panels, reachable again after onboarding.
 *
 * [com.lumovault.app.ui.LumoVaultRoot]'s comment promises that a session which lapses later "asks for
 * a reconnect in place" rather than reopening the whole six-screen flow — and until now that promise
 * had no route: the only sign-in UI lived inside the onboarding graph, and the Cloud tab's
 * needs-sign-in state was a dead sentence telling the user to go somewhere the app could not take
 * them. This screen hosts the same [ConnectTelegramScreen] over the same [OnboardingViewModel]: the
 * VM's init already reconnects TDLib and the auth state machine owns every transition from here.
 *
 * What differs from setup is only the exits. There is no step counter to walk back through and no
 * "continue without Telegram" to a permissions screen: signing in here ends by leaving, and the
 * Cloud tab's own resume-sync picks up from the session the moment it exists.
 */
@Composable
fun ConnectTelegramDestination(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The same courtesy the onboarding flow pays: back leaves the credential prompt, not the number
    // the user typed.
    BackHandler(enabled = state.panel != TelegramPanel.Phone) { viewModel.editPhoneNumber() }

    ConnectTelegramScreen(
        state = state,
        onSubmitPhoneNumber = viewModel::requestCode,
        onSubmitCode = viewModel::submitCode,
        onResendCode = viewModel::resendCode,
        onChangeNumber = viewModel::editPhoneNumber,
        onSubmitPassword = viewModel::submitPassword,
        onCountrySelected = viewModel::selectCountry,
        onPhoneChange = viewModel::onPhoneChange,
        onBack = onNavigateUp,
        // A build that cannot reach Telegram has nothing to reconnect; leaving is the whole answer. This
        // route never offers the skip control itself — see `telegramSkipOffered`.
        onSkipTelegram = onNavigateUp,
        standalone = true,
        modifier = modifier,
    )

    LaunchedEffect(state.telegram) {
        // The session arriving is the only thing that closes this screen; the Cloud tab re-reads
        // everything else on resume, which is its own existing promise about live state.
        if (state.telegram is TelegramAuthState.Authenticated) onNavigateUp()
    }
}
