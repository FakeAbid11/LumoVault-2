package com.lumovault.app.domain.usecase

import com.lumovault.app.data.local.backup.FakeBackupQueueDao
import com.lumovault.app.data.local.backup.QueueClock
import com.lumovault.app.data.repository.BackupQueueRepositoryImpl
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.BackgroundBackupStatus
import com.lumovault.app.data.local.backup.FakeMediaRow
import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaAccessStatus
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.model.NotificationsStatus
import com.lumovault.app.domain.model.OnboardingProgress
import com.lumovault.app.domain.model.ThemeMode
import com.lumovault.app.domain.repository.MediaRepository
import com.lumovault.app.domain.repository.OnboardingRepository
import com.lumovault.app.domain.repository.PermissionRepository
import com.lumovault.app.domain.repository.SettingsRepository
import com.lumovault.app.domain.repository.SyncResult
import com.lumovault.app.domain.model.BackupPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automatic backup, tested through the same queue the user's own taps write to.
 *
 * The queue here is the production repository over [FakeBackupQueueDao], because everything worth asserting
 * about this pass is about the queue: that it cannot take the same item twice, that a cancelled item stays
 * cancelled, that "nothing selected" is not "everything", and that an item already settled as stored is not
 * put back in line by a pass that did not know.
 */
class RunAutomaticBackupUseCaseTest {
    private val clock = QueueClock()
    private val dao = FakeBackupQueueDao()
    private val queue = BackupQueueRepositoryImpl(dao, clock::now, attemptCap = 3)
    private val media = FakeLibrary()
    private val onboarding = FakeOnboarding()
    private val settings = FakeSettings()
    private val permissions = FakePermissions()
    private var schedules = 0

    private fun useCase(limit: Int = 200) = RunAutomaticBackupUseCase(
        media = media,
        queue = queue,
        onboarding = onboarding,
        settings = settings,
        permissions = permissions,
        scheduleUpload = { schedules += 1 },
        candidatesPerPass = limit,
    )

    @Test
    fun aNewPhotoInTheSelectedFolderIsQueuedForTheExistingWorker() = runBlocking<Unit> {
        dao.withItem(1L)
        permissions.access = MediaAccessStatus.Granted
        settings.enableAutomatic()
        onboarding.set(source = BackupSource.AllMedia)

        val outcome = useCase().run()

        assertEquals(RunAutomaticBackupUseCase.Outcome.Queued(queued = 1, moreRemaining = false), outcome)
        assertEquals(UploadState.Queued.storageKey, dao.row(1L).state)
        assertEquals("the pass asks for a send; it never sends one itself", 1, schedules)
        assertEquals(1, media.syncs)
    }

    @Test
    fun aPassIsNeverTakenWhenTheUserHasAutomaticBackupOff() = runBlocking<Unit> {
        dao.withItem(1L)
        permissions.access = MediaAccessStatus.Granted
        onboarding.set(source = BackupSource.AllMedia)

        val outcome = useCase().run()

        assertEquals(RunAutomaticBackupUseCase.Outcome.Disabled, outcome)
        assertEquals("no scan, no queue write, no upload request", 0, media.syncs)
        assertEquals(0, schedules)
        assertFalse("nothing entered the queue", queue.hasQueuedWork())
    }

    @Test
    fun aLibraryIsNotScannedWithoutMediaAccess() = runBlocking<Unit> {
        dao.withItem(1L)
        permissions.access = MediaAccessStatus.Denied
        settings.enableAutomatic()
        onboarding.set(source = BackupSource.AllMedia)

        assertEquals(RunAutomaticBackupUseCase.Outcome.NoMediaAccess, useCase().run())
        assertEquals(0, media.syncs)
    }

    @Test
    fun aPartiallyGrantedLibraryIsScannedBecauseThatIsWhatAndroidReported() = runBlocking<Unit> {
        dao.withItem(1L)
        permissions.access = MediaAccessStatus.PartiallyGranted
        settings.enableAutomatic()
        onboarding.set(source = BackupSource.AllMedia)

        val outcome = useCase().run()

        assertTrue(
            "the app sees what the grant covers, so it queues that and says so, rather than refusing",
            outcome is RunAutomaticBackupUseCase.Outcome.Queued,
        )
    }

