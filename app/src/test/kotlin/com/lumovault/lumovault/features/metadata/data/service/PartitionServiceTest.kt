package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.features.metadata.data.persistence.InMemoryPartitionStore
import com.lumovault.lumovault.features.metadata.domain.model.MetadataPartition
import com.lumovault.lumovault.features.metadata.domain.model.PartitionItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PartitionServiceTest {

    private fun item(localId: String, createdAt: String) = PartitionItem(
        localId = localId,
        fileHash = "h-$localId",
        createdAt = Instant.parse(createdAt),
        modifiedAt = Instant.parse(createdAt),
    )

    @Test
    fun `partition key is year and zero-padded month`() {
        assertEquals("2026/01", MetadataPartition.partitionKeyFromDate(Instant.parse("2026-01-31T23:59:59.999Z")))
        assertEquals("2026/12", MetadataPartition.partitionKeyFromDate(Instant.parse("2026-12-01T00:00:00.000Z")))
    }

    @Test
    fun `dateFromPartitionKey is the inverse of the key`() {
        val key = "2026/07"
        val date = MetadataPartition.dateFromPartitionKey(key)
        assertEquals("2026/07", MetadataPartition.partitionKeyFromDate(date))
    }

    @Test
    fun `nextPartitionKey rolls December into January`() {
        assertEquals("2027/01", MetadataPartition.nextPartitionKey("2026/12"))
        assertEquals("2026/08", MetadataPartition.nextPartitionKey("2026/07"))
    }

    @Test
    fun `an unparseable partition key degrades instead of throwing`() {
        assertEquals("2026/01", MetadataPartition.partitionKeyFromDate(
            MetadataPartition.dateFromPartitionKey("garbage")))
    }

    @Test
    fun `upsert assigns membership by capture month`() = runTest {
        val service = PartitionService(InMemoryPartitionStore())
        service.initialize()

        service.upsertItem(item("a", "2026-01-15T00:00:00.000Z"))
        service.upsertItem(item("b", "2026-01-20T00:00:00.000Z"))
        service.upsertItem(item("c", "2026-02-15T00:00:00.000Z"))

        val jan = service.getPartition("2026/01")
        val feb = service.getPartition("2026/02")
        assertEquals(2, jan?.items?.size)
        assertEquals(1, feb?.items?.size)
    }

    @Test
    fun `a date edit across a month boundary moves the item, leaving no stale copy`() = runTest {
        // Without the move, one localId lands in two partitions: both go dirty,
        // both upload, and a later removeItem finds only the first match.
        val service = PartitionService(InMemoryPartitionStore())
        service.initialize()

        service.upsertItem(item("a", "2026-01-31T00:00:00.000Z"))
        service.upsertItem(item("a", "2026-02-01T00:00:00.000Z"))

        val jan = service.getPartition("2026/01")
        assertEquals("January partition must be empty", 0, jan?.items?.size ?: 0)
        val feb = service.getPartition("2026/02")
        assertEquals(listOf("a"), feb?.items?.map { it.localId })
    }

    @Test
    fun `a partition that empties is deleted`() = runTest {
        // Otherwise it keeps reporting dirty and gets uploaded as an empty
        // document on every pass.
        val service = PartitionService(InMemoryPartitionStore())
        service.initialize()

        service.upsertItem(item("a", "2026-01-15T00:00:00.000Z"))
        assertNotNull(service.getPartition("2026/01"))

        service.removeItem("a")
        assertNull(service.getPartition("2026/01"))
    }

    @Test
    fun `getDirtyPartitionIds reports only partitions whose hash differs`() = runTest {
        val service = PartitionService(InMemoryPartitionStore())
        service.initialize()
        service.upsertItem(item("a", "2026-01-15T00:00:00.000Z"))
        service.upsertItem(item("b", "2026-02-15T00:00:00.000Z"))

        val janHash = service.getPartition("2026/01")!!.computeHash()
        val baseline = mapOf("2026/01" to janHash) // February never synced

        val dirty = service.getDirtyPartitionIds(baseline)
        assertEquals(listOf("2026/02"), dirty)
    }

    @Test
    fun `an empty baseline reports every partition as dirty`() = runTest {
        val service = PartitionService(InMemoryPartitionStore())
        service.initialize()
        service.upsertItem(item("a", "2026-01-15T00:00:00.000Z"))

        assertEquals(listOf("2026/01"), service.getDirtyPartitionIds(emptyMap()))
    }

    @Test
    fun `serializePartition round-trips through JSON`() = runTest {
        val service = PartitionService(InMemoryPartitionStore())
        service.initialize()
        service.upsertItem(item("a", "2026-01-15T00:00:00.000Z"))

        val text = service.serializePartition("2026/01")!!
        val parsed = MetadataPartition.fromJsonString(text)!!
        assertEquals(listOf("a"), parsed.items.map { it.localId })
        assertEquals("2026/01", parsed.id)
    }

    @Test
    fun `partitions survive a restart via the store`() = runTest {
        // The whole point of persistence: after a cold start, nothing is dirty.
        val store = InMemoryPartitionStore()
        val first = PartitionService(store)
        first.initialize()
        first.upsertItem(item("a", "2026-01-15T00:00:00.000Z"))
        first.saveNow()

        val restarted = PartitionService(store)
        restarted.initialize()
        assertEquals(1, restarted.getAllPartitions().size)

        val baseline = restarted.getAllPartitions().associate { it.id to it.computeHash() }
        assertTrue("nothing dirty after restart", restarted.getDirtyPartitionIds(baseline).isEmpty())
    }

    @Test
    fun `a genuine edit after a restart is still dirty`() = runTest {
        val store = InMemoryPartitionStore()
        val first = PartitionService(store)
        first.initialize()
        first.upsertItem(item("a", "2026-01-15T00:00:00.000Z"))
        first.saveNow()

        val baseline = first.getAllPartitions().associate { it.id to it.computeHash() }

        val restarted = PartitionService(store)
        restarted.initialize()
        restarted.upsertItem(item("a", "2026-01-15T00:00:00.000Z").copy(isFavorite = true))

        assertFalse("post-restart edit must be dirty", restarted.getDirtyPartitionIds(baseline).isEmpty())
    }
}
