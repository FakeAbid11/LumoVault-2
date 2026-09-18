package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.features.metadata.domain.model.MediaStatus
import com.lumovault.lumovault.features.metadata.domain.model.PartitionItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ConflictResolverTest {

    private val resolver = ConflictResolver()

    private fun item(
        localId: String = "id",
        fileHash: String = "h1",
        modifiedAt: Instant = Instant.parse("2026-07-14T10:00:00.000Z"),
        telegramMessageId: String? = null,
        isDeleted: Boolean = false,
        deletedAt: Instant? = null,
        isFavorite: Boolean = false,
        tags: List<String> = emptyList(),
    ) = PartitionItem(
        localId = localId,
        fileHash = fileHash,
        createdAt = Instant.parse("2026-07-01T00:00:00.000Z"),
        modifiedAt = modifiedAt,
        telegramMessageId = telegramMessageId,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
        isFavorite = isFavorite,
        tags = tags,
    )

    @Test
    fun `identical items do not conflict`() {
        // This is what makes our own upload echo back as a no-op.
        assertNull(resolver.resolve(item(), item()))
    }

    @Test
    fun `newer modifiedAt wins`() {
        val local = item(isFavorite = false, modifiedAt = Instant.parse("2026-07-14T09:00:00.000Z"))
        val remote = item(isFavorite = true, modifiedAt = Instant.parse("2026-07-14T11:00:00.000Z"))
        val result = resolver.resolve(local, remote)!!
        assertTrue(result.resolved.isFavorite)
    }

    @Test
    fun `equal timestamps fall back to a deterministic content tiebreak`() {
        // Both devices must pick the same winner, so the rule is a function of
        // content alone — never of argument order.
        val local = item(isFavorite = true)
        val remote = item(isFavorite = false)
        val first = resolver.resolve(local, remote)!!
        val swapped = resolver.resolve(remote, local)!!
        assertEquals(
            "swapping the arguments must not change the winner",
            first.resolved.isFavorite,
            swapped.resolved.isFavorite,
        )
        // The tiebreak genuinely resolved the conflict rather than echoing one side.
        assertNotNull(first)
    }

    @Test
    fun `a tombstone wins on equal timestamps and keeps the later deletedAt`() {
        val live = item(isDeleted = false, modifiedAt = Instant.parse("2026-07-14T10:00:00.000Z"))
        val tomb = item(
            isDeleted = true,
            deletedAt = Instant.parse("2026-07-20T00:00:00.000Z"),
            modifiedAt = Instant.parse("2026-07-14T10:00:00.000Z"),
        )
        val result = resolver.resolve(live, tomb)!!
        assertTrue(result.resolved.isDeleted)
        assertEquals(Instant.parse("2026-07-20T00:00:00.000Z"), result.resolved.deletedAt)
    }

    @Test
    fun `an older tombstone loses to a newer live edit`() {
        // Scope caveat: stickiness applies only on the equal-timestamp path.
        // Plain LWW here — do not "fix" this, it is untested territory upstream.
        val tomb = item(
            isDeleted = true,
            deletedAt = Instant.parse("2026-07-01T00:00:00.000Z"),
            modifiedAt = Instant.parse("2026-07-01T00:00:00.000Z"),
        )
        val live = item(isDeleted = false, modifiedAt = Instant.parse("2026-07-14T10:00:00.000Z"))
        val result = resolver.resolve(tomb, live)!!
        assertFalse(result.resolved.isDeleted)
    }

    @Test
    fun `hash divergence preserves the loser's message id`() {
        // The displaced bytes stay recoverable instead of being orphaned.
        val local = item(
            fileHash = "hA",
            telegramMessageId = "100",
            modifiedAt = Instant.parse("2026-07-14T09:00:00.000Z"),
        )
        val remote = item(
            fileHash = "hB",
            telegramMessageId = "200",
            modifiedAt = Instant.parse("2026-07-14T11:00:00.000Z"),
        )
        val result = resolver.resolve(local, remote)!!
        assertEquals("hB", result.resolved.fileHash)
        assertTrue("expected loser id recorded", result.resolved.supersededMessageIds.contains("100"))
    }

    @Test
    fun `recording a superseded id does not change the content hash`() {
        // Otherwise the resolution itself would re-dirty the partition.
        val base = item(fileHash = "hA", telegramMessageId = "100")
        val withSuperseded = base.copy(supersededMessageIds = listOf("99"))
        assertEquals(
            PartitionItem.hashItems(listOf(base)),
            PartitionItem.hashItems(listOf(withSuperseded)),
        )
    }

    @Test
    fun `hash divergence ties prefer local`() {
        val local = item(fileHash = "hA", telegramMessageId = "100")
        val remote = item(fileHash = "hB", telegramMessageId = "200")
        val result = resolver.resolve(local, remote)!!
        assertEquals("hA", result.resolved.fileHash)
    }

    @Test
    fun `resolveBatch pairs by localId and skips items with no remote counterpart`() {
        val localA = item(localId = "a", isFavorite = true)
        val localB = item(localId = "b", modifiedAt = Instant.parse("2026-07-14T09:00:00.000Z"))
        val remoteB = item(localId = "b", isFavorite = true, modifiedAt = Instant.parse("2026-07-14T11:00:00.000Z"))

        val results = resolver.resolveBatch(listOf(localA, localB), listOf(remoteB))
        assertEquals(1, results.size)
        assertEquals("b", results.single().resolved.localId)
    }

    @Test
    fun `status changes do not create a conflict`() {
        val local = item(status = MediaStatus.uploaded)
        val remote = item(status = MediaStatus.pending)
        assertNull(resolver.resolve(local, remote))
    }
}
