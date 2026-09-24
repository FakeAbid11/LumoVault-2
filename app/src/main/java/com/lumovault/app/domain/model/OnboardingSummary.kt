package com.lumovault.app.domain.model

import com.lumovault.app.domain.telegram.TelegramAuthState

/**
 * Live status of the three things the permissions screen sets up. Read from the system rather than
 * from a stored flag, because Android lets the user revoke a grant at any time and a cached "true"
 * would turn into a lie on screen 6.
 */
enum class MediaAccessStatus {
    Unknown,

    /** No usable access; backup would find nothing. */
    Denied,

    /** Android 14+ "selected photos": some, but not all, of the library. */
    PartiallyGranted,
    Granted,
    ;

    val allowsScanning: Boolean get() = this == Granted || this == PartiallyGranted
}

enum class NotificationsStatus {
    Unknown,

    /** This Android version has no runtime notification permission to ask for. */
    NotRequired,
    Denied,
    Granted,
    ;

    val satisfied: Boolean get() = this == Granted || this == NotRequired
}

enum class BackgroundBackupStatus {
    Unknown,

    /** Battery optimization is off for LumoVault, so automatic backup can run unimpeded. */
    Unrestricted,
    Restricted,
}

/** One line of the "You're ready" checklist. */
enum class ChecklistStatus {
    Done,

    /** The user chose to skip an optional step; reported honestly, not as a success. */
    Skipped,

    /** Not done, and still doable. */
    Missing,

    /** Cannot be done in this build (e.g. no TDLib binary). Never rendered as a tick. */
    Unavailable,
}

data class ChecklistItem(val status: ChecklistStatus)

/**
 * What screen 6 shows and what gates the transition into the app. Pure derivation from persisted
 * progress plus live facts, so the checklist cannot drift from reality between renders.
 */
data class OnboardingSummary(
    val progress: OnboardingProgress,
    val telegram: TelegramAuthState,
    val mediaAccess: MediaAccessStatus,
    val notifications: NotificationsStatus,
    val backgroundBackup: BackgroundBackupStatus,
) {
    val telegramConnected: Boolean
        get() = telegram is TelegramAuthState.Authenticated

    val mediaAccessSatisfied: Boolean
        get() = mediaAccess.allowsScanning

    val telegramItem: ChecklistItem
        get() = ChecklistItem(
            when {
                telegramConnected -> ChecklistStatus.Done
                // A build without the TDLib binary cannot be "completed" by the user, so it must
                // not show as a missing step either.
                telegram is TelegramAuthState.NotConfigured -> ChecklistStatus.Unavailable
                else -> ChecklistStatus.Missing
            },
        )

    val mediaItem: ChecklistItem
        get() = ChecklistItem(
            if (mediaAccessSatisfied) ChecklistStatus.Done else ChecklistStatus.Missing,
        )

    val notificationsItem: ChecklistItem
        get() = ChecklistItem(
            when (notifications) {
                NotificationsStatus.Granted, NotificationsStatus.NotRequired -> ChecklistStatus.Done
                NotificationsStatus.Denied ->
                    if (progress.notifications == OptionalStepDecision.Skipped) {
                        ChecklistStatus.Skipped
                    } else {
                        ChecklistStatus.Missing
                    }
                NotificationsStatus.Unknown -> ChecklistStatus.Missing
            },
        )

    val backupSourceItem: ChecklistItem
        get() = ChecklistItem(
            when (progress.backupSource) {
                null -> ChecklistStatus.Missing
                BackupSource.NotNow -> ChecklistStatus.Skipped
                else -> ChecklistStatus.Done
            },
        )
}
