package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.features.metadata.data.persistence.PartitionStore
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Owns the in-memory partition map (sync layer 1's source of truth) and keeps
 * it persisted.
 *
 * Ported from lib/features/metadata/data/repositories/partition_service.dart.
 * The load-bearing invariants are:
 *  - membership is derived solely from [MetadataPartition.partitionKeyFromDate]
 *    of `item.createdAt`;
 *  - an item whose `createdAt` changes *moves* partitions — its stale copy must
 *    not be left behind, or one localId ends up in two partitions, both dirty,
 *    both uploaded;
 *  - a partition that empties out is deleted, so it stops reporting dirty.
 */
class PartitionService(
    private val store: PartitionStore? = null,
    coroutineScope: CoroutineScope? = null,
) {
    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val partitions = LinkedHashMap<String, MetadataPartition>()

    @Volatile var initialized = false
        private set

    private var persistJob: Job? = null

    /**
     * Hydrates the partition map. Must run before the metadata repository builds
     * its layer-1 index — an empty map here means every remote chunk looks
     * "changed" and the first sync after every cold start becomes a full one.
     */
    suspend fun initialize() = mutex.withLock {
        if (initialized) return@withLock
        store?.load()?.forEach { partitions[it.id] = it }
        initialized = true
    }

    suspend fun upsertItem(item: PartitionItem, now: Instant = Instant.now()) = mutex.withLock {
        val newKey = MetadataPartition.partitionKeyFromDate(item.createdAt)

        // If the item already lives in a *different* partition (its capture date
        // was edited across a month boundary), remove the stale copy first.
        // removeFromPartition(key, ...) is used rather than removeItem()
        // because removeItem() looks the key up by localId and could return the
        // destination partition we are about to write into.
        findPartitionKey(item.localId)?.let { existingKey ->
            if (existingKey != newKey) removeFromPartition(existingKey, item.localId, now)
        }

        val existing = partitions[newKey]
        partitions[newKey] = if (existing == null) {
            MetadataPartition.forItem(newKey, item, now)
        } else {
            val replaced = existing.items.any { it.localId == item.localId }
            val items = if (replaced) {
                existing.items.map { if (it.localId == item.localId) item else it }
            } else {
                existing.items + item
            }
            existing.copy(items = items, lastModified = now)
        }
        persist()
    }

    suspend fun removeItem(localId: String, now: Instant = Instant.now()) = mutex.withLock {
        val key = findPartitionKey(localId) ?: return@withLock
        removeFromPartition(key, localId, now)
        persist()
    }

    suspend fun getPartition(id: String): MetadataPartition? = mutex.withLock { partitions[id] }

    suspend fun getAllPartitions(): List<MetadataPartition> = mutex.withLock { partitions.values.toList() }

    /**
     * Partition ids whose current content hash differs from the last-synced
     * baseline — i.e. what needs uploading. A null baseline entry means "never
     * synced", so it is dirty; the caller must always supply the manifest
     * service's baseline, never an empty map.
     */
    suspend fun getDirtyPartitionIds(manifestHashes: Map<String, String>): List<String> = mutex.withLock {
        partitions.values.mapNotNull { p ->
            val baseline = manifestHashes[p.id]
            if (baseline == null || baseline != p.computeHash()) p.id else null
        }
    }

    suspend fun serializePartition(id: String): String? = mutex.withLock {
        partitions[id]?.let { Json.encodeToString(JsonObject.serializer(), it.toJson()) }
    }

    /** Forces any pending debounced write to disk. Called at app teardown. */
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

    /** Full scan of the partition map for the partition holding [localId]. */
    private fun findPartitionKey(localId: String): String? =
        partitions.entries.firstOrNull { (_, p) -> p.items.any { it.localId == localId } }?.key

    private fun removeFromPartition(key: String, localId: String, now: Instant) {
        val partition = partitions[key] ?: return
        val remaining = partition.items.filterNot { it.localId == localId }
        if (remaining.isEmpty()) {
            // An empty partition is deleted so it stops reporting dirty and
            // stops being uploaded as a zero-item document.
            partitions.remove(key)
        } else {
            partitions[key] = partition.copy(items = remaining, lastModified = now)
        }
    }

    private fun persist() {
        if (store == null) return
        // 300 ms debounce: a burst of upserts coalesces into one file write.
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            flush()
        }
    }

    private fun flush() {
        store?.save(partitions.values.toList())
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 300L
    }
}