    @Test
    fun aFolderTheUserDidNotSelectIsNeverSent() = runBlocking<Unit> {
        dao.withItem(1L)
        dao.withItemsIn("Pictures/Screenshots/", 2L)
        permissions.access = MediaAccessStatus.Granted
        settings.enableAutomatic()
        onboarding.set(source = BackupSource.SelectedFolders, folders = listOf(FakeMediaRow.DEFAULT_PATH))

        useCase().run()

        assertEquals(UploadState.Queued.storageKey, dao.rowOrNull(1L)?.state)
        assertEquals(
            "the screenshot is not in a selected folder, so it has no row at all",
            null,
            dao.rowOrNull(2L)?.state,
        )
    }

    @Test
    fun aSourceThatWasNeverAnsweredIsNotReadAsEverything() = runBlocking<Unit> {
        dao.withItem(1L)
        permissions.access = MediaAccessStatus.Granted
        settings.enableAutomatic()
        onboarding.set(source = null)

        assertEquals(RunAutomaticBackupUseCase.Outcome.NoSourceSelected, useCase().run())
        assertEquals(0, media.syncs)
        assertFalse(queue.hasQueuedWork())
    }

    @Test
    fun choosingNoneSendsNothingEvenAfterTheToggleWasTurnedOn() = runBlocking<Unit> {
        dao.withItem(1L)
        permissions.access = MediaAccessStatus.Granted
        settings.enableAutomatic()
        onboarding.set(source = BackupSource.NotNow)

        assertEquals(RunAutomaticBackupUseCase.Outcome.NoSourceSelected, useCase().run())
        assertFalse(queue.hasQueuedWork())
    }

    @Test
    fun twoPassesOverTheSameLibraryQueueItOnce() = runBlocking<Unit> {
        dao.withItem(1L)
        permissions.access = MediaAccessStatus.Granted
        settings.enableAutomatic()
        onboarding.set(source = BackupSource.AllMedia)
        val useCase = useCase()

        useCase.run()
        val second = useCase.run()

        assertEquals("the second pass found nothing new", 0, (second as RunAutomaticBackupUseCase.Outcome.Queued).queued)
        assertEquals(
            "a retried worker or an overlapping period must not double-send",
            1,
            dao.countIn(UploadState.Queued.storageKey),
        )
    }

    @Test
    fun anItemTheUserCancelledIsNotPutBackInTheQueueByAnUnattendedPass() = runBlocking<Unit> {
        dao.withItem(1L)
        queue.enqueue(listOf(1L))
        queue.cancelQueued()
        permissions.access = MediaAccessStatus.Granted
        settings.enableAutomatic()
        onboarding.set(source = BackupSource.AllMedia)

        useCase().run()

        assertEquals(
            "a cancellation is a decision, and the only states a pass may take are the ones that assert nothing",
            UploadState.Cancelled.storageKey,
            dao.row(1L).state,
        )
    }

    @Test
    fun somethingAlreadyStoredIsNotQueuedAgainAfterAMissedNotification() = runBlocking<Unit> {
        dao.withItem(1L)
        queue.recordRestored(
            mediaStoreId = 1L,
            remote = com.lumovault.app.domain.repository.RemoteBackup(7L, 42L),
            identity = com.lumovault.app.domain.backup.MediaIdentity("a".repeat(64), 1_024L, 1L),
        )
        permissions.access = MediaAccessStatus.Granted
        settings.enableAutomatic()
        onboarding.set(source = BackupSource.AllMedia)

        val outcome = useCase().run()

        assertEquals(RunAutomaticBackupUseCase.Outcome.Queued(0, moreRemaining = false), outcome)
        assertEquals(UploadState.BackedUp.storageKey, dao.row(1L).state)
        assertEquals("nothing to send, so no upload pass was asked for", 0, schedules)
    }

    @Test
    fun anItemInTrashIsNotAnAutomaticBackupCandidate() = runBlocking<Unit> {
        dao.withItem(1L)
        dao.markTrashed(1L)
        permissions.access = MediaAccessStatus.Granted
        settings.enableAutomatic()
        onboarding.set(source = BackupSource.AllMedia)

        useCase().run()

        assertEquals(null, dao.rowOrNull(1L))
    }

