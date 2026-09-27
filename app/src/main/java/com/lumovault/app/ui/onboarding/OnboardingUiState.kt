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
import com.lumovault.app.domain.telegram.isInFlight
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
    /**
     * The sub-step Telegram's latest *answer* implies. In-flight states — a request on the wire or a
     * failure of the last one — keep the panel the user is looking at; see [isInFlight].
     */
    val panel: TelegramPanel
        get() = if (telegram.isInFlight) telegramPanel else TelegramPanel.of(telegram)
    /** The single place a phone number becomes E.164; the calling code is never glued on by hand. */
    val internationalNumber: String?
        get() = selectedCountry?.let { PhoneNumbers.toE164(it.iso2, phoneInput) }

    val canRequestCode: Boolean
        get() = selectedCountry != null && PhoneNumbers.hasSubscriberNumber(selectedCountry.iso2, phoneInput)

    val telegramUnavailable: Boolean get() = telegram is TelegramAuthState.NotConfigured

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

/**
 * Whether the sign-in step offers its own "Skip for now".
 *
 * Four conditions, each ruling out a case where the control would be a second, different answer to the
 * same question. Only the phone panel: mid-code or mid-password the user has already chosen to connect, and
 * abandoning a request Telegram has answered is how a resend ends up addressed to a number nobody typed.
 * Only inside setup: the reconnect screen exists *because* Telegram is needed, and it has a back arrow.
 * Only when the build can authenticate: without credentials the primary button already moves past the
 * step, and two doors labelled differently for one action is how they start disagreeing. And never while a
 * request is in flight, because the tap would race the answer.
 */
fun telegramSkipOffered(
    panel: TelegramPanel,
    standalone: Boolean,
    buildHasTelegram: Boolean,
    busy: Boolean,
): Boolean = panel == TelegramPanel.Phone && !standalone && buildHasTelegram && !busy
