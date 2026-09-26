package com.lumovault.app.domain.usecase

import com.lumovault.app.data.local.backup.FakeBackupQueueDao
import com.lumovault.app.data.local.backup.QueueClock
import com.lumovault.app.data.repository.BackupQueueRepositoryImpl
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.model.OnboardingProgress
import com.lumovault.app.domain.model.OptionalStepDecision
import com.lumovault.app.domain.repository.OnboardingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What one saved folder selection does, in the one place that decides it.
 *
 * The bug these tests are written against was a settings write with nothing after it: the row said
 * "these folders", the periodic schedule stayed cancelled because it had been installed under the previous
 * answer, and the photos already in the folder waited for a pass nobody had asked for. So the assertions are
 * all about what a save *triggers*, not about what it stores — plus the other half of the same choice, which
 * is that leaving a folder out has to stop its unsent items, not merely stop queueing new ones.
 *
 * The queue here is the production repository over [FakeBackupQueueDao], so the withdrawal is checked
 * through the same state guards the database enforces.
 */
class ApplyBackupSelectionUseCaseTest {
    private val clock = QueueClock()
    private val dao = FakeBackupQueueDao()
    private val queue = BackupQueueRepositoryImpl(dao, clock::now, inTransaction = { it() }, attemptCap = 3)
    private val onboarding = RecordingOnboarding()
    private val rescheduled = mutableListOf<Boolean>()

    private val apply = ApplyBackupSelectionUseCase(
        onboarding = onboarding,
        queue = queue,
        reschedulePasses = { enablesBackup -> rescheduled += enablesBackup },
    )

    @Test
    fun savingAFolderStoresTheChoiceAndAsksForBackgroundWork() = runBlocking<Unit> {
        dao.withItemsIn(CAMERA, 1L, 2L)

        // Typed the way a folder is thought of rather than filed: no trailing separator. That separator is
        // part of the identity `relative_path IN (…)` compares, so a value without it matches no photo at all.
        apply.apply(BackupSource.SelectedFolders, listOf("DCIM/Camera"))

        assertEquals(BackupSource.SelectedFolders, onboarding.lastSource)
        assertEquals(
            "the identity the queue matches on is the normalized one, or the selection matches nothing",
            listOf(CAMERA),
            onboarding.lastFolders,
        )
        assertEquals("a real selection is the agreement, so the schedule has to be rebuilt and a pass asked for",
            listOf(true), rescheduled)
    }

    @Test
    fun aDeselectedFolderGivesBackWhatNeverLeftThePhoneAndKeepsWhatDid() = runBlocking<Unit> {
        dao.withItemsIn(CAMERA, 1L)
        dao.withItemsIn(WHATSAPP, 2L)
        dao.withItemsIn(WHATSAPP, 3L)
        queue.enqueue(listOf(1L, 2L, 3L))
        dao.forceRawState(3L, UploadState.BackedUp.storageKey)

        apply.apply(BackupSource.SelectedFolders, listOf(CAMERA))

        assertEquals(UploadState.Queued.storageKey, dao.row(1L).state)
        assertEquals(
            "the deselected item is still only waiting, so waiting is where it goes back to",
            UploadState.NotBackedUp.storageKey,
            dao.row(2L).state,
        )
        assertEquals(
            "what is already in the channel is not this screen's to undo",
            UploadState.BackedUp.storageKey,
            dao.row(3L).state,
        )
        assertEquals(
            "and its row is left behind rather than deleted, so re-selecting the folder finds it again",
            listOf(2L),
            queue.autoBackupCandidates(BackupSource.SelectedFolders, listOf(WHATSAPP), 10),
        )
    }

    @Test
    fun anEmptySelectionTakesBackEveryUnsentItem() = runBlocking<Unit> {
        dao.withItemsIn(CAMERA, 1L)
        dao.withItemsIn(WHATSAPP, 2L)
        queue.enqueue(listOf(1L, 2L))

        apply.apply(BackupSource.SelectedFolders, emptyList())

        assertEquals(UploadState.NotBackedUp.storageKey, dao.row(1L).state)
        assertEquals(UploadState.NotBackedUp.storageKey, dao.row(2L).state)
        assertEquals("choosing nothing is still a choice about the schedule", listOf(true), rescheduled)
    }

    @Test
    fun wideningToTheWholeLibraryWithdrawsNothing() = runBlocking<Unit> {
        dao.withItemsIn(CAMERA, 1L)
        dao.withItemsIn(WHATSAPP, 2L)
        queue.enqueue(listOf(1L, 2L))

        apply.apply(BackupSource.AllMedia, emptyList())

        assertEquals(UploadState.Queued.storageKey, dao.row(1L).state)
        assertEquals(UploadState.Queued.storageKey, dao.row(2L).state)
        assertEquals(emptyList<String>(), onboarding.lastFolders)
        assertEquals(listOf(true), rescheduled)
    }

    @Test
    fun turningBackupOffCancelsTheScheduleAndQueuesNothing() = runBlocking<Unit> {
        dao.withItemsIn(CAMERA, 1L)
        queue.enqueue(listOf(1L))

        apply.apply(BackupSource.NotNow, emptyList())

        assertEquals(
            "an answer of \"not now\" cannot be read as a request for a pass",
            listOf(false),
            rescheduled,
        )
        assertEquals(
            "and nothing is withdrawn either: a send the user asked for earlier is still a send they asked for",
            UploadState.Queued.storageKey,
            dao.row(1L).state,
        )
        assertEquals(emptyList<String>(), onboarding.lastFolders)
    }

    private companion object {
        const val CAMERA = "DCIM/Camera/"
        const val WHATSAPP = "Pictures/WhatsApp/"
    }
}

/** Records what was chosen, and is otherwise the settings row this decision writes. */
private class RecordingOnboarding : OnboardingRepository {
    private val state = MutableStateFlow(OnboardingProgress())

    var lastSource: BackupSource? = null
    var lastFolders: List<String> = emptyList()

    override val progress: Flow<OnboardingProgress> = state

    override suspend fun setBackupSource(source: BackupSource, folders: List<String>) {
        lastSource = source
        lastFolders = folders
        state.value = state.value.copy(backupSource = source, selectedFolders = folders)
    }

    override suspend fun setTelegramLinked(linked: Boolean) = Unit

    override suspend fun setNotificationsDecision(decision: OptionalStepDecision) = Unit

    override suspend fun setBackgroundBackupDecision(decision: OptionalStepDecision) = Unit

    override suspend fun completeOnboarding() = Unit
}
