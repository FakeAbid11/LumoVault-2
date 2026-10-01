package com.lumovault.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.domain.model.NotificationsStatus
import com.lumovault.app.domain.model.ThemeMode
import com.lumovault.app.domain.telegram.TelegramAuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What section 43's screens report, and the two writes they make.
 *
 * The five categories share one view model rather than one each because three of them read the same
 * facts — the session, the theme, the channel — and a settings section whose Account row and Cloud row
 * disagreed about whether Telegram is signed in would be a section nothing in it could be trusted for.
 * Every read is a live flow for the same reason the permission screens re-query: each fact can change
 * outside the app (a grant revoked, a session cleared, an account swapped), and a remembered answer is
 * a lie by the time this screen draws it.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LumoVaultApplication).container

    /** The live session. The screens word it with [com.lumovault.app.ui.backup.TelegramWord], the diagnostics panel's four answers. */
    val authState: StateFlow<TelegramAuthState> = container.telegramAuthRepository.state

    /** The same row the palette icon cycles, so the bar's control and the appearance list never hold two truths. */
    val themeMode: StateFlow<ThemeMode> = container.settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_MILLIS), ThemeMode.Default)

    /**
     * Whether this build can see a backup channel, once Room has answered — null before that, and
     * from then on a real yes or no. A default of "no" would print "Not found yet" over a lookup still
     * in flight, which is the app claiming to have checked when it has not.
     */
    val channelAvailable: StateFlow<Boolean?> = flow<Boolean?> {
        emitAll(container.cloudIndexRepository.observeAssociation().map { it != null })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_MILLIS), null)

    /**
     * How many items the cloud index holds, null until the first query returns: "0 items found" during
     * the opening read is a number the database has not produced, and a count is data, not a state to
     * approximate.
     */
    val indexedCount: StateFlow<Int?> = flow<Int?> {
        emitAll(container.cloudIndexRepository.observeCount())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_MILLIS), null)

    private val _notificationsStatus =
        MutableStateFlow(container.permissionRepository.notificationsStatus())

    /** The grant as Android reports it right now; [refreshNotifications] re-reads it after a trip to system settings. */
    val notificationsStatus: StateFlow<NotificationsStatus> = _notificationsStatus.asStateFlow()

    /**
     * Re-read the notification permission. It is granted and revoked in a window this app never sees,
     * so the answer is fetched each time the screen is shown rather than held from whenever it was
     * first asked — a stale "Allowed" is how a settings screen stops being a report.
     */
    fun refreshNotifications() {
        _notificationsStatus.value = container.permissionRepository.notificationsStatus()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            container.settingsRepository.setThemeMode(mode)
        }
    }

    /**
     * The session goes; the channel and every backup in it stay put — the account row's note says so,
     * which is why the screen confirms nothing before calling: there is no data here to lose.
     */
    fun signOut() {
        viewModelScope.launch {
            container.telegramAuthRepository.signOut()
        }
    }

    private companion object {
        /** Long enough to outlive a rotation, short enough that a popped route's queries stop. */
        const val SUBSCRIPTION_MILLIS = 5_000L
    }
}
