package com.lumovault.app.ui.screens.photos

import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaAccessStatus
import com.lumovault.app.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide which Photos screen appears. Worth testing as a function rather than as a
 * rendered screen, because the distinctions are the product: "no media on this device" and "the
 * index has not been built yet" look identical to a boolean bag of flags and mean very different
 * things to the person holding the phone.
 */
class PhotosUiStateTest {
    private fun media(id: Long) = Media(
        id = id,
        contentUri = "content://media/external/file/$id",
        type = MediaType.Photo,
        mimeType = "image/jpeg",
        displayName = "IMG.jpg",
        relativePath = "DCIM/Camera/",
        sizeBytes = 1L,
        dateAddedSeconds = 1L,
        dateModifiedSeconds = 1L,
        width = 100,
        height = 100,
        durationMillis = null,
    )

    private fun derive(
        access: MediaAccessStatus,
        items: List<Media> = emptyList(),
        total: Int = items.size,
        scanning: Boolean = false,
        failed: Boolean = false,
    ) = derivePhotosState(
        access = access,
        items = items,
        totalCount = total,
        isScanning = scanning,
        scanFailed = failed,
        days = emptyList(),
    )

    @Test
    fun `an unread permission is neither granted nor denied`() {
        assertEquals(PhotosUiState.CheckingAccess, derive(MediaAccessStatus.Unknown))
    }

    @Test
    fun `no access asks for it instead of reporting an empty library`() {
        assertEquals(PhotosUiState.PermissionRequired, derive(MediaAccessStatus.Denied))
        assertEquals(
            PhotosUiState.PermissionRequired,
            derive(MediaAccessStatus.Denied, failed = true),
        )
    }

    @Test
    fun `the first scan is distinguishable from a device with no media`() {
        assertEquals(PhotosUiState.Scanning, derive(MediaAccessStatus.Granted, scanning = true))
        assertEquals(PhotosUiState.Empty, derive(MediaAccessStatus.Granted))
    }

    @Test
    fun `a failed first scan is an error, not an empty library`() {
        assertEquals(
            PhotosUiState.Failure(PhotosUiState.Failure.Reason.ScanFailed),
            derive(MediaAccessStatus.Granted, failed = true),
        )
    }

    @Test
    fun `an indexed library keeps showing while a rescan runs`() {
        val state = derive(
            access = MediaAccessStatus.Granted,
            items = listOf(media(1), media(2)),
            scanning = true,
        ) as PhotosUiState.Content

        assertTrue(state.isRefreshing)
        assertEquals(2, state.indexedCount)
    }

    @Test
    fun `a window smaller than the index reports more to load`() {
        val partial = derive(
            access = MediaAccessStatus.Granted,
            items = listOf(media(1)),
            total = 4_200,
        ) as PhotosUiState.Content

        assertTrue(partial.hasMoreToLoad)

        val complete = derive(
            access = MediaAccessStatus.Granted,
            items = listOf(media(1)),
            total = 1,
        ) as PhotosUiState.Content

        assertFalse(complete.hasMoreToLoad)
    }

    @Test
    fun `selected-photos access is flagged so the grid can say so`() {
        val state = derive(
            access = MediaAccessStatus.PartiallyGranted,
            items = listOf(media(1)),
        ) as PhotosUiState.Content

        assertTrue(state.limitedAccess)
    }
}
