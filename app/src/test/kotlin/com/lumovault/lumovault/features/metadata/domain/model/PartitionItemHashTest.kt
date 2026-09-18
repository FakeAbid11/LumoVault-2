package com.lumovault.lumovault.features.metadata.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PartitionItemHashTest {

    private val t0 = Instant.parse("2026-07-14T10:00:00.00Z")
    private val t1 = Instant.parse("2026-07-14T11:00:00.00Z")

    private fun item(
        localId: String = "abc",
        fileHash: String = "deadbeef",
        createdAt: Instant = t0,
        modifiedAt: Instant = t1,
        telegramMessageId: String? = null,
        telegramFileId: String? = null,
        backedUpAt: Instant? = null,
        status: MediaStatus = MediaStatus.pending,
        supersededMessageIds: List<String> = emptyList(),
        isFavorite: Boolean = false,
        tags: List<String> = emptyList(),
        aiLabels: List<String> = emptyList(),
    ) = PartitionItem(
        localId = localId,
        fileHash = fileHash,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        telegramMessageId = telegramMessageId,
        telegramFileId = telegramFileId,
        backedUpAt = backedUpAt,
        status = status,
        supersededMessageIds = supersededMessageIds,
        isFavorite = isFavorite,
        tags = tags,
        aiLabels = aiLabels,
    )

    @Test
    fun `identical items hash identically`() {
        assertEquals(
            PartitionItem.hashItems(listOf(item())),
            PartitionItem.hashItems(listOf(item())),
        )
    }

    @Test
    fun `hash is a 64-char lowercase hex sha256`() {
        val hash = PartitionItem.hashItems(listOf(item()))
        assertTrue("got: $hash", Regex("^[0-9a-f]{64}$").matches(hash))
    }

    @Test
    fun `telegram pointers do not affect the hash`() {
        // If these participated, a freshly-synced item would re-dirty its own
        // partition and force a redundant re-upload.
        assertEquals(
            PartitionItem.hashItems(listOf(item())),
            PartitionItem.hashItems(
                listOf(
                    item(
                        telegramMessageId = "999",
                        telegramFileId = "fid",
                        backedUpAt = t1,
                        status = MediaStatus.uploaded,
                        supersededMessageIds = listOf("888"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `a hashed field change changes the hash`() {
        val base = PartitionItem.hashItems(listOf(item()))

        assertNotEquals(base, PartitionItem.hashItems(listOf(item(isFavorite = true))))
        assertNotEquals(base, PartitionItem.hashItems(listOf(item(fileHash = "cafebabe"))))
        assertNotEquals(base, PartitionItem.hashItems(listOf(item(modifiedAt = t0))))
        assertNotEquals(base, PartitionItem.hashItems(listOf(item(tags = listOf("kitten")))))
        assertNotEquals(base, PartitionItem.hashItems(listOf(item(aiLabels = listOf("cat")))))
    }

    @Test
    fun `hash is order-insensitive`() {
        val a = item(localId = "a", createdAt = t0)
        val b = item(localId = "b", createdAt = t1)
        assertEquals(
            PartitionItem.hashItems(listOf(a, b)),
            PartitionItem.hashItems(listOf(b, a)),
        )
    }

    @Test
    fun `tag and label order within an item does not affect the hash`() {
        assertEquals(
            PartitionItem.hashItems(listOf(item(tags = listOf("a", "b")))),
            PartitionItem.hashItems(listOf(item(tags = listOf("b", "a")))),
        )
    }

    @Test
    fun `distinct field values cannot collide through the separators`() {
        // The control-character separators exist precisely so that joining
        // fields cannot let two different items serialize to the same bytes.
        assertNotEquals(
            PartitionItem.hashItems(listOf(item(fileHash = "ab", createdAt = t0))),
            PartitionItem.hashItems(listOf(item(fileHash = "a", createdAt = Instant.parse("2026-07-01T00:00:00.00Z")))),
        )
    }

    @Test
    fun `areIdentical is keyed on the content digest`() {
        assertTrue(PartitionItem.areIdentical(item(), item()))
        assertFalse(PartitionItem.areIdentical(item(), item(isFavorite = true)))
        // Same content, different sync pointers: still identical.
        assertTrue(
            PartitionItem.areIdentical(
                item(),
                item(telegramMessageId = "1", status = MediaStatus.uploaded),
            ),
        )
    }

    @Test
    fun `toJson is sparse and round-trips`() {
        val source = item(
            telegramMessageId = "42",
            isFavorite = true,
            tags = listOf("trip"),
            aiLabels = listOf("beach"),
            supersededMessageIds = listOf("40"),
        ).copy(
            fileName = "IMG_1.jpg",
            fileSize = 1024,
            width = 1920,
            height = 1080,
            locationName = "Nice",
            description = "sunset",
            albumName = "Summer",
            deviceFolder = "Camera",
            isDateUserSet = true,
            status = MediaStatus.uploaded,
        )
        val parsed = PartitionItem.fromJson(source.toJson())!!
        assertEquals(source, parsed)
    }

    @Test
    fun `toJson omits defaulted fields`() {
        val json = item().toJson()
        // Always present.
        assertTrue(json.containsKey("lid"))
        assertTrue(json.containsKey("h"))
        assertTrue(json.containsKey("ct"))
        assertTrue(json.containsKey("mod"))
        assertTrue(json.containsKey("st"))
        // Written only when truthy / non-default.
        assertFalse(json.containsKey("fn"))
        assertFalse(json.containsKey("sz"))
        assertFalse(json.containsKey("w"))
        assertFalse(json.containsKey("fav"))
        assertFalse(json.containsKey("tags"))
        assertFalse(json.containsKey("smids"))
    }

    @Test
    fun `fromJson tolerates an out-of-range status ordinal`() {
        // A corrupt byte must degrade one item, not take down the partition:
        // fromJsonString gives up on a throw for the whole document.
        val source = item().toJson()
        val json = kotlinx.serialization.json.buildJsonObject {
            source.forEach { (key, value) ->
                if (key == "st") put(key, kotlinx.serialization.json.JsonPrimitive(97))
                else put(key, value)
            }
        }
        val parsed = PartitionItem.fromJson(json)
        assertEquals(MediaStatus.excluded, parsed?.status)
    }

    @Test
    fun `fromJson returns null on a malformed item`() {
        assertNull(PartitionItem.fromJson(kotlinx.serialization.json.buildJsonObject { }))
    }
}
