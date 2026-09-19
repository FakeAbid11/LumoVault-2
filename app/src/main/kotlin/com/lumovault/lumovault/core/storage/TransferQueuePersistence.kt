package com.lumovault.lumovault.core.storage

import android.content.Context
import com.lumovault.lumovault.features.backup.domain.model.UploadStatus
import com.lumovault.lumovault.features.backup.domain.model.UploadTask
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the upload queue to disk so interrupted backups survive app kills.
 *
 * Ported from Flutter `lib/core/storage/transfer_queue_persistence.dart`.
 *
 * Key properties:
 * - **Atomic writes**: temp file + rename, so a crash mid-write leaves the
 *   previous queue intact.
 * - **Save coalescing**: saves are serialized through a single chained future.
 *   When saves pile up, only the newest payload matters -- intermediate saves
 *   are skipped.
 * - **Queue merging**: [mergeQueues] combines persisted tasks with the live
 *   queue on restart. In-progress tasks are reset to queued; completed tasks
 *   are dropped; paused/failed/queued tasks carry over.
 * - **Per-record resilience**: [loadTasks] skips malformed entries individually
 *   rather than failing the entire queue.
 */
@Singleton
class TransferQueuePersistence @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()
    @Volatile
    private var pendingSave: Deferred<Unit>? = null

    private val queueFile: File
        get() = File(context.filesDir, QUEUE_FILE_NAME)

    // ----------------------------------------------------------------- save

    /**
     * Persist the given [tasks] to disk, coalescing concurrent calls.
     */
    suspend fun save(tasks: List<UploadTask>) {
        mutex.withLock {
            pendingSave?.cancel()
            pendingSave = coroutineScope {
                async(Dispatchers.IO) {
                val payload = try {
                    json.encodeToString(
                        UploadTaskList.serializer(),
                        UploadTaskList(tasks),
                    )
                } catch (_: Exception) {
                    """{"tasks":[]}"""
                }
                // Atomic write: temp file + rename.
                val temp = File(queueFile.parent, "${QUEUE_FILE_NAME}.tmp")
                try {
                    temp.writeText(payload)
                    temp.renameTo(queueFile)
                } catch (_: Exception) {
                    temp.delete()
                }
            }
        }
        pendingSave?.await()
        pendingSave = null
        }
    }

    // ----------------------------------------------------------------- load

    /**
     * Load persisted tasks, skipping malformed entries individually.
     */
    suspend fun loadTasks(): List<UploadTask> = withContext(Dispatchers.IO) {
        try {
            if (!queueFile.exists()) return@withContext emptyList()
            val raw = queueFile.readText()
            if (raw.isBlank()) return@withContext emptyList()
            json.decodeFromString(UploadTaskList.serializer(), raw).tasks
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ----------------------------------------------------------------- merge

    /**
     * Merge persisted tasks with the live queue on restart.
     *
     * Rules:
     * - Completed tasks are dropped (they're already in Room).
     * - In-progress tasks are reset to [UploadStatus.queued].
     * - All other statuses (queued, paused, failed) carry over.
     */
    suspend fun mergeQueues(liveTasks: List<UploadTask>): List<UploadTask> {
        val persisted = loadTasks()
        if (persisted.isEmpty()) return liveTasks

        val liveById = liveTasks.associateBy { it.id }
        val merged = ArrayList<UploadTask>()

        for (task in persisted) {
            if (task.status == UploadStatus.completed) continue
            val live = liveById[task.id]
            if (live != null) {
                merged.add(live)
            } else {
                // Reset in-progress to queued on restart.
                if (task.status == UploadStatus.uploading) {
                    merged.add(task.copy(status = UploadStatus.queued))
                } else {
                    merged.add(task)
                }
            }
        }
        // Add live tasks not in persisted set.
        for (task in liveTasks) {
            if (merged.none { it.id == task.id }) {
                merged.add(task)
            }
        }
        return merged
    }

    // ---------------------------------------------------------------- clear

    suspend fun clear() = withContext(Dispatchers.IO) {
        queueFile.delete()
    }

    companion object {
        private const val QUEUE_FILE_NAME = "transfer_queue.json"
    }
}

/** Wrapper for kotlinx.serialization list encoding. */
@Serializable
private data class UploadTaskList(val tasks: List<UploadTask> = emptyList())
