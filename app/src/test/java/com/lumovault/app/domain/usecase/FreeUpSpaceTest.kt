package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.organization.MediaOrganizationRepository
import com.lumovault.app.domain.repository.FreeUpSpaceRepository
import com.lumovault.app.domain.repository.MediaRepository
import com.lumovault.app.domain.repository.SyncResult
import com.lumovault.app.domain.restore.EligibilityCheck
import com.lumovault.app.domain.restore.FreeUpSpaceCandidate
import com.lumovault.app.domain.restore.FreeUpSpaceEligibility
import com.lumovault.app.domain.restore.FreeUpSpacePlan
import com.lumovault.app.domain.restore.ItemEvidence
import com.lumovault.app.domain.restore.RejectionReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether a file may be removed from a phone, and the two steps that apply it.
 *
 * Every case here is a way Free Up Space could take a picture that is not actually safe. The evidence type
 * makes those cases writable as data rather than as a database, which is the only reason they can be
 * asserted at all: a build server cannot hold a photo library, but it can hold a table of who may delete
 * what.
 */
class FreeUpSpaceTest {

    private val deletable = evidence()

    @Test
    fun aFileWhoseMessageTheIndexStillHoldsIsEligible() {
        assertNull(FreeUpSpaceEligibility.reasonFor(deletable))
        assertTrue(FreeUpSpaceEligibility.isDeletable(deletable))
    }

    @Test
    fun anItemWithNoBackupRecordAtAllIsNotEligible() {
        // The local-only case: the file exists and nothing else does.
        assertEquals(
            RejectionReason.NotBackedUp,
            FreeUpSpaceEligibility.reasonFor(evidence(queueState = null)),
        )
        assertEquals(
            RejectionReason.NotBackedUp,
            FreeUpSpaceEligibility.reasonFor(evidence(queueState = UploadState.NotBackedUp)),
        )
        assertEquals(
            "a file waiting to be sent is not a file that has been sent",
            RejectionReason.NotBackedUp,
            FreeUpSpaceEligibility.reasonFor(evidence(queueState = UploadState.Queued)),
        )
        assertEquals(
            RejectionReason.NotBackedUp,
            FreeUpSpaceEligibility.reasonFor(evidence(queueState = UploadState.Failed)),
        )
    }

    @Test
    fun aSendInFlightIsRefusedOnItsOwnTermsRatherThanCalledUnbacked() {
        listOf(UploadState.Preparing, UploadState.Uploading).forEach { state ->
            assertEquals(
                "a row the worker owns has not been settled either way, and 'not backed up' would be wrong " +
                    "about work that is happening",
                RejectionReason.UploadInFlight,
                FreeUpSpaceEligibility.reasonFor(evidence(queueState = state)),
            )
        }
    }

    @Test
    fun aBackedUpRowThatNamesNoMessageHasNoStoredHome() {
        assertEquals(
            RejectionReason.NoAssociation,
            FreeUpSpaceEligibility.reasonFor(evidence(chatId = 0L)),
        )
        assertEquals(
            RejectionReason.NoAssociation,
            FreeUpSpaceEligibility.reasonFor(evidence(messageId = 0L)),
        )
    }

    @Test
    fun aBackupWithoutAContentHashIsVerificationIncomplete() {
        assertEquals(
            "this is the distinction PRD section 53 asks for: a message exists, and it has never been " +
                "shown to hold *this* file",
            RejectionReason.NoContentIdentity,
            FreeUpSpaceEligibility.reasonFor(evidence(hasContentHash = false)),
        )
    }

    @Test
    fun aMessageTheCloudIndexNoLongerHoldsIsNotAReasonToDelete() {
        assertEquals(
            RejectionReason.CloudRecordGone,
            FreeUpSpaceEligibility.reasonFor(evidence(cloudMessagePresent = false)),
        )
    }

    @Test
    fun trashIsReportedAsTrashRatherThanAsABackupQuestion() {
        assertEquals(RejectionReason.InTrash, FreeUpSpaceEligibility.reasonFor(evidence(trashed = true)))
        assertEquals(
            "the reason that explains the item wins over the reason that merely applies to it",
            RejectionReason.InTrash,
            FreeUpSpaceEligibility.reasonFor(evidence(trashed = true, hasContentHash = false)),
        )
    }

