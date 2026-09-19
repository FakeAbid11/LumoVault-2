package com.lumovault.lumovault.features.onboarding.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.auth.AuthResult
import com.lumovault.lumovault.core.auth.AuthService
import com.lumovault.lumovault.core.auth.AuthState
import com.lumovault.lumovault.core.permissions.PermissionService
import com.lumovault.lumovault.features.gallery.data.service.DeviceFolder
import com.lumovault.lumovault.features.gallery.data.service.MediaScannerService
import com.lumovault.lumovault.features.settings.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Shared state holder for the five onboarding screens.
 *
 * Ported from lib/features/onboarding/presentation/providers/onboarding_provider.dart
 * plus the auth glue that lived inside telegram_connect_screen.dart. Two
 * properties of the originals are load-bearing here:
 *
 *  - **The finish write is atomic.** `onboardingCompleted` and
 *    `includedFolders` are persisted in a single [SettingsRepository.update]
 *    call — the Flutter app once wrote them in two separate unawaited persists
 *    that raced, and the second could overwrite `onboardingCompleted` back to
 *    false.
 *  - **Auth results, not auth states, drive the flow.** [verifyCode] /
 *    [submitPassword] in the repository subscribe to TDLib's state stream
 *    *before* sending (an `Ok` reply alone does not say what comes next), and
 *    a timeout re-reads the state instead of assuming success. This view
 *    model trusts the returned [AuthResult] only — so a 2FA account lands on
 *    the password step, never on "connected" with TDLib parked in
 *    WaitPassword.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val settingsRepository: SettingsRepository,
    private val authService: AuthService,
    private val mediaScannerService: MediaScannerService,
) : ViewModel() {

    val permissionService = PermissionService(context)

    /** Standing Telegram auth state; the Telegram screen also renders this. */
    val authState: StateFlow<AuthState> = authService.state

    /** Result of the folder-list load, for the folder screen's error/retry UI. */
    data class FolderListState(
        val folders: List<DeviceFolder> = emptyList(),
        val isLoading: Boolean = false,
        val failed: Boolean = false,
    )

    private val _folderList = MutableStateFlow(FolderListState(isLoading = true))
    val folderList: StateFlow<FolderListState> = _folderList.asStateFlow()

    /** Selected folder keys (MediaStore bucket ids) chosen on the folder screen. */
    private val _selectedFolders = MutableStateFlow<Set<String>>(emptySet())
    val selectedFolders: StateFlow<Set<String>> = _selectedFolders.asStateFlow()

    init {
        loadFolders()
    }

    fun loadFolders() = viewModelScope.launch {
        _folderList.value = FolderListState(isLoading = true)
        val folders = try {
            mediaScannerService.listFolders()
        } catch (e: Throwable) {
            _folderList.value = FolderListState(failed = true)
            return@launch
        }
        _folderList.value = FolderListState(folders = folders)
    }

    fun toggleFolder(bucketId: String) {
        val current = _selectedFolders.value
        _selectedFolders.value =
            if (bucketId in current) current - bucketId else current + bucketId
    }

    // -- Telegram connect --

    /** Bring up the TDLib client. Returns false when it cannot be reached. */
    suspend fun initializeAuth(): Boolean = try {
        authService.initialize()
        true
    } catch (e: Throwable) {
        false
    }

    /** True when a previous session is already live — skip the code round trip. */
    fun isAlreadyAuthenticated(): Boolean = authService.currentState == AuthState.authenticated

    suspend fun sendCode(phoneNumber: String): AuthResult = authService.sendCode(phoneNumber)

    suspend fun verifyCode(code: String): AuthResult = authService.verifyCode(code)

    suspend fun submitPassword(password: String): AuthResult = authService.submitPassword(password)

    /**
     * The single write that ends onboarding: marks it complete and persists
     * the chosen folders together (see the class doc for why one write).
     */
    fun completeOnboarding() {
        settingsRepository.update {
            it.copy(
                onboardingCompleted = true,
                includedFolders = _selectedFolders.value.toList(),
            )
        }
    }
}
