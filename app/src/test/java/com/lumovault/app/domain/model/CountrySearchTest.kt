package com.lumovault.app.domain.model

import com.lumovault.app.domain.telegram.AuthCodeChannel
import com.lumovault.app.domain.telegram.TelegramAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two separate guarantees, both cheap to break by accident: the search has to find a country by any
 * of the three ways a person looks for one, and the Ready screen's ticks must be derived from state
 * rather than optimism.
 */
class CountrySearchTest {
    private val sample = listOf(
        Country("AF", 93, "Afghanistan"),
        Country("AL", 355, "Albania"),
        Country("DZ", 213, "Algeria"),
        Country("AU", 61, "Australia"),
        Country("BD", 880, "Bangladesh"),
        Country("BR", 55, "Brazil"),
        Country("CA", 1, "Canada"),
    )

    @Test
    fun `name fragment finds the country`() {
        assertEquals(listOf("BD"), sample.search("Bang").map { it.iso2 })
        assertEquals(listOf("CA"), sample.search("can").map { it.iso2 })
    }

    @Test
    fun `calling code digits find the country`() {
        assertEquals(listOf("BD"), sample.search("880").map { it.iso2 })
        assertEquals(listOf("BR"), sample.search("+55").map { it.iso2 })
    }

    @Test
    fun `iso code finds the country`() {
        assertEquals(listOf("BD"), sample.search("bd").map { it.iso2 })
        assertEquals(listOf("AU"), sample.search("AU").map { it.iso2 })
    }

    @Test
    fun `an empty query returns everything and a bad query returns nothing`() {
        assertEquals(sample.size, sample.search("   ").size)
        assertTrue(sample.search("zzzz").isEmpty())
    }

    @Test
    fun `searching never reorders the full list`() {
        assertEquals(sample.sortedBy { it.name }, sample.search(""))
    }

    @Test
    fun `flags are built from the iso code rather than an image asset`() {
        // U+1F1E6 + letter offset: "BD" is the B and D regional indicators.
        assertEquals("\uD83C\uDDE7\uD83C\uDDE9", Country("BD", 880, "Bangladesh").flag)
        assertEquals("+880", Country("BD", 880, "Bangladesh").dialPrefix)
    }
}

/** PRD section 75 / phase rule: a checkmark may only appear for something that actually happened. */
class OnboardingSummaryTest {
    private fun summary(
        telegram: TelegramAuthState = TelegramAuthState.Authenticated,
        progress: OnboardingProgress = OnboardingProgress(),
        media: MediaAccessStatus = MediaAccessStatus.Granted,
        notifications: NotificationsStatus = NotificationsStatus.Granted,
        background: BackgroundBackupStatus = BackgroundBackupStatus.Unrestricted,
    ) = OnboardingSummary(progress, telegram, media, notifications, background)

    @Test
    fun `a build that cannot reach telegram is unavailable, never done and never silently skipped`() {
        val summary = summary(telegram = TelegramAuthState.NotConfigured)

        assertEquals(ChecklistStatus.Unavailable, summary.telegramItem.status)
        assertEquals(false, summary.telegramConnected)
    }

    @Test
    fun `only an authenticated session earns the telegram tick`() {
        assertEquals(ChecklistStatus.Done, summary().telegramItem.status)
        assertEquals(ChecklistStatus.Missing, summary(telegram = TelegramAuthState.ReadyForPhoneNumber).telegramItem.status)
        assertEquals(
            ChecklistStatus.Missing,
            summary(telegram = TelegramAuthState.WaitingForCode(AuthCodeChannel.Sms, 6)).telegramItem.status,
        )
    }

    @Test
    fun `partial media access is a tick, denial is not`() {
        assertEquals(ChecklistStatus.Done, summary(media = MediaAccessStatus.PartiallyGranted).mediaItem.status)
        assertEquals(ChecklistStatus.Missing, summary(media = MediaAccessStatus.Denied).mediaItem.status)
        assertEquals(ChecklistStatus.Missing, summary(media = MediaAccessStatus.Unknown).mediaItem.status)
    }

    @Test
    fun `an android version with no notification permission is satisfied, not skipped`() {
        assertEquals(
            ChecklistStatus.Done,
            summary(notifications = NotificationsStatus.NotRequired).notificationsItem.status,
        )
    }

    @Test
    fun `a refused notification permission reads as skipped only when the user said so`() {
        val declined = OnboardingProgress(notifications = OptionalStepDecision.Skipped)

        assertEquals(
            ChecklistStatus.Skipped,
            summary(progress = declined, notifications = NotificationsStatus.Denied).notificationsItem.status,
        )
        assertEquals(
            ChecklistStatus.Missing,
            summary(notifications = NotificationsStatus.Denied).notificationsItem.status,
        )
    }

    @Test
    fun `choosing not now is recorded as a decision, not as a completed selection`() {
        val notNow = OnboardingProgress(backupSource = BackupSource.NotNow)
        val all = OnboardingProgress(backupSource = BackupSource.AllMedia)

        assertEquals(ChecklistStatus.Missing, summary().backupSourceItem.status)
        assertEquals(ChecklistStatus.Skipped, summary(progress = notNow).backupSourceItem.status)
        assertEquals(ChecklistStatus.Done, summary(progress = all).backupSourceItem.status)
    }

    @Test
    fun `backup is enabled only when something was actually selected`() {
        assertTrue(BackupSource.AllMedia.enablesBackup)
        assertTrue(BackupSource.SelectedFolders.enablesBackup)
        assertEquals(false, BackupSource.NotNow.enablesBackup)
    }
}
