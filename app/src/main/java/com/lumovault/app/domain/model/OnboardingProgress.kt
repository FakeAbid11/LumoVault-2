package com.lumovault.app.domain.model

/** What the user told LumoVault to back up (PRD section 40). `null` means no choice recorded yet. */
enum class BackupSource(val storageKey: String) {
    AllMedia("all"),
    SelectedFolders("folders"),
    NotNow("none");

    companion object {
        fun fromStorageKey(key: String?): BackupSource? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * Whether this answer is something the background pass can act on.
 *
 * [folderCount] belongs in the question, because an answer of "these folders" naming none is not a widened
 * permission but an empty one: `autoBackupCandidates` matches `relative_path` against the list and returns
 * nothing, so a schedule installed under that setting runs on its period for ever and queues no photo.
 * That is indistinguishable, from the phone's point of view, from backup being switched off — which is why
 * the same rule now decides the write, the schedule and whether the Settings row is a switch at all.
 *
 * A null source — never answered — enables nothing, whatever else the row says.
 */
fun BackupSource?.canRunAutomatic(folderCount: Int): Boolean = when (this) {
    BackupSource.AllMedia -> true
    BackupSource.SelectedFolders -> folderCount > 0
    BackupSource.NotNow, null -> false
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
