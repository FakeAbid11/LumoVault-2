package com.lumovault.app.data.repository

import com.lumovault.app.data.local.organization.FakeMediaOrganizationDao
import com.lumovault.app.data.local.organization.FakeSystemAlbumDao
import com.lumovault.app.data.local.organization.LocalFolderRow
import com.lumovault.app.data.local.organization.OrganizationStore
import com.lumovault.app.data.local.organization.FakeMediaRow
import com.lumovault.app.domain.model.FolderPaths
import com.lumovault.app.domain.model.LocalFolderAlbum
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.model.SystemAlbum
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device folders as albums: which folders qualify, what they are called, and what reading them must never
 * touch.
 *
 * `localFolderAlbums` is where those rules live, so it is tested as a function rather than through a
 * screen. The store-backed cases at the end exist to pin the two things the mapping cannot be trusted
 * alone with: that "in a folder" means the folder and not its children, and that deriving a folder album
 * leaves the tables holding a person's own albums exactly as they were.
 */
class LocalFolderAlbumsTest {
    private fun row(path: String, count: Int, cover: String? = "content://media/$path") =
        LocalFolderRow(relativePath = path, itemCount = count, coverUri = cover)

    private fun List<LocalFolderAlbum>.paths(): List<String> = map { it.relativePath }

    @Test
    fun aFolderHoldingMediaBecomesAnAlbumNamedAfterTheFolderNotThePath() {
        val albums = localFolderAlbums(listOf(row("Pictures/WhatsApp/", 12)))

        assertEquals(1, albums.size)
        val whatsapp = albums.single()
        assertEquals("WhatsApp", whatsapp.displayName)
        assertEquals("Pictures", whatsapp.parentLabel)
        assertEquals(12, whatsapp.mediaCount)
        assertEquals("the identity is the path, whatever the card shows", "Pictures/WhatsApp/", whatsapp.relativePath)
        assertEquals("content://media/Pictures/WhatsApp/", whatsapp.coverUri)
    }

    @Test
    fun severalFoldersAreSeveralAlbumsAndTheBiggestComesFirst() {
        val albums = localFolderAlbums(
            listOf(row("Pictures/Instagram/", 4), row("Pictures/WhatsApp/", 12), row("Movies/ScreenRecordings/", 12)),
        )

        assertEquals(
            "equal counts fall to the name, so the order is the same on every run",
            listOf("Movies/ScreenRecordings/", "Pictures/WhatsApp/", "Pictures/Instagram/"),
            albums.paths(),
        )
    }

    /**
     * The deduplication rule, for all three path patterns at once.
     *
     * A folder a system album already lists would otherwise be on the same screen twice — and two cards
     * over the same files can disagree about the count the moment one view excludes something the other
     * does not.
     */
    @Test
    fun foldersTheLibraryAlreadyListsDoNotAppearAgainUnderTheirOwnName() {
        val albums = localFolderAlbums(
            listOf(
                row("DCIM/Camera/", 30),
                row("Pictures/Screenshots/", 4),
                row("Download/", 2),
                row("Download/Telegram/", 5),
                row("Pictures/WhatsApp/", 7),
            ),
        )

        // `Download/Telegram/` sits inside Downloads' own `Download/%` reach, so that album already shows
        // every one of its files.
        assertEquals(listOf("Pictures/WhatsApp/"), albums.paths())
    }

    @Test
    fun nestedFoldersUnderASystemAlbumPathAreCoveredByThatAlbum() {
        // Each case names itself, because the last failure of this rule said only `AssertionError`.
        assertTrue(
            "the middle pattern claims a folder under any parent: ${SystemAlbum.Screenshots.pathLikePattern}",
            SystemAlbum.Screenshots.covers("DCIM/Screenshots/Old/"),
        )
        assertTrue(
            "a prefix pattern claims what is filed beneath it",
            SystemAlbum.Camera.covers("DCIM/Camera/Bursts/"),
        )
        assertTrue(
            "and the same pattern claims the folder it is named after",
            SystemAlbum.Screenshots.covers("Pictures/Screenshots/"),
        )
        assertFalse(
            "a folder that merely shares a prefix is a different folder",
            SystemAlbum.Camera.covers("DCIM/CameraX/"),
        )
    }

    @Test
    fun typeAndStateAlbumsClaimNoFoldersSoAFolderOfVideosStillShowsUp() {
        val albums = localFolderAlbums(listOf(row("Movies/ScreenRecordings/", 3), row("Pictures/Camera/", 1)))

        assertEquals("Videos is a type, not a place; its clips stay in the folders that hold them", 2, albums.size)
        assertFalse(SystemAlbum.Videos.covers("Movies/ScreenRecordings/"))
        assertFalse(SystemAlbum.Favorites.covers("Pictures/Camera/"))
        assertFalse(SystemAlbum.Trash.covers("Pictures/Camera/"))
        assertFalse(SystemAlbum.RecentlyAdded.covers("Pictures/Camera/"))
    }