    @Test
    fun aCancelledBackupIsRefusedAndSaysSo() {
        assertEquals(
            RejectionReason.NotBackedUp,
            FreeUpSpaceEligibility.reasonFor(evidence(queueState = UploadState.Cancelled)),
        )
    }

    @Test
    fun anItemThatVanishedBetweenReviewAndConfirmationIsStillAnsweredFor() {
        val checks = FreeUpSpaceEligibility.evaluate(listOf(1L, 2L), mapOf(1L to evidence()))

        assertEquals(2, checks.size)
        assertTrue(checks.first().isDeletable)
        assertEquals(RejectionReason.MissingFromIndex, checks.last().rejected)
        assertFalse("and there is no uri left to hand the deletion system", checks.last().isDeletable)
        assertNull(checks.last().candidate)
    }

    @Test
    fun aPlanThatIncludesNothingDeletableOffersNothing() {
        val checks = FreeUpSpaceEligibility.evaluate(
            listOf(1L, 2L),
            mapOf(1L to evidence(queueState = UploadState.Queued), 2L to evidence(trashed = true)),
        )

        assertTrue(checks.none { it.isDeletable })
    }

    @Test
    fun confirmationUsesOnlyWhatSurvivedTheSecondCheck() = runBlocking<Unit> {
        val repository = FakeFreeUpSpace(
            mapOf(
                1L to evidence(),
                2L to evidence(queueState = UploadState.Uploading),
                3L to evidence(cloudMessagePresent = false),
            ),
        )
        val useCase = FreeUpSpaceUseCase(repository, FakeOrganization(), SpaceLibrary())

        val prepared = useCase.prepare(listOf(1L, 2L, 3L, 4L))

        assertEquals("only the item still eligible is offered to the delete request", listOf(1L), prepared.ids)
        assertEquals(listOf("content://media/external/images/media/1"), prepared.uris)
        assertEquals(SIZE, prepared.reclaimedBytes)
        assertEquals(
            "every refusal is reported by reason, including the id that had no row at all",
            listOf(
                RejectionReason.UploadInFlight,
                RejectionReason.CloudRecordGone,
                RejectionReason.MissingFromIndex,
            ),
            prepared.skipped.mapNotNull { it.rejected },
        )
        assertFalse(prepared.nothingLeft)
    }

    @Test
    fun aConfirmationThatLeftNothingEligibleAsksTheSystemForNothing() = runBlocking<Unit> {
        val repository = FakeFreeUpSpace(mapOf(2L to evidence(hasContentHash = false)))
        val organization = FakeOrganization()
        val media = SpaceLibrary()

        val useCase = FreeUpSpaceUseCase(repository, organization, media)
        val prepared = useCase.prepare(listOf(2L))

        assertTrue("nothing was left to delete", prepared.nothingLeft)
        assertEquals(0, useCase.complete(prepared))
        assertEquals("nothing was reconciled, because nothing happened", 0, organization.forgotten.size)
        assertEquals(0, media.syncs)
    }

    @Test
    fun aConfirmedDeletionReconcilesTheLibraryAndLeavesTheCloudAlone() = runBlocking<Unit> {
        val repository = FakeFreeUpSpace(mapOf(1L to evidence(1L), 5L to evidence(5L)))
        val organization = FakeOrganization()
        val media = SpaceLibrary()
        val useCase = FreeUpSpaceUseCase(repository, organization, media)
        val prepared = useCase.prepare(listOf(1L, 5L))
        val deleted = useCase.complete(prepared)

        assertEquals(2, deleted)
        assertEquals(setOf(1L, 5L), organization.forgotten.toSet())
        assertEquals(
            "the scan is part of the confirmation, so the timeline stops drawing a file that is gone",
            1,
            media.syncs,
        )
        assertFalse(
            "the cloud index is never touched: the message and its record survive the local file",
            repository.cleared,
        )
    }

