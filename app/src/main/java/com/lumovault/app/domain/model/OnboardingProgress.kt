package com.lumovault.app.domain.model

/** What the user told LumoVault to back up (PRD section 40). `null` means no choice recorded yet. */
enum class BackupSource(val storageKey: String) {
    AllMedia("all"),
    SelectedFolders("folders"),
    NotNow("none");

    /**
     * Automatic backup is only meaningful when something was selected; "Not now" is a legitimate
     * completed choice that Settings can change later, not a failure to configure.
     */
    val enablesBackup: Boolean
        get() = this == AllMedia || this == SelectedFolders

    companion object {
        fun fromStorageKey(key: String?): BackupSource? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * Outcome of an onboarding item the user may skip. Persisted so a skipped step is not nagged for
 * on every relaunch, and distinct from a step that is actually done.
 */
enum class OptionalStepDecision(val storageKey: String) {
    NotAsked("not_asked"),
    Completed("completed"),
    Skipped("skipped");

    companion object {
        fun fromStorageKey(key: String?): OptionalStepDecision =
            entries.firstOrNull { it.storageKey == key } ?: NotAsked
    }
}

/** The persisted half of onboarding: what the user decided. Live system facts stay out of it. */
data class OnboardingProgress(
    val onboardingCompleted: Boolean = false,
    /** An account was linked at some point; whether it is *currently* valid comes from TDLib. */
    val telegramLinked: Boolean = false,
    val backupEnabled: Boolean = false,
    val notifications: OptionalStepDecision = OptionalStepDecision.NotAsked,
    val backupSource: BackupSource? = null,
    val selectedFolders: List<String> = emptyList(),
    val backgroundBackup: OptionalStepDecision = OptionalStepDecision.NotAsked,
)
