package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.features.metadata.data.persistence.InMemoryManifestStore
import com.lumovault.lumovault.features.metadata.data.persistence.InMemoryPartitionStore
import com.lumovault.lumovault.features.metadata.data.persistence.InMemorySyncLogStore
import com.lumovault.lumovault.features.metadata.domain.model.PartitionItem
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncServiceTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = kotlinx.coroutines.CoroutineScope(dispatcher)

    private fun services(): Triple<PartitionService, ManifestService, SyncService> {
        val partitions = PartitionService(InMemoryPartitionStore(), scope)
        val manifest = ManifestService(InMemoryManifestStore(), scope)
        val sync = SyncService(
            partitionService = partitions,
            manifestService = manifest,
            store = InMemorySyncLogStore(),
            debounceMs = 100L,
            coroutineScope = scope,
        )
        return Triple(partitions, manifest, sync)
    }

    private fun item(localId: String) = PartitionItem(
        localId = localId,
        fileHash = "h-$localId",
        createdAt = Instant.parse("2026-01-15T00:00:00.000Z"),
        modifiedAt = Instant.parse("2026-01-15T00:00:00.000Z"),
    )

    @Test
    fun `a burst of changes to one item coalesces to the last operation`() = runTest(dispatcher) {
        val (partitions, manifest, sync) = services()
        partitions.initialize()
        manifest.initialize()
        sync.initialize()

        val flushed = mutableListOf<List<com.lumovault.lumovault.features.metadata.domain.model.SyncChange>>()
        sync.flushHandler = { flushed.add(it) }

        sync.enqueueChange("a", "create")
        sync.enqueueChange("a", "update")
        sync.enqueueChange("a", "trash")

        advanceUntilIdle()

        assertEquals(1, flushed.size)
        assertEquals(1, flushed.single().size)
        assertEquals("trash", flushed.single().single().operation)
    }

    @Test
    fun `the debounce window is sliding — a continuous stream never flushes`() = runTest(dispatcher) {
        val (partitions, manifest, sync) = services()
        partitions.initialize()
        manifest.initialize()

        var flushCount = 0
        sync.flushHandler = { flushCount++ }

        repeat(5) {
            sync.enqueueChange("a", "update")
            advanceTimeBy(50L) // less than the 100 ms window, so the timer keeps re-arming
            assertEquals("no flush while the stream is running", 0, flushCount)
        }
        // Only once the stream stops does the window finally close.
        advanceUntilIdle()
        assertEquals(1, flushCount)
    }

    @Test
    fun `pendingCount reflects the raw queue, not the coalesced count`() = runTest(dispatcher) {
        val (_, _, sync) = services()
        sync.initialize()

        sync.enqueueChange("a", "create")
        sync.enqueueChange("a", "update")
        sync.enqueueChange("b", "update")

        assertEquals(3, sync.pendingCount)
        advanceUntilIdle()
        assertEquals(0, sync.pendingCount)
    }

    @Test
    fun `a failing flush re-queues the raw batch and self-retries`() = runTest(dispatcher) {
        val (partitions, manifest, sync) = services()
        partitions.initialize()
        manifest.initialize()

        var attempts = 0
        val seen = mutableListOf<List<com.lumovault.lumovault.features.metadata.domain.model.SyncChange>>()
        sync.flushHandler = {
            attempts++
            seen.add(it)
            if (attempts == 1) throw RuntimeException("channel down")
        }

        sync.enqueueChange("a", "create")
        sync.enqueueChange("a", "update")
        assertEquals(2, sync.pendingCount)

        // The flush fails; the raw batch (2 entries) goes back on the queue
        // rather than the coalesced view (1 entry).
        advanceTimeBy(100L)
        assertEquals(1, attempts)
        assertEquals(2, sync.pendingCount)

        // The retry succeeds and drains the queue.
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals(0, sync.pendingCount)
        assertEquals(1, seen[0].size)
    }

    @Test
    fun `backoff is exponential`() = runTest(dispatcher) {
        // Attempt n waits debounce * 2^(n-1): with a 100 ms debounce the gaps
        // grow 100, 200, 400 ms. Measured in virtual time.
        val (_, _, sync) = services()
        sync.initialize()
        val scheduler = testScheduler

        val attemptTimes = mutableListOf<Long>()
        var failuresLeft = 3
        sync.flushHandler = {
            attemptTimes += scheduler.currentTime
            if (failuresLeft-- > 0) throw RuntimeException("channel down")
        }

        sync.enqueueChange("a", "update")
        advanceUntilIdle()

        assertEquals(4, attemptTimes.size)
        val gaps = attemptTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(100L, 200L, 400L), gaps)
    }

    @Test
    fun `syncToTelegram uploads dirty partitions then the manifest, in order`() = runTest(dispatcher) {
        val (partitions, manifest, sync) = services()
        partitions.initialize()
        manifest.initialize()
        sync.initialize()
        partitions.upsertItem(item("a"))

        val order = mutableListOf<String>()
        var uploadedManifest: String? = null

        val count = sync.syncToTelegram(
            uploadPartition = { id, json ->
                order.add("partition:$id")
                assertTrue("partition document must serialize", json.isNotEmpty())
            },
            uploadManifest = { json ->
                order.add("manifest")
                uploadedManifest = json
            },
            allItems = { partitions.getAllPartitions().flatMap { it.items } },
            totalSizeBytes = 0L,
        )

        assertEquals(1, count)
        assertEquals(listOf("partition:2026/01", "manifest"), order)
        assertTrue(uploadedManifest!!.contains("2026/01"))
    }

    @Test
    fun `a successful sync advances the baseline so the next pass is a no-op`() = runTest(dispatcher) {
        val (partitions, manifest, sync) = services()
        partitions.initialize()
        manifest.initialize()
        sync.initialize()
        partitions.upsertItem(item("a"))

        val upload: suspend (String, String) -> Unit = { _, _ -> }
        sync.syncToTelegram(
            uploadPartition = upload,
            uploadManifest = { },
            allItems = { partitions.getAllPartitions().flatMap { it.items } },
            totalSizeBytes = 0L,
        )

        // recordSyncedPartitions ran before the manifest was serialized.
        val baseline = manifest.partitionHashes()
        assertEquals(partitions.getPartition("2026/01")!!.computeHash(), baseline["2026/01"])

        val second = sync.syncToTelegram(
            uploadPartition = upload,
            uploadManifest = { },
            allItems = { partitions.getAllPartitions().flatMap { it.items } },
            totalSizeBytes = 0L,
        )
        assertEquals("nothing dirty after a clean sync", 0, second)
    }

    @Test
    fun `syncToTelegram is re-entrant safe`() = runTest(dispatcher) {
        val (partitions, manifest, sync) = services()
        partitions.initialize()
        manifest.initialize()
        sync.initialize()
        partitions.upsertItem(item("a"))

        val doSync: suspend () -> Int = {
            sync.syncToTelegram(
                uploadPartition = { _, _ -> delay(50L) },
                uploadManifest = { },
                allItems = { partitions.getAllPartitions().flatMap { it.items } },
                totalSizeBytes = 0L,
            )
        }

        // A second call while the first is mid-flight returns 0 rather than
        // double-uploading.
        val first = async { doSync() }
        val second = async { doSync() }

        assertEquals(1, first.await() + second.await())
    }
}
