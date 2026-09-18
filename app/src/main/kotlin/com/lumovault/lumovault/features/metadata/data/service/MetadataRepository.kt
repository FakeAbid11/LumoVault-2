package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.features.metadata.domain.model.Manifest
import com.lumovault.lumovault.features.metadata.domain.model.PartitionItem
import com.lumovault.lumovault.features.metadata.domain.model.SyncChange
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant

/**
 * Layer 1 of the metadata system: the in-memory index of every item's synced
 * state, hydrated from the persisted partition set at boot, and the orchestrator
 * that wires partition/manifest/sync services together.
 *
 * Ported from lib/features/metadata/data/repositories/metadata_repository.dart.
 *
 * **Wiring is in the constructor, not [initialize]**: the flush handler is
 * installed eagerly so the debounced change queue always has a drain by the
 * time anything can enqueue into it.
 */
class MetadataRepository(
    private val partitionService: PartitionService,
    private val manifestService: ManifestService,
    private val syncService: SyncService,
    private val migrationService: MigrationService = MigrationService(),
) {
    private val _changeEvents = MutableSharedFlow<MetadataChangeEvent>(extraBufferCapacity = 64)
    val changeEvents: SharedFlow<MetadataChangeEvent> = _changeEvents.asSharedFlow()

    // Layer 1: localId -> synced-state item.
    private val localMetadata = LinkedHashMap<String, PartitionItem>()

    val totalItems: Int get() = synchronized(localMetadata) { localMetadata.size }

    init {
        // Installed eagerly: the queue must have a drain before any enqueue.
        syncService.flushHandler = ::flushChanges
    }

    /** Boot order is a hard dependency — see the class doc of each service. */
    suspend fun initialize() {
        partitionService.initialize()
        manifestService.initialize()
        syncService.initialize()
        loadLocalMetadata()
    }

    private suspend fun loadLocalMetadata() {
        synchronized(localMetadata) { localMetadata.clear() }
        partitionService.getAllPartitions().forEach { partition ->
            partition.items.forEach { item ->
                synchronized(localMetadata) { localMetadata[item.localId] = item }
            }
        }
    }

    suspend fun getAllMetadata(): List<PartitionItem> =
        synchronized(localMetadata) { localMetadata.values.toList() }

    suspend fun getMetadata(localId: String): PartitionItem? =
        synchronized(localMetadata) { localMetadata[localId] }

    // ------------------------------------------------------------- recording

    /**
     * A newly discovered scan item.
     *
     * Resurrection guard: a permanently deleted file stays on disk, so the next
     * scan re-discovers it. Overwriting its tombstone with a live item would
     * push that resurrection to every other device, so a tombstoned item is
     * left untouched.
     */
    suspend fun recordNewItem(item: PartitionItem) {
        val existing = getMetadata(item.localId)
        if (existing != null && existing.isDeleted) return
        upsertAndEnqueue(item, operation = "scan_discover")
    }

    /**
     * A state change (favorite, hidden, archive, trash, tags, date, label...).
     *
     * The incoming snapshot may be *stale* — e.g. the viewer's pre-upload copy —
     * so the telegram pointers from the item we already have are merged onto it
     * rather than being overwritten. Without this, a state change arriving with
     * a stale snapshot nulls out the message id and orphans the channel
     * reference with no later change to repair it.
     */
    suspend fun recordStateChange(item: PartitionItem, operation: String) {
        val existing = getMetadata(item.localId)
        val effective = if (existing != null) {
            item.copy(
                telegramMessageId = item.telegramMessageId ?: existing.telegramMessageId,
                telegramFileId = item.telegramFileId ?: existing.telegramFileId,
                backedUpAt = item.backedUpAt ?: existing.backedUpAt,
                supersededMessageIds = item.supersededMessageIds.ifEmpty { existing.supersededMessageIds },
            )
        } else {
            item
        }
        upsertAndEnqueue(effective, operation = operation)
    }

    /**
     * A deletion.
     *
     * Two distinct semantics, and confusing them breaks multi-device sync:
     *  - `operation == "delete"` (user permanent delete) writes a **tombstone**:
     *    the item stays in the map and its partition, freshly timestamped so it
     *    wins last-write-wins everywhere else. It is dropped from the search
     *    index only.
     *  - anything else, notably `scan_delete`, is a **local removal with no
     *    tombstone** — a file disappearing from this device must not revoke the
     *    backup on every other device.
     */
    suspend fun recordDeletion(localId: String, operation: String) {
        val existing = getMetadata(localId)
        if (operation == "delete" && existing != null) {
            val now = Instant.now()
            val tombstone = existing.copy(
                isDeleted = true,
                deletedAt = now,
                // Both timestamps: deletedAt for the record, modifiedAt so the
                // tombstone wins last-write-wins on other devices.
                modifiedAt = now,
            )
            upsertAndEnqueue(tombstone, operation = operation)
        } else {
            synchronized(localMetadata) { localMetadata.remove(localId) }
            partitionService.removeItem(localId)
            // Note: deliberately no search-index removal here — indexing is the
            // gallery layer's concern, and it observes [changeEvents].
        }
    }

    /**
     * An upload landed. Resurrection guard: an upload completing *after* a
     * delete leaves the orphaned message on the channel rather than restoring
     * the item here.
     */
    suspend fun recordUploadComplete(
        localId: String,
        telegramMessageId: String,
        telegramFileId: String,
    ) {
        val existing = getMetadata(localId) ?: return
        if (existing.isDeleted) return
        val updated = existing.copy(
            telegramMessageId = telegramMessageId,
            telegramFileId = telegramFileId,
            backedUpAt = Instant.now(),
        )
        upsertAndEnqueue(updated, operation = "uploaded")
        // Note: no changeStream event is emitted for this path in the original —
        // it enqueues a sync change but does not notify reactive listeners.
    }

    private suspend fun upsertAndEnqueue(item: PartitionItem, operation: String) {
        synchronized(localMetadata) { localMetadata[item.localId] = item }
        partitionService.upsertItem(item)
        syncService.enqueueChange(item.localId, operation)
        _changeEvents.tryEmit(MetadataChangeEvent(operation, item.localId))
    }

    // ------------------------------------------------------------- flush

    /**
     * Drains a coalesced batch: regenerates manifest content hashes so the dirty
     * check is meaningful, then announces *only* if something is actually dirty.
     *
     * Announcing unconditionally would make the backup layer re-upload the
     * manifest even when nothing changed.
     */
    private suspend fun flushChanges(changes: List<SyncChange>) {
        if (changes.isEmpty()) return
        val deviceHash = manifestService.getCurrentManifest()?.deviceHash
        if (deviceHash != null) {
            generateManifest(deviceHash)
        }
        if (getDirtyPartitions().isEmpty()) return

        val mediaItemId = if (changes.size != 1) "*" else changes.single().mediaItemId
        _changeEvents.tryEmit(MetadataChangeEvent("sync_pending", mediaItemId))
    }

    // ------------------------------------------------------------- manifest

    suspend fun getCurrentManifest(): Manifest? = manifestService.getCurrentManifest()

    suspend fun generateManifest(
        deviceHash: String,
        totalSizeBytes: Long = 0L,
    ): Manifest {
        manifestService.notePartitionCounts(
            partitionService.getAllPartitions().associate { it.id to it.items.size },
        )
        return manifestService.generateManifest(
            items = getAllMetadata(),
            deviceHash = deviceHash,
            totalSizeBytes = totalSizeBytes,
        )
    }

    suspend fun getDirtyPartitions(): List<String> =
        partitionService.getDirtyPartitionIds(manifestService.partitionHashes())

    // ------------------------------------------------------------- reconcile

    /**
     * Pull path: diff the remote manifest against local partitions, resolve
     * conflicts per item, adopt remote-only items, then advance the baseline to
     * the remote description.
     *
     * Returns the number of upserts + deletions applied.
     */
    suspend fun reconcileFromTelegram(
        downloadManifest: suspend () -> String?,
        downloadPartition: suspend (partitionId: String) -> String?,
    ): Int {
        val remoteText = downloadManifest() ?: return 0
        val downloaded = Manifest.fromJsonString(remoteText) ?: return 0

        // A manifest we cannot migrate is refused: keep the local baseline
        // rather than writing a half-upgraded one.
        val remoteManifest = migrationService.migrateManifest(downloaded) ?: return 0

        val toPull = mutableListOf<String>()
        remoteManifest.chunks.forEach { chunk ->
            val local = partitionService.getPartition(chunk.id)
            if (local?.computeHash() != chunk.hash) toPull.add(chunk.id)
        }

        val resolver = ConflictResolver()
        var upserts = 0
        var deletions = 0

        for (partitionId in toPull) {
            val partitionText = downloadPartition(partitionId) ?: continue
            val remotePartition = com.lumovault.lumovault.features.metadata.domain.model.MetadataPartition
                .fromJsonString(partitionText) ?: continue

            val localIds = HashSet<String>()
            val localItems = partitionService.getPartition(partitionId)?.items.orEmpty().also { items ->
                items.forEach { localIds.add(it.localId) }
            }

            resolver.resolveBatch(localItems, remotePartition.items).forEach { resolved ->
                applyReconciledItem(resolved.resolved)
                if (resolved.resolved.isDeleted) deletions++ else upserts++
            }

            // Remote-only items are adopted wholesale — *including tombstones*,
            // which is how a delete reaches this device. Absence from a remote
            // partition document is NOT a deletion; only tombstones are.
            remotePartition.items.forEach { remoteItem ->
                if (remoteItem.localId !in localIds) {
                    applyReconciledItem(remoteItem)
                    if (remoteItem.isDeleted) deletions++ else upserts++
                }
            }

            // Local-only items are deliberately not touched.
        }

        // Advance the baseline to the remote description, for *all* chunks
        // including ones never pulled — safe only because unpulled chunks had
        // matching hashes by construction. Do not mutate anything between the
        // diff above and this call.
        manifestService.setManifest(remoteManifest)

        if (upserts + deletions > 0) {
            _changeEvents.tryEmit(MetadataChangeEvent("reconciled", "*", upserts, deletions))
        }
        return upserts + deletions
    }

    private suspend fun applyReconciledItem(item: PartitionItem) {
        // A tombstoned winner stays in the partition (so it re-syncs and
        // survives a restore) but is reported as a deletion, not an upsert.
        synchronized(localMetadata) { localMetadata[item.localId] = item }
        partitionService.upsertItem(item)
    }
}

/** One recorded metadata change. */
data class MetadataChangeEvent(
    val operation: String,
    val mediaItemId: String,
    val upsertCount: Int = 0,
    val deletionCount: Int = 0,
)
