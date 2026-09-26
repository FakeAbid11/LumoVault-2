package com.lumovault.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** GIF is a first-class media type in this product, so the classification rule is worth pinning. */
class MediaTypeClassificationTest {
    @Test
    fun `a gif is not labelled an ordinary photo`() {
        assertEquals(MediaType.Gif, mediaTypeOf("image/gif", isVideo = false))
        assertEquals(MediaType.Gif, mediaTypeOf("  IMAGE/GIF ", isVideo = false))
    }

    @Test
    fun `stills and videos classify by their media store type`() {
        assertEquals(MediaType.Photo, mediaTypeOf("image/jpeg", isVideo = false))
        assertEquals(MediaType.Video, mediaTypeOf("video/mp4", isVideo = true))
    }

    @Test
    fun `a video stays a video when its mime type contradicts the media type column`() {
        // MediaStore rows can carry an unexpected mime string; the collection's own media type wins.
        assertEquals(MediaType.Video, mediaTypeOf("image/gif", isVideo = true))
    }

    @Test
    fun `a missing mime type falls back to photo rather than guessing a gif`() {
        assertEquals(MediaType.Photo, mediaTypeOf(null, isVideo = false))
        assertEquals(MediaType.Photo, mediaTypeOf("", isVideo = false))
    }

    @Test
    fun `storage keys round trip and an unknown key defaults to photo`() {
        MediaType.entries.forEach { type ->
            assertEquals(type, MediaType.fromStorageKey(type.storageKey))
        }
        assertEquals(MediaType.Photo, MediaType.fromStorageKey("audio"))
        assertEquals(MediaType.Photo, MediaType.fromStorageKey(null))
    }

    @Test
    fun `a relative path reads as a folder without its trailing slash`() {
        val base = Media(
            id = 1,
            contentUri = "content://media/external/file/1",
            type = MediaType.Photo,
            mimeType = "image/jpeg",
            displayName = "IMG_1.jpg",
            relativePath = "DCIM/Camera/",
            sizeBytes = 1L,
            dateAddedSeconds = 1L,
            dateModifiedSeconds = 1L,
            width = 1,
            height = 1,
            durationMillis = null,
        )

        assertEquals("DCIM/Camera", base.folder)
        assertEquals("/", base.copy(relativePath = "").folder)
    }

    @Test
    fun `dimensions of zero are reported as unknown rather than trusted`() {
        val zeroed = Media(
            id = 2,
            contentUri = "content://media/external/file/2",
            type = MediaType.Photo,
            mimeType = "image/jpeg",
            displayName = "IMG_2.jpg",
            relativePath = "Pictures/",
            sizeBytes = 1L,
            dateAddedSeconds = 1L,
            dateModifiedSeconds = 1L,
            width = 0,
            height = 0,
            durationMillis = null,
        )

        assertEquals(false, zeroed.hasDimensions)
    }
}
