package com.lumovault.app.domain.model

import com.lumovault.app.data.local.organization.like
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What makes a file belong to a system album.
 *
 * These patterns are the definition, not a description of it: the queries bind
 * [SystemAlbum.pathLikePattern] directly and the fakes interpret the same strings, so a test here pins
 * both. `MediaStore.RELATIVE_PATH` always ends in a separator, which is why the patterns end in `/%`
 * rather than `%` — the difference is whether `DCIM/CameraExtra/` counts as the camera, and it should
 * not.
 */
class SystemAlbumClassificationTest {
    private val camera = requireNotNull(SystemAlbum.Camera.pathLikePattern)
    private val screenshots = requireNotNull(SystemAlbum.Screenshots.pathLikePattern)
    private val downloads = requireNotNull(SystemAlbum.Downloads.pathLikePattern)

    @Test
    fun theCameraAlbumIsTheCameraFolderAndNothingElse() {
        assertTrue("DCIM/Camera/".like(camera))
        assertTrue("DCIM/Camera/IMG_1.jpg".like(camera))
        assertFalse("DCIM/CameraExtra/".like(camera))
        assertFalse("Pictures/Camera/".like(camera))
        assertFalse("DCIM/".like(camera))
    }

    @Test
    fun aScreenshotIsRecognisedUnderWhicheverParentItsDevicePutItUnder() {
        // Devices disagree: the AOSP default is Pictures/Screenshots, some skins use DCIM, and a
        // manufacturer root is possible, which is why this one pattern allows a leading `%`.
        assertTrue("Pictures/Screenshots/".like(screenshots))
        assertTrue("DCIM/Screenshots/".like(screenshots))
        assertTrue("Pictures/Screenshots/Screenshot_2026.png".like(screenshots))
        assertFalse("Pictures/Wallpapers/".like(screenshots))
        assertFalse("Pictures/".like(screenshots))
    }

    @Test
    fun downloadsDoesNotClaimAFolderCalledDownloads() {
        // Not pedantry: `Download/` is where Android puts downloads and `Downloads/` is a folder a user
        // may well have made themselves, and the second one is not a system category.
        assertTrue("Download/".like(downloads))
        assertFalse("Downloads/".like(downloads))
        assertFalse("Documents/Download/".like(downloads))
    }

    @Test
    fun videosIsTheVideoTypeSoAGifStaysInItsOwnCategory() {
        assertEquals(MediaType.Video, SystemAlbum.Videos.mediaType)
        assertNotEqualsVideo(MediaType.Gif)
        assertNotEqualsVideo(MediaType.Photo)
    }

    @Test
    fun onlyTheThreeStateAlbumsComeFromTheOrganisationRecord() {
        assertTrue(SystemAlbum.Favorites.isOrganized)
        assertTrue(SystemAlbum.Archive.isOrganized)
        assertTrue(SystemAlbum.Trash.isOrganized)

        listOf(SystemAlbum.Camera, SystemAlbum.Screenshots, SystemAlbum.Downloads, SystemAlbum.Videos)
            .forEach { assertFalse("${it.name} is derived from the file", it.isOrganized) }
    }

    @Test
    fun noSystemAlbumHasEditableMembership() {
        // If this ever becomes true for one of them, that album needs a real membership table behind it
        // and the detail screen's "remove from album" has to appear for it — both are deliberate changes,
        // never an accident of an enum edit.
        SystemAlbum.entries.forEach { assertFalse(it.name, it.supportsMembership) }
    }

    @Test
    fun noAlbumIsDefinedByTwoOfFolderTypeAndOrganisation() {
        // Two definitions for one album would mean two answers: an item the user moved out of
        // `Screenshots/` but kept favourited is in one album and not the other, and which list it
        // appears in would depend on which query ran last.
        SystemAlbum.entries.forEach { album ->
            assertTrue(
                "${album.name} must be defined by at most one of folder, type or the organisation record",
                album.derivations() <= 1,
            )
        }
    }

    @Test
    fun everyAlbumExceptRecentlyAddedHasExactlyOneDefinition() {
        SystemAlbum.entries.filterNot { it == SystemAlbum.RecentlyAdded }.forEach { album ->
            assertEquals("${album.name} has nothing to derive it from", 1, album.derivations())
        }
    }

    @Test
    fun recentlyAddedIsNotAFolderAlbumAndNotAnOrganisationOne() {
        // It is a window over the arrival date, which is its own kind and has no pattern to get wrong.
        assertNull(SystemAlbum.RecentlyAdded.pathLikePattern)
        assertNull(SystemAlbum.RecentlyAdded.mediaType)
        assertFalse(SystemAlbum.RecentlyAdded.isOrganized)
    }

    @Test
    fun theEightAlbumsAreAllDistinct() {
        assertEquals(8, SystemAlbum.entries.size)
        assertEquals(8, SystemAlbum.entries.map { it.name }.distinct().size)
        assertNotNull(SystemAlbum.Camera.pathLikePattern)
    }

    private fun SystemAlbum.derivations(): Int = listOf(
        pathLikePattern != null,
        mediaType != null,
        isOrganized,
    ).count { it }

    private fun assertNotEqualsVideo(type: MediaType) {
        assertFalse(
            "${type.name} is not the videos album",
            type.storageKey == SystemAlbum.Videos.mediaType?.storageKey,
        )
    }
}
