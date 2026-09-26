package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.model.FolderPaths
import com.lumovault.app.domain.repository.OnboardingRepository

/**
 * What actually happens when the user changes what should be backed up.
 *
 * Three things, in this order, and the order is the argument:
 *
 *  1. **Write the choice.** [OnboardingRepository.setBackupSource] stores the source, the normalized folder
 *     list, and — because a real selection *is* the agreement — flips the one `backupEnabled` column the
 *     automatic pass reads. There is no second "background backup" switch to discover, and the Settings
 *     toggle shows the same column, so the screen cannot disagree with the queue.
 *  2. **Withdraw the work the new choice excludes.** Rows still sitting at `queued` for a folder that is no
 *     longer selected go back to being merely known. Without this, deselecting a folder stops *future*
 *     queueing and changes nothing about the forty items already lined up to leave the phone, which is not
 *     what the person tapping the checkbox meant. Nothing claimed, sent, failed or cancelled is touched.
 *  3. **Re-read the schedule and ask for a pass now.** Constraints live on the WorkManager request rather
 *     than in the settings row, so a schedule left as it was runs under rules the user just changed; and a
 *     save that only re-installs the periodic pass makes somebody wait up to six hours for the first copy of
 *     the photos they just opted in.
 *
 * Both folder pickers — onboarding's and the one in Settings — go through here, because a choice that means
 * one thing in one screen and another in the second is the bug this shape exists to prevent.
 */
class ApplyBackupSelectionUseCase(
    private val onboarding: OnboardingRepository,
    private val queue: BackupQueueRepository,
    /**
     * Re-installs (or cancels) the periodic pass and, when backup is on, asks for one scan immediately.
     *
     * A callback rather than a scheduler reference so the rule "saving a selection means background work
     * starts" is testable without WorkManager, and so the one place that knows about WorkManager keeps being
     * [com.lumovault.app.data.backup.BackupScheduler].
     */
    private val reschedulePasses: (automatic: Boolean) -> Unit,
) {

    suspend fun apply(source: BackupSource, folders: List<String>) {
        // The identity the queue matches on, decided once. `relative_path` equality is exact, so a path
        // stored with its trailing separator trimmed — or as the label the picker showed — matches nothing,
        // which looks identical to "this folder has no photos".
        val selected = if (source == BackupSource.SelectedFolders) {
            folders.map(FolderPaths::normalize).distinct().sorted()
        } else {
            emptyList()
        }

        onboarding.setBackupSource(source, selected)

        // Only a folder selection can exclude anything. "Everything" is a widening, and an unsent row is
        // already inside it.
        if (source == BackupSource.SelectedFolders) queue.releaseUnsentOutside(selected)

        reschedulePasses(source.enablesBackup)
    }
}