    private fun evidence(
        id: Long = 1L,
        queueState: UploadState? = UploadState.BackedUp,
        chatId: Long = CHAT,
        messageId: Long = MESSAGE,
        hasContentHash: Boolean = true,
        cloudMessagePresent: Boolean = true,
        trashed: Boolean = false,
    ) = ItemEvidence(
        candidate = FreeUpSpaceCandidate(
            mediaStoreId = id,
            contentUri = "content://media/external/images/media/$id",
            displayName = "IMG_0001.jpg",
            sizeBytes = SIZE,
            mediaType = MediaType.Photo,
        ),
        queueState = queueState,
        telegramChatId = chatId,
        telegramMessageId = messageId,
        hasContentHash = hasContentHash,
        cloudMessagePresent = cloudMessagePresent,
        trashed = trashed,
    )

}

private const val CHAT = 7L
private const val MESSAGE = 42L
private const val SIZE = 4_096L

private class FakeFreeUpSpace(private val rows: Map<Long, ItemEvidence>) : FreeUpSpaceRepository {
    var cleared = false

    override fun observePlan(): Flow<FreeUpSpacePlan> = flowOf(FreeUpSpacePlan(rows.size, SIZE * rows.size))

    override suspend fun review(limit: Int): List<FreeUpSpaceCandidate> =
        rows.values.take(limit).map { it.candidate }

    override suspend fun recheck(ids: Collection<Long>): List<EligibilityCheck> =
        FreeUpSpaceEligibility.evaluate(ids, rows)
}

private class FakeOrganization : MediaOrganizationRepository {
    val forgotten = mutableListOf<Long>()

    override suspend fun forgetDeletedLocally(mediaStoreIds: Collection<Long>) {
        forgotten += mediaStoreIds
    }

    override fun observeCounts(): Flow<com.lumovault.app.domain.organization.SystemAlbumCounts> =
        flowOf(com.lumovault.app.domain.organization.SystemAlbumCounts(byAlbum = emptyMap()))

    override fun observeContents(
        album: com.lumovault.app.domain.model.SystemAlbum,
        limit: Int,
    ): Flow<List<Media>> = flowOf(emptyList())

    // Free Up Space asks which items are safely backed up; a folder view is not part of that question, so
    // the fake answers with nothing rather than inventing a folder.
    override fun observeLocalFolders(): Flow<List<com.lumovault.app.domain.model.LocalFolder>> =
        flowOf(emptyList())

    override fun observeBackupFolders(): Flow<List<com.lumovault.app.domain.model.LocalFolder>> =
        flowOf(emptyList())

    override fun observeLocalFolderContents(
        relativePath: String,
        limit: Int,
    ): Flow<List<Media>> = flowOf(emptyList())

    override fun observeFavoritesWithin(mediaStoreIds: Collection<Long>): Flow<Set<Long>> = flowOf(emptySet())

    override suspend fun setFavorite(mediaStoreIds: Collection<Long>, favorite: Boolean) = Unit

    override suspend fun setArchived(mediaStoreIds: Collection<Long>, archived: Boolean) = Unit

    override suspend fun moveToTrash(mediaStoreIds: Collection<Long>) = Unit

    override suspend fun restoreFromTrash(mediaStoreIds: Collection<Long>): Int = 0

    override suspend fun trashedMediaIds(): List<Long> = emptyList()

    override suspend fun trashedCount(): Int = 0
}

private class SpaceLibrary : MediaRepository {
    var syncs = 0

    override suspend fun local(mediaStoreId: Long): Media? = null

    override suspend fun sync(): SyncResult {
        syncs += 1
        return SyncResult(indexed = 0, removed = 0)
    }

    override suspend fun clear() = Unit

    override fun observeWindow(limit: Int): Flow<List<Media>> = flowOf(emptyList())

    override fun observeCount(): Flow<Int> = flowOf(0)

    override fun observeCountByType(): Flow<Map<MediaType, Int>> = flowOf(emptyMap())

    override fun observeFolders(): Flow<List<String>> = flowOf(emptyList())
}
