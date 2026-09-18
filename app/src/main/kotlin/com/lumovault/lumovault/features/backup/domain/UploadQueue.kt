package com.lumovault.lumovault.features.backup.domain

import com.lumovault.lumovault.features.backup.domain.model.UploadStatus
import com.lumovault.lumovault.features.backup.domain.model.UploadTask
import java.util.TreeSet

/**
 * Priority queue for pending uploads.
 *
 * Ported from the Flutter `UploadQueue`:
 * - Min-heap by priority (ties broken by task id) via a [TreeSet] comparator.
 * - O(1) secondary indexes so dedup checks and removals don't scan the queue:
 *   task ids by id, task ids grouped by media item, and a completed-count per
 *   file hash.
 * - Maintained counters so stats reads are O(1) with no recomputation.
 *
 * Concurrency: TDLib allows one upload at a time, so [nextBatch] is called
 * serially by the engine. All methods are synchronized for the pause/resume
 * paths that touch the queue from the UI.
 */
class UploadQueue(batchSize: Int = DEFAULT_BATCH_SIZE) {

    private var _batchSize: Int = batchSize.coerceAtLeast(1)

    /** How many queued tasks [nextBatch] returns at once. */
    var batchSize: Int
        get() = _batchSize
        set(value) { _batchSize = value.coerceAtLeast(1) }

    private val comparator = compareBy<UploadTask>(
        { it.priority },
        { it.id },
    )

    private val queue = TreeSet(comparator)

    private val taskIndex = HashMap<String, UploadTask>()

    /** Task IDs grouped by media item, so duplicate checks and removals don't
     * have to scan the whole queue. */
    private val tasksByMediaItem = HashMap<String, MutableSet<String>>()

    /** Completed tasks per file hash, for the dedup check. */
    private val completedByHash = HashMap<String, Int>()

    // Maintained counters — O(1) reads, no recomputation.
    var pendingCount = 0; private set
    var uploadingCount = 0; private set
    var completedCount = 0; private set
    var failedCount = 0; private set
    var pausedCount = 0; private set
    var totalBytes = 0L; private set
    var backedUpBytes = 0L; private set

    val size: Int get() = synchronized(this) { queue.size }
    val isEmpty: Boolean get() = synchronized(this) { queue.isEmpty() }

    /** Number of tasks eligible to run right now (queued, backoff elapsed). */
    fun eligibleCount(now: Long = System.currentTimeMillis()): Int = synchronized(this) {
        queue.count { it.status == UploadStatus.queued && (it.nextAttemptAt == null || it.nextAttemptAt <= now) }
    }

    fun contains(taskId: String): Boolean = synchronized(this) { taskId in taskIndex }

    fun get(taskId: String): UploadTask? = synchronized(this) { taskIndex[taskId] }

    fun tasksForMediaItem(mediaItemId: String): List<UploadTask> = synchronized(this) {
        tasksByMediaItem[mediaItemId]?.mapNotNull { taskIndex[it] } ?: emptyList()
    }

    /** Completed tasks sharing this hash, for upload-side dedup. */
    fun completedCountForHash(fileHash: String): Int = synchronized(this) {
        completedByHash[fileHash] ?: 0
    }

    /**
     * Enqueue [task], replacing any existing task with the same id.
     * Returns true if a new task was added (false on replacement).
     */
    fun enqueue(task: UploadTask): Boolean = synchronized(this) {
        val replaced = taskIndex[task.id] != null
        taskIndex[task.id]?.let { removeInternal(it) }
        index(task)
        queue.add(task)
        !replaced
    }

    /** Remove [task] from the queue and indexes. No-op if absent. */
    fun remove(task: UploadTask) = synchronized(this) {
        removeInternal(task)
        Unit
    }

    fun clear() = synchronized(this) {
        queue.clear()
        taskIndex.clear()
        tasksByMediaItem.clear()
        completedByHash.clear()
        pendingCount = 0; uploadingCount = 0; completedCount = 0
        failedCount = 0; pausedCount = 0
        totalBytes = 0L; backedUpBytes = 0L
        Unit
    }

    /**
     * The next eligible batch: queued tasks whose retry backoff has elapsed,
     * lowest priority score first.
     */
    fun nextBatch(now: Long = System.currentTimeMillis()): List<UploadTask> = synchronized(this) {
        if (queue.isEmpty()) return emptyList()
        val batch = ArrayList<UploadTask>(_batchSize)
        val it = queue.iterator()
        while (it.hasNext() && batch.size < _batchSize) {
            val task = it.next()
            if (task.status != UploadStatus.queued) continue
            if (task.nextAttemptAt != null && task.nextAttemptAt > now) continue
            batch.add(task)
        }
        batch
    }

    /** All tasks, for persistence. */
    fun snapshot(): List<UploadTask> = synchronized(this) { queue.toList() }

    // ------------------------------------------------------------- internals

    private fun index(task: UploadTask) {
        taskIndex[task.id] = task
        tasksByMediaItem.getOrPut(task.mediaItemId) { HashSet() }.add(task.id)
        if (task.status == UploadStatus.completed) {
            completedByHash[task.fileHash] = (completedByHash[task.fileHash] ?: 0) + 1
        }
        updateCountersForStatus(task.status, delta = 1)
        totalBytes += task.fileSize
        if (task.status == UploadStatus.completed) backedUpBytes += task.fileSize
    }

    /** Exact inverse of [index]. */
    private fun removeInternal(task: UploadTask) {
        if (queue.remove(task) || taskIndex[task.id] == null) {
            // Not in the tree (e.g. status changed); still must unindex by id.
            if (taskIndex[task.id] == null) return
        }
        taskIndex.remove(task.id)
        tasksByMediaItem[task.mediaItemId]?.also { ids ->
            ids.remove(task.id)
            if (ids.isEmpty()) tasksByMediaItem.remove(task.mediaItemId)
        }
        if (task.status == UploadStatus.completed) {
            completedByHash[task.fileHash]?.let { count ->
                if (count <= 1) completedByHash.remove(task.fileHash)
                else completedByHash[task.fileHash] = count - 1
            }
        }
        updateCountersForStatus(task.status, delta = -1)
        totalBytes -= task.fileSize
        if (task.status == UploadStatus.completed) backedUpBytes -= task.fileSize
    }

    private fun updateCountersForStatus(status: UploadStatus, delta: Int) {
        when (status) {
            UploadStatus.queued -> pendingCount += delta
            UploadStatus.uploading -> uploadingCount += delta
            UploadStatus.completed -> completedCount += delta
            UploadStatus.failed -> failedCount += delta
            UploadStatus.paused -> pausedCount += delta
        }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 10
    }
}
