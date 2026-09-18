package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.features.metadata.data.persistence.ManifestStore
import com.lumovault.lumovault.features.metadata.domain.model.Manifest
import com.lumovault.lumovault.features.metadata.domain.model.ManifestChunk
import com.lumovault.lumovault.features.metadata.domain.model.MetadataPartition
import com.lumovault.lumovault.features.metadata.domain.model.PartitionItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Owns the library manifest and — critically — the last-synced partition hash
 * baseline that the dirty check compares against.
 *
 * Ported from lib/features/metadata/data/repositories/manifest_service.dart.
 *
 * **The baseline may only advance in three places**: [setManifest] (a remote
 * manifest was loaded), [updateAfterSync] (partitions were successfully
 * uploaded), and nowhere else. An earlier version of the Dart code wrote the
 * freshly computed hashes into the baseline inside [generateManifest], which
 * made every partition look clean the moment a change was flushed — so the sync
 * engine found nothing dirty and partition files were never re-uploaded. That
 * failure is silent: the app reports "up to date" while nothing propagates.
 */
class ManifestService(
    private val store: ManifestStore? = null,
    coroutineScope: CoroutineScope? = null,
) {
    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile private var current: Manifest? = null

    /**
     * The last-synced baseline: partition id -> content hash as of the last
     * successful upload or loaded remote manifest. This is the operational
     * "epoch" for incremental sync.
     */
    @Volatile private var partitionHashes: Map<String, String> = emptyMap()

    @Volatile var initialized = false
        private set

    private var persistJob: Job? = null

    /**
     * Loads the manifest and repopulates the baseline. Routes through
     * [setManifest] specifically so the baseline is restored by the *one* code
     * path allowed to advance it at load time.
     */
    suspend fun initialize() = mutex.withLock {
        if (initialized) return@withLock
        store?.load()?.let { setManifestInternal(it) }
        initialized = true
    }

    suspend fun getCurrentManifest(): Manifest? = mutex.withLock { current }

    /** Snapshot of the baseline; the argument [PartitionService.getDirtyPartitionIds] expects. */
    suspend fun partitionHashes(): Map<String, String> = mutex.withLock { partitionHashes }

    /**
     * Regenerates the manifest content from [items] and returns it.
     *
     * Describes *current* local state. Deliberately does **not** advance
     * [partitionHashes]: the baseline for the dirty check may only move after a
     * successful upload or when a remote manifest is loaded. Advancing it here
     * made every partition look clean the moment a change was flushed, so the
     * sync engine re-uploaded nothing.
     *
     * [created] is preserved across regenerations — it is the closest thing the
     * manifest has to an epoch.
     */
    suspend fun generateManifest(
        items: List<PartitionItem>,
        deviceHash: String,
        totalSizeBytes: Long,
        now: Instant = Instant.now(),
    ): Manifest = mutex.withLock {
        val existing = current
        val chunks = computeChunks(items)
        val manifest = Manifest(
            created = existing?.created ?: now,
            deviceHash = deviceHash,
            totalMedia = chunks.sumOf { it.count.toLong() },
            totalSizeBytes = totalSizeBytes,
            lastSync = now,
            chunks = chunks,
            schemaVersion = Manifest.CURRENT_SCHEMA_VERSION,
        )
        // The manifest document is stored so the next regeneration can carry
        // `created` forward and updateAfterSync has something to merge into.
        // Note: partitionHashes is intentionally left untouched — advancing it
        // here is the bug that made every partition look clean and suppressed
        // all re-uploads.
        current = manifest
        persist()
        manifest
    }

    /**
     * Records that [partitions] uploaded successfully at [syncTime], merging
     * their fresh chunk entries into the manifest rather than replacing the
     * chunk set — replacing it would drop every unchanged chunk and shrink
     * totalMedia on the channel to just the partitions that changed.
     *
     * Must run *after* the uploads succeed but *before* the manifest document is
     * serialized, so the manifest sent to the channel describes exactly the
     * partitions that went with it.
     */
    suspend fun recordSyncedPartitions(
        partitions: List<MetadataPartition>,
        syncTime: Instant,
    ) = mutex.withLock {
        updateAfterSyncInternal(partitions.associateBy({ it.id }, { it.computeHash() }), syncTime)
    }

    /** The merge path behind [recordSyncedPartitions], exposed for direct chunk maps. */
    suspend fun updateAfterSync(uploadedChunks: Map<String, String>, syncTime: Instant) = mutex.withLock {
        updateAfterSyncInternal(uploadedChunks, syncTime)
    }

    private fun updateAfterSyncInternal(uploadedChunks: Map<String, String>, syncTime: Instant) {
        // The baseline advances for the uploaded chunks regardless of whether a
        // manifest document exists yet — a first sync has nothing to merge into
        // but must still record what it just pushed, or the next pass re-uploads
        // everything.
        val baseline = partitionHashes.toMutableMap()
        uploadedChunks.forEach { (id, hash) -> baseline[id] = hash }
        partitionHashes = baseline

        val existing = current
        if (existing == null) {
            // No manifest to merge into; record exactly what was uploaded. The
            // counts come from the live partition set if the caller noted them.
            val chunks = uploadedChunks.map { (id, hash) ->
                ManifestChunk(id = id, count = partitionsForChunkCount[id] ?: 0, hash = hash)
            }.sortedBy { it.id }
            current = Manifest(
                created = syncTime,
                deviceHash = "",
                totalMedia = chunks.sumOf { it.count.toLong() },
                totalSizeBytes = 0L,
                lastSync = syncTime,
                chunks = chunks,
            )
            persist()
            return
        }

        val chunksById = existing.chunks.associateBy { it.id }.toMutableMap()

        // Overwrite only the uploaded ids; everything else is carried forward.
        uploadedChunks.forEach { (id, hash) ->
            val count = partitionsForChunkCount[id] ?: 0
            chunksById[id] = ManifestChunk(id = id, count = count, hash = hash)
        }

        val merged = chunksById.values.sortedBy { it.id }
        current = existing.copy(
            chunks = merged,
            totalMedia = merged.sumOf { it.count.toLong() },
            lastSync = syncTime,
        )

        persist()
    }

    /** Chunk counts by partition id, kept fresh by [notePartitionCounts]. */
    @Volatile private var partitionsForChunkCount: Map<String, Int> = emptyMap()

    /**
     * Refreshes the item counts the merge uses when writing chunk entries. The
     * manifest's `count` must reflect reality, but only the *uploaded* chunks
     * are rewritten during a merge, so counts for the rest come from here.
     */
    suspend fun notePartitionCounts(counts: Map<String, Int>) = mutex.withLock {
        partitionsForChunkCount = counts
    }

    /**
     * Replaces the manifest and baseline wholesale from a remote manifest.
     * Called on pull and on restore. Safe to apply for *all* chunks including
     * ones never pulled, because unpulled chunks had matching hashes by
     * construction — do not mutate anything between the diff and this call.
     */
    suspend fun setManifest(manifest: Manifest) = mutex.withLock {
        setManifestInternal(manifest)
    }

    private fun setManifestInternal(manifest: Manifest) {
        current = manifest
        partitionHashes = manifest.chunks.associate { it.id to it.hash }
        persist()
    }

    suspend fun toJsonString(): String? = mutex.withLock { current?.toJsonString() }

    suspend fun clear() = mutex.withLock {
        current = null
        partitionHashes = emptyMap()
        partitionsForChunkCount = emptyMap()
        store?.clear()
    }

    /** Forces any pending debounced write. */
    suspend fun saveNow() = mutex.withLock {
        persistJob?.cancel()
        persistJob = null
        flush()
    }

    fun dispose() {
        persistJob?.cancel()
        if (store != null) scope.cancel()
    }

    // ------------------------------------------------------------------ internals

    /** Groups [items] into chunks by capture month, hashing each. */
    private fun computeChunks(items: List<PartitionItem>): List<ManifestChunk> {
        return items.groupBy { MetadataPartition.partitionKeyFromDate(it.createdAt) }
            .map { (id, group) ->
                ManifestChunk(id = id, count = group.size, hash = PartitionItem.hashItems(group))
            }
            // Always sorted by id so two manifests of the same library match.
            .sortedBy { it.id }
    }

    private fun persist() {
        if (store == null) return
        // 300 ms debounce: a burst of manifest edits coalesces into one write.
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            flush()
        }
    }

    private fun flush() {
        current?.let { store?.save(it) }
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 300L
    }
}
