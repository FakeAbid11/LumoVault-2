package com.lumovault.app.domain.model

import com.lumovault.app.domain.telegram.TelegramAuthState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Ready screen's checklist, derived from persisted progress plus live facts.
 *
 * The rule with a history here is the backup source's: "these folders" naming none is not a widened
 * permission but an empty one — [canRunAutomatic] refuses it for the write and the schedule, and the
 * checklist used to tick it anyway, so setup could finish with a ✓ over a step the backup engine
 * could never run.
 */
class OnboardingSummaryTest {
    private fun summary(progress: OnboardingProgress) = OnboardingSummary(
        progress = progress,
        telegram = TelegramAuthState.ReadyForPhoneNumber,
        mediaAccess = MediaAccessStatus.Granted,
        notifications = NotificationsStatus.NotRequired,
        backgroundBackup = BackgroundBackupStatus.Unrestricted,
    )

    @Test
    fun `an unanswered source is a missing step`() {
        assertEquals(
            ChecklistStatus.Missing,
            summary(OnboardingProgress(backupSource = null)).backupSourceItem.status,
        )
    }

    @Test
    fun `not now is reported as skipped, not as a failure`() {
        assertEquals(
            ChecklistStatus.Skipped,
            summary(OnboardingProgress(backupSource = BackupSource.NotNow)).backupSourceItem.status,
        )
    }

    @Test
    fun `everything is a done step`() {
        assertEquals(
            ChecklistStatus.Done,
            summary(OnboardingProgress(backupSource = BackupSource.AllMedia)).backupSourceItem.status,
        )
    }

    @Test
    fun `selected folders with folders chosen is a done step`() {
        assertEquals(
            ChecklistStatus.Done,
            summary(
                OnboardingProgress(
                    backupSource = BackupSource.SelectedFolders,
                    selectedFolders = listOf("DCIM/Camera/"),
                ),
            ).backupSourceItem.status,
        )
    }

    @Test
    fun `selected folders naming none is not a done step`() {
        assertEquals(
            ChecklistStatus.Missing,
            summary(
                OnboardingProgress(backupSource = BackupSource.SelectedFolders, selectedFolders = emptyList()),
            ).backupSourceItem.status,
        )
    }
}
