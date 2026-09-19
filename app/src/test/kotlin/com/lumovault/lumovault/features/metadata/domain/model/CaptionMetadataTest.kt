package com.lumovault.lumovault.features.metadata.domain.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the caption wire format.
 *
 * This matters more than a typical unit test: a caption that serializes
 * differently from the Flutter app's would still round-trip *within* this app,
 * so the breakage would only show up when restoring a backup written by the
 * other client. The literal JSON below is therefore asserted by shape, not just
 * by round trip.
 */
class CaptionMetadataTest {

    private val created = Instant.parse("2026-07-15T10:00:00Z")
    private val modified = Instant.parse("2026-07-16T11:30:00Z")
    private val backedUp = Instant.parse("2026-09-19T09:00:00Z")

    private fun sample() = CaptionMetadata(
        mediaItemId = "1234",
        fileHash = "abc123",
        createdAt = created,
        modifiedAt = modified,
        backedUpAt = backedUp,
    )

    @Test
    fun `minimal caption round-trips`() {
        val parsed = CaptionMetadata.fromCaptionString(sample().toCaptionString())

        assertEquals("1234", parsed?.mediaItemId)
        assertEquals("abc123", parsed?.fileHash)
        assertEquals(created, parsed?.createdAt)
        assertEquals(modified, parsed?.modifiedAt)
        assertEquals(backedUp, parsed?.backedUpAt)
        assertEquals(CaptionMetadata.CURRENT_VERSION, parsed?.version)
    }

    @Test
    fun `optional fields are omitted when at their defaults`() {
        val json = sample().toCaptionString()

        // The original wrote only the six required keys plus any set optional.
        assertFalse(json.contains("\"mime\""))
        assertFalse(json.contains("\"sz\""))
        assertFalse(json.contains("\"fav\""))
        assertFalse(json.contains("\"hid\""))
        assertFalse(json.contains("\"x\""))
        assertTrue(json.contains("\"v\":\"1\""))
        assertTrue(json.contains("\"mid\":\"1234\""))
    }

    @Test
    fun `full caption round-trips every field`() {
        val full = sample().copy(
            mimeType = "image/jpeg",
            fileSize = 4_194_304,
            width = 4032,
            height = 3024,
            durationMs = 15_000,
            isFavorite = true,
            isHidden = true,
            isArchived = true,
            isTrashed = true,
            trashedAt = Instant.parse("2026-08-01T00:00:00Z"),
            albumName = "Holiday",
            deviceFolder = "DCIM/Camera",
            description = "sunset",
            tags = listOf("beach", "family"),
            isDateUserSet = true,
        )

        val parsed = CaptionMetadata.fromCaptionString(full.toCaptionString())

        assertEquals(full, parsed)
    }

    @Test
    fun `a foreign caption is rejected`() {
        // Not JSON at all, JSON but not an object, and JSON without our keys.
        assertNull(CaptionMetadata.fromCaptionString("just a caption"))
        assertNull(CaptionMetadata.fromCaptionString("[1,2,3]"))
        assertNull(CaptionMetadata.fromCaptionString("{}"))
        assertNull(CaptionMetadata.fromCaptionString("{\"text\":\"hello\"}"))
    }

    @Test
    fun `a caption without a media id is rejected`() {
        // A version tag alone is not enough: without an id we cannot address
        // the file, and importing it would create an unusable catalog row.
        assertNull(CaptionMetadata.fromCaptionString("{\"v\":\"1\"}"))
        assertNull(CaptionMetadata.fromCaptionString("{\"v\":\"1\",\"mid\":\"\"}"))
    }

    @Test
    fun `a caption without a parseable creation time is rejected`() {
        assertNull(
            CaptionMetadata.fromCaptionString(
                "{\"v\":\"1\",\"mid\":\"7\",\"ct\":\"not-a-date\"}",
            ),
        )
    }

    @Test
    fun `missing optional timestamps fall back to the creation time`() {
        // A partition key comes from created_at, so a caption missing mod/bu
        // must still land in the right month rather than being dropped.
        val parsed = CaptionMetadata.fromCaptionString(
            "{\"v\":\"1\",\"mid\":\"7\",\"ct\":\"2026-07-15T10:00:00Z\"}",
        )

        assertEquals(created, parsed?.createdAt)
        assertEquals(created, parsed?.modifiedAt)
        assertEquals(created, parsed?.backedUpAt)
    }

    @Test
    fun `unknown keys are ignored and preserved in custom fields`() {
        // Forward compatibility: a newer writer's extra keys must not break
        // the parse, and an explicit bag must survive.
        val parsed = CaptionMetadata.fromCaptionString(
            "{\"v\":\"1\",\"mid\":\"7\",\"ct\":\"2026-07-15T10:00:00Z\",\"x\":{\"future\":1}}",
        )

        assertTrue(parsed?.customFields?.containsKey("future") == true)
    }

    @Test
    fun `tags survive as a list and empty tags are omitted`() {
        val tagged = sample().copy(tags = listOf("a", "b"))
        assertEquals(listOf("a", "b"), CaptionMetadata.fromCaptionString(tagged.toCaptionString())?.tags)
        assertFalse(sample().toCaptionString().contains("\"tags\""))
    }
}