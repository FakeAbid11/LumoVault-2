package com.lumovault.lumovault.features.restore.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.auth.AuthService
import com.lumovault.lumovault.core.auth.AuthState
import com.lumovault.lumovault.features.restore.data.service.RestoreController
import com.lumovault.lumovault.features.restore.data.service.RestoreProgressState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Restore screens' state: Telegram sign-in gates the Scan button, the
 * controller's progress flow drives the progress screen.
 */
@HiltViewModel
class RestoreViewModel @Inject constructor(
    authService: AuthService,
    private val controller: RestoreController,
) : ViewModel() {

    /** Restore runs need a signed-in Telegram session. */
    val canRestore: StateFlow<Boolean> = authService.state
        .map { it == AuthState.authenticated }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = authService.currentState == AuthState.authenticated,
        )

    val progress: StateFlow<RestoreProgressState> = controller.state

    fun startRestore() = controller.start()

    fun cancelRestore() = controller.cancel()
}