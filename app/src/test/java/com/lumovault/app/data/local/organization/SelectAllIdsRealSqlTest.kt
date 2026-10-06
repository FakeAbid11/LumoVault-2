package com.lumovault.app.data.local.organization

import android.app.Application
import androidx.room.Room
import com.lumovault.app.data.local.LumoVaultDatabase
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.model.SystemAlbum
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Select all's id reads beside the windows they mirror, out of a real SQLite database.
 *
 * Every `*Ids` query on [SystemAlbumDao], [MediaDao.visibleIds] and [AlbumDao.allMemberIds] claims to
 * be its windowed twin without the `LIMIT` — same joins, same `WHERE`, ids only. The fakes in this
 * package agree with themselves, which proves nothing about the SQL, so each pair is asserted here as
 * one set: whatever the grid would draw across every page is exactly what select all selects. A drift
 * would surface on a phone as a count the strip prints that does not match the rows an action touches.
 *
 * A plain [Application] for the same reason as [MigrationUpgradeTest]: the real application's
 * `onCreate` builds a dependency graph, a notification channel and a WorkManager schedule, and none
 * of it is what this test is about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SelectAllIdsRealSqlTest {
    private lateinit var database: LumoVaultDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LumoVaultDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun everyIdsQueryIsItsWindowWithoutALimit() = runBlocking {
        // Seven rows, each breaking a different rule so a mirrored `WHERE` that dropped one of them
        // changes the answer: plain, favourited, archived, favourited-and-trashed, a video, a second
        // folder, and a trashed member of that folder.
        database.mediaDao().upsertAll(
            listOf(
                FakeMediaRow(id = 101, relativePath = "DCIM/Camera/", dateAddedSeconds = 1_000).toEntity(),
                FakeMediaRow(id = 102, relativePath = "DCIM/Camera/", dateAddedSeconds = 2_000).toEntity(),
                FakeMediaRow(id = 103, relativePath = "DCIM/Camera/", dateAddedSeconds = 3_000).toEntity(),
                FakeMediaRow(id = 104, relativePath = "DCIM/Camera/", dateAddedSeconds = 4_000).toEntity(),
                FakeMediaRow(
                    id = 105,
                    type = MediaType.Video,
                    relativePath = "DCIM/Camera/",
                    dateAddedSeconds = 5_000,
                ).toEntity(),
                FakeMediaRow(id = 106, relativePath = "Pictures/Other/", dateAddedSeconds = 6_000).toEntity(),
                FakeMediaRow(id = 107, relativePath = "Pictures/Other/", dateAddedSeconds = 7_000).toEntity(),
            ),
        )
        val organization = database.mediaOrganizationDao()
        organization.setFavorite(102, true)
        organization.setFavorite(104, true)
        organization.setArchived(103, true)
        organization.setTrashedAt(104, 42)
        organization.setTrashedAt(107, 77)

        // The timeline: archived 103 and trashed 104/107 leave, whatever else they are.
        assertEquals(
            database.mediaDao().observeWindow(100).first().map { it.mediaStoreId }.toSet(),
            database.mediaDao().visibleIds().toSet(),
        )
        assertEquals(setOf(101L, 102L, 105L, 106L), database.mediaDao().visibleIds().toSet())

        val system = database.systemAlbumDao()

        // Favorites: 104 is favourited too, but trashed — the JOIN and the filter must both hold.
        assertEquals(
            system.observeFavorites(100).first().map { it.mediaStoreId }.toSet(),
            system.favoriteIds().toSet(),
        )
        assertEquals(setOf(102L), system.favoriteIds().toSet())

        assertEquals(
            system.observeArchived(100).first().map { it.mediaStoreId }.toSet(),
            system.archivedIds().toSet(),
        )
        assertEquals(setOf(103L), system.archivedIds().toSet())

        assertEquals(
            system.observeTrashed(100).first().map { it.media.mediaStoreId }.toSet(),
            system.trashedMediaIds().toSet(),
        )
        assertEquals(setOf(104L, 107L), system.trashedMediaIds().toSet())

        val video = MediaType.Video.storageKey
        assertEquals(
            system.observeByType(video, 100).first().map { it.mediaStoreId }.toSet(),
            system.byTypeIds(video).toSet(),
        )
        assertEquals(setOf(105L), system.byTypeIds(video).toSet())

        val camera = checkNotNull(SystemAlbum.Camera.pathLikePattern)
        assertEquals(
            system.observeByPath(camera, 100).first().map { it.mediaStoreId }.toSet(),
            system.byPathIds(camera).toSet(),
        )
        // Archived 103 stays: a path album reports where the files are, and only Trash leaves it.
        assertEquals(setOf(101L, 102L, 103L, 105L), system.byPathIds(camera).toSet())

        assertEquals(
            system.observeRecentlyAdded(3_000L, 100).first().map { it.mediaStoreId }.toSet(),
            system.recentlyAddedIds(3_000L).toSet(),
        )
        assertEquals(setOf(103L, 105L, 106L), system.recentlyAddedIds(3_000L).toSet())

        assertEquals(
            system.observeFolderContents("Pictures/Other/", 100).first().map { it.mediaStoreId }.toSet(),
            system.folderContentsIds("Pictures/Other/").toSet(),
        )
        assertEquals(setOf(106L), system.folderContentsIds("Pictures/Other/").toSet())

        // And the album's own pair: membership as the grid pages it, Trash filter included.
        val albumId = database.albumDao().insert(AlbumEntity(name = "Mixed", createdAt = 1))
        database.albumDao().addMembers(albumId, listOf(101L, 104L, 106L), now = 1)
        assertEquals(
            database.albumDao().observeContent(albumId, 100).first().map { it.mediaStoreId }.toSet(),
            database.albumDao().allMemberIds(albumId).toSet(),
        )
        assertEquals(setOf(101L, 106L), database.albumDao().allMemberIds(albumId).toSet())
    }
}
