package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.features.metadata.data.persistence.InMemoryManifestStore
import com.lumovault.lumovault.features.metadata.domain.model.Manifest
import com.lumovault.lumovault.features.metadata.domain.model.PartitionItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ManifestServiceTest {

    private fun item(localId: String, createdAt: String) = PartitionItem(
        localId = localId,
        fileHash = "h-$localId",
        createdAt = Instant.parse(createdAt),
        modifiedAt = Instant.parse(createdAt),
    )

    @Test
    fun `generateManifest does not advance the sync baseline`() = runTest {
        // An earlier version wrote the freshly computed hashes into the
        // baseline here, so a change flushed by the debounced queue made every
        // partition look clean and syncToTelegram re-uploaded nothing.
        val service = ManifestService(InMemoryManifestStore())
        service.initialize()

        service.generateManifest(
            items = listOf(item("a", "2026-01-15T00:00:00.000Z"), item("b", "2026-02-15T00:00:00.000Z")),
            deviceHash = "device",
        )
        assertTrue("baseline must be empty until a sync or load happens", service.partitionHashes().isEmpty())
    }

    @Test
    fun `updateAfterSync advances the baseline only for uploaded chunks`() = runTest {
        val service = ManifestService(InMemoryManifestStore())
        service.initialize()

        service.updateAfterSync(
            uploadedChunks = mapOf("2026/01" to "hash-jan"),
            syncTime = Instant.now(),
        )

        val baseline = service.partitionHashes()
        assertEquals("hash-jan", baseline["2026/01"])
        assertFalse(baseline.containsKey("2026/02"))
    }

    @Test
    fun `updateAfterSync merges chunks instead of replacing them`() = runTest {
        // Replacing the chunk set would drop every unchanged chunk and shrink
        // totalMedia on the channel to just the partitions that changed.
        val service = ManifestService(InMemoryManifestStore())
        service.initialize()
        service.setManifest(
            Manifest(
                created = Instant.parse("2026-01-01T00:00:00.000Z"),
                deviceHash = "device",
                totalMedia = 200,
                totalSizeBytes = 0,
                lastSync = null,
                chunks = listOf(
                    com.lumovault.lumovault.features.metadata.domain.model.ManifestChunk("2026/01", 100, "old-jan"),
                    com.lumovault.lumovault.features.metadata.domain.model.ManifestChunk("2026/02", 100, "old-feb"),
                ),
            ),
        )

        service.notePartitionCounts(mapOf("2026/02" to 25))
        service.updateAfterSync(
            uploadedChunks = mapOf("2026/02" to "new-feb"),
            syncTime = Instant.now(),
        )

        val manifest = service.getCurrentManifest()!!
        val chunks = manifest.chunks.associateBy { it.id }
        assertEquals("unchanged chunk must be carried forward", "old-jan", chunks["2026/01"]?.hash)
        assertEquals("new-feb", chunks["2026/02"]?.hash)
        assertEquals(125L, manifest.totalMedia)
    }

    @Test
    fun `setManifest replaces the baseline wholesale`() = runTest {
        val service = ManifestService(InMemoryManifestStore())
        service.initialize()

        val remote = Manifest(
            created = Instant.parse("2026-01-01T00:00:00.000Z"),
            deviceHash = "device",
            totalMedia = 2,
            totalSizeBytes = 0,
            lastSync = null,
            chunks = listOf(
                com.lumovault.lumovault.features.metadata.domain.model.ManifestChunk("2026/01", 1, "jan"),
                com.lumovault.lumovault.features.metadata.domain.model.ManifestChunk("2026/02", 1, "feb"),
            ),
        )
        service.setManifest(remote)

        assertEquals(mapOf("2026/01" to "jan", "2026/02" to "feb"), service.partitionHashes())
    }

    @Test
    fun `generateManifest preserves created across regenerations`() = runTest {
        val service = ManifestService(InMemoryManifestStore())
        service.initialize()
        val birth = Instant.parse("2026-03-01T00:00:00.000Z")

        service.generateManifest(items = emptyList(), deviceHash = "device", now = birth)
        service.updateAfterSync(mapOf("2026/01" to "jan"), Instant.now())
        val second = service.generateManifest(items = emptyList(), deviceHash = "device")

        assertEquals(birth, second.created)
    }

    @Test
    fun `manifest chunks are always sorted by id`() = runTest {
        val service = ManifestService(InMemoryManifestStore())
        service.initialize()

        // Deliberately fed out of chronological order.
        val manifest = service.generateManifest(
            items = listOf(
                item("b", "2026-11-15T00:00:00.000Z"),
                item("a", "2026-01-15T00:00:00.000Z"),
                item("c", "2026-05-15T00:00:00.000Z"),
            ),
            deviceHash = "device",
        )
        val ids = manifest.chunks.map { it.id }
        assertEquals(listOf("2026/01", "2026/05", "2026/11"), ids)
    }

    @Test
    fun `a manifest round-trips through its own serialization`() = runTest {
        val manifest = Manifest(
            created = Instant.parse("2026-01-01T00:00:00.000Z"),
            deviceHash = "device",
            totalMedia = 1,
            totalSizeBytes = 4096,
            lastSync = Instant.parse("2026-01-02T00:00:00.000Z"),
            chunks = listOf(com.lumovault.lumovault.features.metadata.domain.model.ManifestChunk("2026/01", 1, "jan")),
        )
        val parsed = Manifest.fromJsonString(manifest.toJsonString())
        assertNotNull(parsed)
        assertEquals(manifest.copy(), parsed!!.copy())
        assertEquals(2, parsed.schemaVersion)
    }

    @Test
    fun `a manifest with no schema version is v1 and still parses`() = runTest {
        val text = """
            {"app":"lumovault","created":"2026-01-01T00:00:00.000Z",
             "device_hash":"device","total_media":0,"total_size_bytes":0,"chunks":[]}
        """.trimIndent()
        val parsed = Manifest.fromJsonString(text)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.schemaVersion)
        assertTrue(parsed.isCompatibleWith(Manifest.CURRENT_SCHEMA_VERSION))
    }
}
