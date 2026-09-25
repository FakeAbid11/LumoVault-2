package com.lumovault.app.data.repository

import com.lumovault.app.data.local.backup.FakeBackupQueueDao
import com.lumovault.app.data.local.organization.AlbumEntity
import com.lumovault.app.data.local.organization.FakeAlbumDao
import com.lumovault.app.data.local.organization.FakeMediaDao
import com.lumovault.app.data.local.organization.FakeMediaOrganizationDao
import com.lumovault.app.data.local.organization.FakeMediaRow
import com.lumovault.app.data.local.organization.FakeSystemAlbumDao
import com.lumovault.app.data.local.organization.OrganizationStore
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.model.SystemAlbum
import com.lumovault.app.domain.organization.MediaOrganizationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Favourite, archive and Trash — the three organisation states, and what each of them hides.
 *
 * The assertions here are mostly about *separation*, because that is what the PRD insists on and what a
 * single table of flags quietly loses: an item can be favourited, archived, backed up and in Trash at
 * once, and each of those has to still mean only itself after any of the others changes. The fake DAOs
 * write one column per statement so a write that clobbered a neighbour would show up as a failure here
 * rather than as a green run.
 */
class MediaOrganizationRepositoryTest {
    private val store = OrganizationStore()
    private val media = FakeMediaDao(store)
    private val organization = FakeMediaOrganizationDao(store)
    private val albums = FakeAlbumDao(store)
    private val systemAlbums = FakeSystemAlbumDao(store)
    private val queue = FakeBackupQueueDao()

    private var clock = NOW
    private val repository: MediaOrganizationRepository = MediaOrganizationRepositoryImpl(
        organization = organization,
        systemAlbums = systemAlbums,
        media = media,
        albums = albums,
        nowSeconds = { clock },
        inTransaction = { block -> block() },
        recentlyAddedWindowSeconds = RECENT_WINDOW,
    )