    @Test
    fun twoFoldersWithTheSameNameStayTwoAlbums() {
        val albums = localFolderAlbums(listOf(row("Pictures/Telegram/", 3), row("DCIM/Telegram/", 5)))

        assertEquals(
            "one album named Telegram would be a merged list that opens into neither folder",
            listOf("DCIM/Telegram/", "Pictures/Telegram/"),
            albums.paths(),
        )
        assertEquals(listOf("Telegram", "Telegram"), albums.map { it.displayName })
        assertEquals(listOf("DCIM", "Pictures"), albums.map { it.parentLabel })
    }

    @Test
    fun oneFolderIsNeverTwoAlbumsBecauseOfHowThePathWasSpelled() {
        val albums = localFolderAlbums(
            listOf(
                row("Pictures/WhatsApp", 4),
                row("Pictures/WhatsApp/", 3),
                row("Pictures//WhatsApp/", 2),
                row(" Pictures/WhatsApp/ ", 1),
            ),
        )

        assertEquals(listOf("Pictures/WhatsApp/"), albums.paths())
        assertEquals("the counts are one folder's, added", 10, albums.single().mediaCount)
    }

    @Test
    fun theRootOfAVolumeIsNotAnAlbum() {
        assertTrue(localFolderAlbums(listOf(row("/", 4), row("", 2), row("///", 1))).isEmpty())
    }

    @Test
    fun aNestedFolderIsItsOwnAlbumRatherThanPartOfItsParent() {
        val albums = localFolderAlbums(listOf(row("Pictures/WhatsApp/", 6), row("Pictures/WhatsApp/Images/", 40)))

        assertEquals(listOf("Pictures/WhatsApp/Images/", "Pictures/WhatsApp/"), albums.paths())
        assertEquals(listOf("Pictures/WhatsApp", "Pictures"), albums.map { it.parentLabel })
    }

    @Test
    fun normalizationIsIdempotentAndNamingHandlesATopLevelFolder() {
        val once = FolderPaths.normalize("Pictures//WhatsApp\\")
        assertEquals("Pictures/WhatsApp/", once)
        assertEquals("normalizing twice changes nothing", once, FolderPaths.normalize(once))
        assertEquals("DCIM", FolderPaths.displayNameOf("DCIM/"))
        assertEquals("", FolderPaths.parentOf("DCIM/"))
        assertEquals(FolderPaths.ROOT, FolderPaths.normalize("   "))
    }

    @Test
    fun theStoreGroupsByFolderAndTrashedItemsLeaveBothTheCountAndTheCard() = runBlocking {
        val store = OrganizationStore()
        val folders = FakeSystemAlbumDao(store)
        val organization = FakeMediaOrganizationDao(store)
        store.index(
            FakeMediaRow(1, relativePath = "Pictures/WhatsApp/"),
            FakeMediaRow(2, relativePath = "Pictures/WhatsApp/"),
            FakeMediaRow(3, relativePath = "Pictures/Instagram/", type = MediaType.Video),
        )

        val rows = folders.observeFolderAlbums().first()
        assertEquals(
            mapOf("Pictures/WhatsApp/" to 2, "Pictures/Instagram/" to 1),
            rows.associate { it.relativePath to it.itemCount },
        )
        assertEquals(
            "a clip is still a file in that folder",
            1,
            folders.observeFolderContents("Pictures/Instagram/", 10).first().size,
        )
        assertTrue(
            "a folder's list is its own files, not everything beneath it",
            folders.observeFolderContents("Pictures/", 10).first().isEmpty(),
        )

        organization.setArchived(1, true)
        assertEquals(
            "archiving hides a photo from the timeline, not from the folder it lives in",
            2,
            folders.observeFolderAlbums().first().first { it.relativePath == "Pictures/WhatsApp/" }.itemCount,
        )

        organization.setTrashedAt(1, 1_790_000_100L)
        assertEquals(
            1,
            folders.observeFolderAlbums().first().first { it.relativePath == "Pictures/WhatsApp/" }.itemCount,
        )

        organization.setTrashedAt(2, 1_790_000_100L)
        assertTrue(
            "a folder whose last photo was thrown away stops being an album",
            folders.observeFolderAlbums().first().none { it.relativePath == "Pictures/WhatsApp/" },
        )
        assertEquals(
            "and its own list empties with it rather than showing a file in Trash",
            0,
            folders.observeFolderContents("Pictures/WhatsApp/", 10).first().size,
        )
    }

    @Test
    fun readingFoldersLeavesTheTablesHoldingUsersOwnAlbumsExactlyAsTheyWere() = runBlocking {
        val store = OrganizationStore()
        store.index(FakeMediaRow(1, relativePath = "Pictures/WhatsApp/"))
        val before = store.albums.toMap() to store.members.toSet()

        val derived = localFolderAlbums(FakeSystemAlbumDao(store).observeFolderAlbums().first())

        assertEquals(1, derived.size)
        assertEquals(
            "a device folder is read, never registered: no row in `albums`, none in `album_media`",
            before,
            store.albums.toMap() to store.members.toSet(),
        )
    }
}
