package com.lumovault.app.ui.onboarding

import com.lumovault.app.domain.model.LocalFolder
import com.lumovault.app.domain.model.BackgroundBackupStatus
import com.lumovault.app.domain.model.Country
import com.lumovault.app.domain.model.MediaAccessStatus
import com.lumovault.app.domain.model.NotificationsStatus
import com.lumovault.app.domain.model.OnboardingProgress
import com.lumovault.app.domain.model.OnboardingSummary
import com.lumovault.app.domain.telegram.AuthCodeChannel
import com.lumovault.app.domain.telegram.TelegramAuthState
import com.lumovault.app.domain.telegram.isAuthenticated
import com.lumovault.app.util.PhoneNumbers
import com.lumovault.app.util.Privacy

/** Which part of the sign-in flow the user is looking at. */
enum class TelegramPanel {
    Phone,
    Code,
    Password,
    ;

    companion object {
        /** Anything that is not an explicit credential prompt shows the number field again. */
        fun of(state: TelegramAuthState): TelegramPanel = when (state) {
            is TelegramAuthState.WaitingForCode -> Code
            is TelegramAuthState.WaitingForPassword -> Password
            else -> Phone
        }
    }
}

/** What the onboarding flow renders. Everything below the fields is derived, never stored twice. */
data class OnboardingUiState(
    val telegram: TelegramAuthState = TelegramAuthState.Unknown,
    val progress: OnboardingProgress = OnboardingProgress(),
    val mediaAccess: MediaAccessStatus = MediaAccessStatus.Unknown,
    val notifications: NotificationsStatus = NotificationsStatus.Unknown,
    val backgroundBackup: BackgroundBackupStatus = BackgroundBackupStatus.Unknown,
    val selectedCountry: Country? = null,
    val phoneInput: String = "",
    val countries: List<Country> = emptyList(),
    /** Which part of the sign-in flow the user is looking at. */
    val telegramPanel: TelegramPanel = TelegramPanel.Phone,
    /** Folders present in the media index; empty until the library has been scanned. */
    val availableFolders: List<LocalFolder> = emptyList(),
) {
    /** The sub-step Telegram's latest state implies, or the previous one while an error is showing. */
    val panel: TelegramPanel
        get() = if (telegram is TelegramAuthState.Failed) telegramPanel else TelegramPanel.of(telegram)
    /** The single place a phone number becomes E.164; the calling code is never glued on by hand. */
    val internationalNumber: String?
        get() = selectedCountry?.let { PhoneNumbers.toE164(it.iso2, phoneInput) }

    val canRequestCode: Boolean
        get() = selectedCountry != null && PhoneNumbers.hasSubscriberNumber(selectedCountry.iso2, phoneInput)

    val telegramConnected: Boolean get() = telegram.isAuthenticated

    val telegramUnavailable: Boolean get() = telegram is TelegramAuthState.NotConfigured

    /**
     * Authentication is required for the product, so the step cannot be passed silently — but when
     * the build cannot authenticate at all, the user is not held hostage by it either.
     */
    val canContinueAfterTelegram: Boolean get() = telegramConnected || telegramUnavailable

    val busy: Boolean
        get() = telegram is TelegramAuthState.SendingCode ||
            telegram is TelegramAuthState.VerifyingCode ||
            telegram is TelegramAuthState.Authenticating ||
            telegram is TelegramAuthState.Initializing

    /** Only ever a fragment of the number, for the "we sent a code" line. */
    val destinationHint: String
        get() = internationalNumber?.let { Privacy.tailOf(it) }.orEmpty()

    /** Which channel Telegram is using, so the prompt says "texted" or "calling" instead of guessing. */
    val codeChannel: AuthCodeChannel?
        get() = (telegram as? TelegramAuthState.WaitingForCode)?.channel

    fun summary(): OnboardingSummary = OnboardingSummary(
        progress = progress,
        telegram = telegram,
        mediaAccess = mediaAccess,
        notifications = notifications,
        backgroundBackup = backgroundBackup,
    )
}
