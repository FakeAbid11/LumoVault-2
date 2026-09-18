package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.features.metadata.data.persistence.SyncLogStore
import com.lumovault.lumovault.features.metadata.domain.DeviceHashProvider
import com.lumovault.lumovault.features.metadata.domain.model.MetadataPartition
import com.lumovault.lumovault.features.metadata.domain.model.PartitionItem
import com.lumovault.lumovault.features.metadata.domain.model.SyncChange
import com.lumovault.lumovault.features.metadata.domain.model.SyncLogEntity
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
 * The change queue plus the push path to Telegram.
 *
 * Ported from lib/features/metadata/data/repositories/sync_service.dart. Two
 * separate concerns share this class:
 *
 *  1. A **debounced, coalescing queue** of local changes. A burst of edits to
 *     the same item collapses to the last operation; a continuous stream never
 *     flushes at all (sliding window). Failure re-queues and self-retries on an
 *     exponential backoff without needing a new enqueue.
 *
 *  2. The **upload pass**: dirty partitions first, then the manifest, in that
 *     order, because the manifest must describe exactly what was uploaded.
 */
class SyncService(
    private val partitionService: PartitionService,
    private val manifestService: ManifestService,
    private val store: SyncLogStore? = null,
    private val deviceHashProvider: DeviceHashProvider? = null,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    coroutineScope: CoroutineScope? = null,
) {
    /** Called with the coalesced batch when the debounce window closes. */
    var flushHandler: (suspend (List<SyncChange>) -> Unit)? = null

    private val scope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val changeQueue = mutableListOf<SyncChange>()
    private val log = mutableListOf<SyncLogEntity>()

    @Volatile private var debounceJob: Job? = null

    @Volatile var pendingCount = 0
        private set

    @Volatile var lastSyncTime: Instant? = null
        private set

    @Volatile var lastError: String? = null
        private set

    @Volatile private var consecutiveFlushFailures = 0
    @Volatile private var syncInProgress = false

    suspend fun initialize() = mutex.withLock {
        store?.load()?.let { log.clear(); log.addAll(it) }
    }

    /** Enqueues a change and re-arms the debounce window. */
    suspend fun enqueueChange(mediaItemId: String, operation: String) = mutex.withLock {
        changeQueue.add(SyncChange(mediaItemId, operation))
        pendingCount = changeQueue.size
        armDebounce()
    }

    suspend fun getRecentLog(limit: Int = DEFAULT_LOG_LIMIT): List<SyncLogEntity> = mutex.withLock {
        log.takeLast(limit).reversed()
    }

    suspend fun getLogEntryCount(): Int = mutex.withLock { log.size }

    fun dispose() {
        debounceJob?.cancel()
        if (store != null) scope.cancel()
    }

    // ------------------------------------------------------------- flush queue

    /** Fires the pending batch after the debounce window. */
    private fun armDebounce() {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            processPendingChanges()
        }
    }

    private suspend fun processPendingChanges() {
        val batch: List<SyncChange>
        val coalesced: Map<String, SyncChange>
        mutex.withLock {
            if (changeQueue.isEmpty()) return
            batch = changeQueue.toList()
            changeQueue.clear()
            pendingCount = 0
            // Last change per item wins. A Map, so insertion order is preserved
            // for the log and for a stable flush order.
            coalesced = batch.associateBy { it.mediaItemId }
        }

        val handler = flushHandler
        try {
            if (handler == null) {
                // No handler wired up: still drain and log, so pendingCount and
                // the sync log stay honest rather than quietly accumulating.
                logCoalesced(coalesced, success = true)
            } else {
                handler(coalesced.values.toList())
            }
            consecutiveFlushFailures = 0
        } catch (e: Throwable) {
            consecutiveFlushFailures++
            lastError = e.message ?: e::class.simpleName
            mutex.withLock {
                // Re-queue the raw batch, NOT the coalesced list — the collapse
                // is a flush-time optimization, and re-expanding here preserves
                // the original change order for the retry.
                changeQueue.addAll(0, batch)
                pendingCount = changeQueue.size
            }
            logEntry(
                mediaItemId = "*",
                operation = "flush_failed",
                success = false,
                error = lastError,
                details = "${batch.size} change(s) re-queued; retry in ${retryBackoffMs()}ms",
            )
            persistLog()

            // Self-retry: the batch does not need a new enqueue to try again.
            scope.launch {
                delay(retryBackoffMs())
                processPendingChanges()
            }
            return
        }

        if (handler != null) logCoalesced(coalesced, success = true)
        persistLog()
    }

    private suspend fun logCoalesced(coalesced: Map<String, SyncChange>, success: Boolean) {
        // One log entry per *coalesced* change (not per raw change) — the log
        // records what was actually flushed.
        coalesced.values.forEach { change ->
            logEntry(change.mediaItemId, change.operation, success = success, error = null)
        }
    }

    private suspend fun logEntry(
        mediaItemId: String,
        operation: String,
        success: Boolean,
        error: String?,
        details: String? = null,
    ) = mutex.withLock {
        log.add(
            SyncLogEntity(
                mediaItemId = mediaItemId,
                operation = operation,
                timestamp = Instant.now(),
                details = details,
                success = success,
                error = error,
            ),
        )
        if (log.size > MAX_LOG_ENTRIES) {
            // Trim the oldest; the log is diagnostic, not authoritative.
            log.subList(0, log.size - MAX_LOG_ENTRIES).clear()
        }
    }

    /**
     * Exponential backoff for a retrying flush: `debounce * 2^(failures-1)`,
     * capped at 5 minutes. Attempt 1 waits the debounce itself.
     */
    private fun retryBackoffMs(): Long {
        val failures = consecutiveFlushFailures.coerceIn(0, MAX_BACKOFF_FAILURES)
        val shifted = (failures - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        val scaled = debounceMs * (1L shl shifted)
        return scaled.coerceAtMost(MAX_RETRY_BACKOFF_MS)
    }

    // ------------------------------------------------------------- push path

    /**
     * Uploads every dirty partition, then the manifest. Returns the number of
     * partitions uploaded.
     *
     * Ordering is deliberate and load-bearing: [ManifestService.recordSyncedPartitions]
     * runs *after* the partition uploads succeed but *before* the manifest is
     * serialized, so the manifest on the channel describes exactly the
     * partitions that went with it. Moving it into a `finally` or after the
     * manifest upload sends a manifest whose hashes lie.
     */
    suspend fun syncToTelegram(
        uploadPartition: suspend (partitionId: String, json: String) -> Unit,
        uploadManifest: suspend (manifestJson: String) -> Unit,
        allItems: suspend () -> List<PartitionItem>,
        totalSizeBytes: Long,
        now: Instant = Instant.now(),
    ): Int {
        if (syncInProgress) return 0
        syncInProgress = true
        return try {
            val baseline = manifestService.partitionHashes()
            val dirtyIds = partitionService.getDirtyPartitionIds(baseline)
            if (dirtyIds.isEmpty()) return 0

            val synced = mutableListOf<String>()
            for (partitionId in dirtyIds) {
                val json = partitionService.serializePartition(partitionId) ?: continue
                try {
                    uploadPartition(partitionId, json)
                    synced.add(partitionId)
                    logEntry(
                        mediaItemId = "partition:$partitionId",
                        operation = "partition_upload",
                        success = true,
                        error = null,
                    )
                } catch (e: Throwable) {
                    // Propagates to the outer catch, so recordSyncedPartitions
                    // is skipped and this partition stays dirty for next pass.
                    throw e
                }
            }

            // Baseline advances here and only here, for the partitions that
            // actually uploaded — before the manifest is serialized below.
            val partitions = mutableListOf<MetadataPartition>()
            for (id in synced) {
                partitionService.getPartition(id)?.let { partitions.add(it) }
            }
            manifestService.recordSyncedPartitions(partitions, syncTime = now)

            // Regenerate the manifest content from the live partition set. This
            // refreshes *content* only — the baseline above is what advanced,
            // and generateManifest deliberately does not touch it.
            val deviceHash = manifestService.getCurrentManifest()?.deviceHash
                ?: deviceHashProvider?.deviceHash().orEmpty()
            val manifest = manifestService.generateManifest(
                items = allItems(),
                deviceHash = deviceHash,
                totalSizeBytes = totalSizeBytes,
                now = now,
            )
            uploadManifest(manifest.toJsonString())

            lastSyncTime = now
            lastError = null
            mutex.withLock { pendingCount = 0 }
            synced.size
        } catch (e: Throwable) {
            lastError = e.message ?: e::class.simpleName
            logEntry(mediaItemId = "*", operation = "sync_failed", success = false, error = lastError)
            0
        } finally {
            syncInProgress = false
            persistLog()
        }
    }

    private suspend fun persistLog() {
        store?.save(mutex.withLock { log.toList() })
    }

    private companion object {
        const val DEFAULT_DEBOUNCE_MS = 5_000L
        const val DEFAULT_LOG_LIMIT = 50
        const val MAX_LOG_ENTRIES = 1000
        const val MAX_RETRY_BACKOFF_MS = 5L * 60 * 1000 // 5 minutes
        const val MAX_BACKOFF_FAILURES = 20
        const val MAX_BACKOFF_SHIFT = 20
    }
}
