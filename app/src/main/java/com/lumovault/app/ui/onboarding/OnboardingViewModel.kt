package com.lumovault.app.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.model.Country
import com.lumovault.app.domain.model.OptionalStepDecision
import com.lumovault.app.domain.telegram.TelegramAuthState
import com.lumovault.app.domain.telegram.isAuthenticated
import com.lumovault.app.util.PhoneNumbers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One holder for the whole six-screen flow, so a rotation or a process restart does not lose the
 * chosen country, the typed number, or where authentication had reached.
 *
 * The entered number lives only in memory here — never on disk, never in a log. Codes and passwords
 * go from the field straight to the repository, and the calling composable clears its own text.
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LumoVaultApplication).container

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        container.telegramAuthRepository.connect()

        viewModelScope.launch {
            combine(
                container.telegramAuthRepository.state,
                container.onboardingRepository.progress,
            ) { telegram, progress -> telegram to progress }
                .collect { (telegram, progress) ->
                    _uiState.update { state ->
                        state.copy(
                            telegram = telegram,
                            progress = progress,
                            // A failure keeps the panel the user was on; any other state moves it.
                            telegramPanel = if (telegram is TelegramAuthState.Failed) {
                                state.telegramPanel
                            } else {
                                TelegramPanel.of(telegram)
                            },
                        )
                    }

                    // "An account was linked" is a setup fact; whether it still works comes from TDLib.
                    // Written only on the transition, never on every emission: this collector's own
                    // `progress` input is fed by the `app_settings` row the write lands in, so an
                    // unconditional upsert invalidates the table that re-triggers the collector — a
                    // write loop that spins for as long as the screen is open and the account is linked.
                    if (telegram.isAuthenticated && !progress.telegramLinked) {
                        container.onboardingRepository.setTelegramLinked(true)
                    }
                }
        }

        viewModelScope.launch {
            // Resolving ~200 regions' worth of metadata is real work: keep it off the main thread.
            val countries = withContext(Dispatchers.Default) { container.countryRepository.countries }
            _uiState.update { state ->
                state.copy(
                    countries = countries,
                    selectedCountry = state.selectedCountry ?: suggestedCountry(),
                )
            }
        }

        viewModelScope.launch {
            container.mediaOrganizationRepository.observeBackupFolders()
                .collect { folders -> _uiState.update { it.copy(availableFolders = folders) } }
        }

        refreshSystemStatuses()
    }

    fun selectCountry(country: Country) {
        _uiState.update { it.copy(selectedCountry = country) }
    }

    fun onPhoneChange(raw: String) {
        val sanitized = PhoneNumbers.sanitizeInput(raw)
        if (sanitized != _uiState.value.phoneInput) {
            _uiState.update { it.copy(phoneInput = sanitized) }
        }
    }

    fun requestCode() {
        val state = _uiState.value
        val number = state.internationalNumber ?: return

        viewModelScope.launch { container.telegramAuthRepository.requestCode(number) }
    }

    fun submitCode(code: String) {
        viewModelScope.launch { container.telegramAuthRepository.submitCode(code) }
    }

    fun resendCode() {
        viewModelScope.launch { container.telegramAuthRepository.resendCode() }
    }

    fun submitPassword(password: String) {
        viewModelScope.launch { container.telegramAuthRepository.submitPassword(password) }
    }

    /** Back out of a code prompt without discarding the number the user typed. */
    fun editPhoneNumber() {
        container.telegramAuthRepository.cancelPendingRequest()
    }

    fun setBackupSource(source: BackupSource) {
        val folders = if (source == BackupSource.SelectedFolders) {
            _uiState.value.progress.selectedFolders
        } else {
            emptyList()
        }
        viewModelScope.launch {
            // The same call the folder screen in Settings makes: the source is also what switches unattended
            // backup on, so it has to re-read the schedule and ask for a first pass in the same breath.
            // Doing only the settings write here is how setup could finish with backup "on" and nothing
            // scheduled until the process was restarted.
            container.applyBackupSelection.apply(source, folders)
        }
    }

    fun toggleFolder(folder: String) {
        val selected = _uiState.value.progress.selectedFolders.toSet()
        val next = if (folder in selected) selected - folder else selected + folder

        viewModelScope.launch {
            // Every tap writes, because this screen has no Save to wait for. Repeated requests coalesce into
            // one follow-up pass on a unique work name rather than a scan per tap.
            container.applyBackupSelection.apply(BackupSource.SelectedFolders, next.toList())
        }
    }

    fun setNotificationsDecision(decision: OptionalStepDecision) {
        viewModelScope.launch { container.onboardingRepository.setNotificationsDecision(decision) }
    }

    fun setBackgroundBackupDecision(decision: OptionalStepDecision) {
        viewModelScope.launch { container.onboardingRepository.setBackgroundBackupDecision(decision) }
    }

    /**
     * Re-reads live system state. Permissions can be granted or revoked from outside the app, so
     * this runs on resume rather than trusting an answer from earlier in the flow.
     */
    fun refreshSystemStatuses() {
        viewModelScope.launch(Dispatchers.Default) {
            val media = container.permissionRepository.mediaStatus()
            val notifications = container.permissionRepository.notificationsStatus()
            val background = container.permissionRepository.backgroundBackupStatus()

            _uiState.update {
                it.copy(mediaAccess = media, notifications = notifications, backgroundBackup = background)
            }
        }
    }

    /** Which media permissions this Android version actually asks for. */
    fun mediaPermissionsToRequest(): List<String> = container.permissionRepository.mediaPermissionsToRequest()

    /** False on devices with no battery page, so the card offers an explanation instead of a button. */
    fun canOpenBatterySettings(): Boolean = container.permissionRepository.canOpenBatterySettings()

    fun completeOnboarding() {
        viewModelScope.launch { container.onboardingRepository.completeOnboarding() }
    }

    private fun suggestedCountry(): Country? {
        val suggestion = container.countryRepository.suggestedForDevice() ?: return null
        return _uiState.value.countries.firstOrNull { it.iso2 == suggestion.iso2 }
    }
}