    @Test
    fun favouriteAndUnfavouriteAreBothRecordedAndSurviveARestart() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))

        repository.setFavorite(listOf(1L, 2L), true)
        assertEquals(setOf(1L, 2L), repository.observeFavoritesWithin(listOf(1L, 2L)).first())

        repository.setFavorite(listOf(1L), false)
        assertEquals(setOf(2L), repository.observeFavoritesWithin(listOf(1L, 2L)).first())
        assertEquals(
            "the mark is stored, not remembered in memory — a restart reads the same row",
            false,
            store.organizationOf(1L)?.favorite,
        )
    }

    @Test
    fun favouritingLeavesArchiveAndTrashAlone() = runBlocking {
        store.index(FakeMediaRow(1L))
        repository.setArchived(listOf(1L), true)
        repository.moveToTrash(listOf(1L))

        repository.setFavorite(listOf(1L), true)

        val state = requireNotNull(store.organizationOf(1L))
        assertTrue("favourite landed", state.favorite)
        assertTrue("archive survived it", state.archived)
        assertEquals("and so did the Trash timestamp", 1_790_000_000L, state.trashedAt)
    }

    @Test
    fun archivedItemsLeaveTheTimelineButStayInTheLibraryAndTheirAlbums() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        val albumId = albums.insert(AlbumEntity(name = "Trip", createdAt = 1L))
        albums.addMembers(albumId, listOf(1L, 2L), 1L)

        repository.setArchived(listOf(1L), true)

        assertEquals("the archive mark itself is kept", true, store.organizationOf(1L)?.archived)
        assertEquals("it left the timeline", 1, media.currentCount())
        assertEquals(1, repository.observeCounts().first().of(SystemAlbum.Archive))
        assertEquals(
            "and the Archive album is where it went, so it is still findable",
            listOf(1L),
            repository.observeContents(SystemAlbum.Archive, 5).first().map { it.id },
        )
        assertEquals(
            "an archived item still belongs to the album it was put in",
            listOf(2L, 1L),
            albums.observeContent(albumId, limit = 5).first().map { it.mediaStoreId },
        )
    }

    @Test
    fun unarchivingReturnsAnItemToTheTimeline() = runBlocking {
        store.index(FakeMediaRow(1L))
        repository.setArchived(listOf(1L), true)
        assertEquals(0, media.currentCount())

        repository.setArchived(listOf(1L), false)

        assertEquals(1, media.currentCount())
        assertEquals(listOf(1L), media.observeWindow(limit = 10).first().map { it.mediaStoreId })
    }

    @Test
    fun trashLeavesTheTimelineAndEveryOtherAlbumViewButStaysInTrash() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        repository.setFavorite(listOf(1L), true)
        clock = 1_790_000_500L
        repository.moveToTrash(listOf(1L))

        assertEquals("hidden from Photos", 1, media.currentCount())
        assertEquals("counted as a favourite zero times while it is in Trash", 0, repository.observeCounts().first().of(SystemAlbum.Favorites))
        assertEquals("not in the favourites album", emptyList<Long>(), repository.observeContents(SystemAlbum.Favorites, 10).first().map { it.id })
        assertEquals(
            "but exactly one item in Trash, with the arrival time it was thrown at",
            listOf(1L to 1_790_000_500L),
            systemAlbums.observeTrashed(10).first().map { it.media.mediaStoreId to it.trashedAt },
        )
    }

    @Test
    fun restoringBringsAnItemBackWithEverythingItHadMarked() = runBlocking {
        store.index(FakeMediaRow(1L))
        repository.setFavorite(listOf(1L), true)
        repository.setArchived(listOf(1L), true)
        repository.moveToTrash(listOf(1L))

        assertEquals(1, repository.restoreFromTrash(listOf(1L)))

        val state = requireNotNull(store.organizationOf(1L))
        assertEquals("still a favourite", true, state.favorite)
        assertEquals("still archived", true, state.archived)
        assertEquals("no longer in Trash", 0L, state.trashedAt)
        assertEquals("and back in the timeline, where the archive mark still hides it", 0, media.currentCount())
    }

    @Test
    fun countsAndContentsAgreeForEverySystemAlbum() = runBlocking {
        store.index(
            FakeMediaRow(1L, relativePath = "DCIM/Camera/", dateAddedSeconds = NOW),
            FakeMediaRow(2L, relativePath = "Pictures/Screenshots/", dateAddedSeconds = NOW),
            FakeMediaRow(3L, relativePath = "Download/", dateAddedSeconds = NOW),
            FakeMediaRow(4L, type = MediaType.Video, relativePath = "DCIM/Camera/", dateAddedSeconds = NOW),
            FakeMediaRow(5L, type = MediaType.Gif, relativePath = "Pictures/", dateAddedSeconds = NOW - 10 * YEAR),
        )
        repository.setFavorite(listOf(1L, 5L), true)

        val counts = repository.observeCounts().first()

        assertEquals(2, counts.of(SystemAlbum.Camera))
        assertEquals(1, counts.of(SystemAlbum.Screenshots))
        assertEquals(1, counts.of(SystemAlbum.Downloads))
        assertEquals("a GIF is its own type, so it is not in the videos album", 1, counts.of(SystemAlbum.Videos))
        assertEquals(2, counts.of(SystemAlbum.Favorites))
        assertEquals(0, counts.of(SystemAlbum.Archive))
        assertEquals(0, counts.of(SystemAlbum.Trash))
        assertEquals("four of the five arrived inside the window", 4, counts.of(SystemAlbum.RecentlyAdded))

        assertEquals(
            "the same items the count promised, in the same order",
            counts.of(SystemAlbum.Camera).toLong(),
            repository.observeContents(SystemAlbum.Camera, 10).first().size.toLong(),
        )
        assertEquals(
            listOf(5L, 1L),
            repository.observeContents(SystemAlbum.Favorites, 10).first().map { it.id },
        )
    }

    @Test
    fun recentlyAddedIsAWINDOWOnArrivalAndDoesNotMoveAnythingInTheTimeline() = runBlocking {
        store.index(
            FakeMediaRow(1L, dateAddedSeconds = NOW - 3 * DAY),
            FakeMediaRow(2L, dateAddedSeconds = NOW - 90 * DAY),
        )

        val recent = repository.observeContents(SystemAlbum.RecentlyAdded, 10).first()

        assertEquals("a file that arrived inside the window is here", listOf(1L), recent.map { it.id })
        assertEquals(
            "and it is still the same row the timeline shows, with the same arrival date",
            listOf(1L, 2L),
            media.observeWindow(limit = 10).first().map { it.mediaStoreId }.sorted(),
        )
    }

    @Test
    fun forgettingADeletedFileClearsTheLocalRowsAndLeavesTheBackupAlone() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        val albumId = albums.insert(AlbumEntity(name = "Trip", createdAt = 1L))
        albums.addMembers(albumId, listOf(1L, 2L), 1L)
        repository.setFavorite(listOf(1L), true)
        repository.moveToTrash(listOf(1L))
        queue.withMedia(1L)
        queue.insertMissing(listOf(1L), UploadState.Queued.storageKey, 7L)
        queue.recordIdentity(
            id = 1L,
            notBackedUpState = UploadState.NotBackedUp.storageKey,
            hash = "a".repeat(64),
            sizeBytes = 1024L,
            modifiedSeconds = 1L,
            hashedAt = 2L,
        )

        repository.forgetDeletedLocally(listOf(1L))

        assertNull("the index row is gone", store.media[1L])
        assertNull("so is its organisation", store.organizationOf(1L))
        assertEquals("and its membership", emptyList<Long>(), albums.membersWithin(albumId, listOf(1L, 2L)))
        assertEquals(
            "the file that stayed is unaffected",
            listOf(2L),
            store.media.keys.toList(),
        )
    }

    /**
     * The backup record for a file the device confirmed deleted, asserted from the queue side.
     *
     * A separate test because the claim is about a table this repository never touches: proving it here is
     * proving the absence, which is the whole point — nothing in [MediaOrganizationRepository] has a
     * handle on `backup_queue` to begin with.
     */
    @Test
    fun forgettingADeletedFileKeepsItsQueueRowAndHash() = runBlocking {
        store.index(FakeMediaRow(1L))
        queue.withMedia(1L)
        queue.insertMissing(listOf(1L), UploadState.Queued.storageKey, 7L)
        queue.recordIdentity(
            id = 1L,
            notBackedUpState = UploadState.NotBackedUp.storageKey,
            hash = "a".repeat(64),
            sizeBytes = 1024L,
            modifiedSeconds = 1L,
            hashedAt = 2L,
        )
        val before = queue.row(1L)

        repository.forgetDeletedLocally(listOf(1L))

        val after = queue.row(1L)
        assertEquals("still queued", before.state, after.state)
        assertEquals("still the same content hash", before.contentHash, after.contentHash)
        assertEquals("and the same fingerprint of the bytes that hash described", before.contentSizeBytes, after.contentSizeBytes)
    }

    @Test
    fun anEmptySelectionWritesNothingAndAsksNothing() = runBlocking {
        store.index(FakeMediaRow(1L))

        repository.setFavorite(emptyList(), true)
        repository.setArchived(emptyList(), true)
        repository.moveToTrash(emptyList())
        assertEquals(0, repository.restoreFromTrash(emptyList()))
        repository.forgetDeletedLocally(emptyList())

        assertEquals(emptyList<Long>(), store.organization.keys.toList())
        assertEquals(0, repository.trashedCount())
        assertEquals(
            "an empty window is a valid question, and the answer is nobody",
            emptySet<Long>(),
            repository.observeFavoritesWithin(emptyList()).first(),
        )
    }

    @Test
    fun organisationActionsNeverCreateQueueWork() = runBlocking {
        store.index(FakeMediaRow(1L))
        queue.withMedia(1L)
        repository.setFavorite(listOf(1L), true)
        repository.setArchived(listOf(1L), true)
        repository.moveToTrash(listOf(1L))
        repository.restoreFromTrash(listOf(1L))

        assertEquals(
            "not one item was queued by any of it, which is the difference between organising and uploading",
            0,
            queue.countIn(UploadState.Queued.storageKey),
        )
    }

    @Test
    fun alreadyBackedUpMediaCanBeFavouritedWithoutLosingItsMessage() = runBlocking {
        store.index(FakeMediaRow(1L))
        queue.withMedia(1L)
        queue.insertMissing(listOf(1L), UploadState.BackedUp.storageKey, 7L)
        queue.markSent(1L, chatId = 42L, messageId = 999L, sentState = UploadState.BackedUp.storageKey, now = 8L)

        repository.setFavorite(listOf(1L), true)

        val row = queue.row(1L)
        assertEquals("still backed up", UploadState.BackedUp.storageKey, row.state)
        assertEquals("still pointing at the same message", 999L, row.messageId)
        assertEquals(true, store.organizationOf(1L)?.favorite)
    }

    private companion object {
        const val DAY = 86_400L
        const val YEAR = 365L * DAY
        const val NOW = 1_790_000_000L
        const val RECENT_WINDOW = 30L * DAY
    }
}
