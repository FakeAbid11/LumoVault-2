package com.lumovault.lumovault.features.settings.presentation

import androidx.lifecycle.ViewModel
import com.lumovault.lumovault.core.auth.AuthService
import com.lumovault.lumovault.core.auth.AuthState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Reads the standing Telegram auth state for the account screen.
 *
 * The original's `accountInfoProvider` fetched the profile (name, phone) via
 * TDLib `getMe`; that query path is not wired in this rewrite, so only the
 * auth state is shown — "Connected" once TDLib reports Ready, otherwise a
 * static not-signed-in state. Profile details land with the account batch.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    authService: AuthService,
) : ViewModel() {
    val authState: StateFlow<AuthState> = authService.state
}