    @Test
    fun aFullWindowReportsThatMoreRemainsInsteadOfLookingFinished() = runBlocking<Unit> {
        (1L..3L).forEach { dao.withItem(it) }
        permissions.access = MediaAccessStatus.Granted
        settings.enableAutomatic()
        onboarding.set(source = BackupSource.AllMedia)

        val outcome = useCase(limit = 2).run()

        assertEquals(
            "two of three fit the window, so the pass says there is more to do",
            RunAutomaticBackupUseCase.Outcome.Queued(queued = 2, moreRemaining = true),
            outcome,
        )
    }

    @Test
    fun nothingNewStillReconcilesTheIndexSoADeletedFileStopsBeingOffered() = runBlocking<Unit> {
        permissions.access = MediaAccessStatus.Granted
        settings.enableAutomatic()
        onboarding.set(source = BackupSource.AllMedia)

        useCase().run()

        assertEquals("the scan is what discovers both new files and gone ones", 1, media.syncs)
        assertEquals(0, schedules)
    }
}

private class FakeLibrary : MediaRepository {
    var syncs = 0

    override suspend fun sync(): SyncResult {
        syncs += 1
        return SyncResult(indexed = 0, removed = 0)
    }

    override suspend fun local(mediaStoreId: Long): Media? = null

    override suspend fun clear() = Unit

    override fun observeWindow(limit: Int): Flow<List<Media>> = flowOf(emptyList())

    override fun observeCount(): Flow<Int> = flowOf(0)

    override fun observeCountByType(): Flow<Map<MediaType, Int>> = flowOf(emptyMap())

    override fun observeFolders(): Flow<List<String>> = flowOf(emptyList())
}

private class FakeOnboarding : OnboardingRepository {
    private val state = MutableStateFlow(OnboardingProgress())

    override val progress: Flow<OnboardingProgress> = state

    fun set(source: BackupSource?, folders: List<String> = emptyList()) {
        state.value = state.value.copy(backupSource = source, selectedFolders = folders)
    }

    override suspend fun setTelegramLinked(linked: Boolean) = Unit

    override suspend fun setNotificationsDecision(decision: com.lumovault.app.domain.model.OptionalStepDecision) =
        Unit

    override suspend fun setBackgroundBackupDecision(
        decision: com.lumovault.app.domain.model.OptionalStepDecision,
    ) = Unit

    override suspend fun setBackupSource(source: BackupSource, folders: List<String>) {
        state.value = state.value.copy(backupSource = source, selectedFolders = folders)
    }

    override suspend fun completeOnboarding() = Unit
}

private class FakeSettings : SettingsRepository {
    private val preferences = MutableStateFlow(BackupPreferences.Default)

    override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.System)

    override suspend fun setThemeMode(mode: ThemeMode) = Unit

    override val backupPreferences: Flow<BackupPreferences> = preferences

    fun enableAutomatic() {
        preferences.value = preferences.value.copy(automatic = true)
    }

    override suspend fun setAutomaticBackup(enabled: Boolean) {
        preferences.value = preferences.value.copy(automatic = enabled)
    }

    override suspend fun setBackupWifiOnly(enabled: Boolean) {
        preferences.value = preferences.value.copy(wifiOnly = enabled)
    }

    override suspend fun setBackupChargingOnly(enabled: Boolean) {
        preferences.value = preferences.value.copy(chargingOnly = enabled)
    }
}

private class FakePermissions : PermissionRepository {
    var access: MediaAccessStatus = MediaAccessStatus.Granted

    override fun mediaPermissionsToRequest(): List<String> = emptyList()

    override fun mediaStatus(): MediaAccessStatus = access

    override fun mediaLocationPermissionToRequest(): String = "android.permission.ACCESS_MEDIA_LOCATION"

    override fun mediaLocationGranted(): Boolean = false

    override fun notificationsStatus(): NotificationsStatus = NotificationsStatus.Granted

    override fun backgroundBackupStatus(): BackgroundBackupStatus = BackgroundBackupStatus.Unrestricted

    override fun canOpenBatterySettings(): Boolean = false
}
