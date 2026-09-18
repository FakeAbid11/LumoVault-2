package com.lumovault.lumovault.features.backup.domain

import com.lumovault.lumovault.features.backup.domain.model.UploadStatus
import com.lumovault.lumovault.features.backup.domain.model.UploadTask

/**
 * Priority calculation per the Flutter `UploadPriorityCalculator` (PRD 9.3).
 *
 *   priorityScore = fileSizeMB - (recencyScore * 10) + (retryPenalty * 50)
 *
 * Lower score = higher priority (min-heap). User-initiated uploads get a
 * -1000 bonus to jump the queue; un-attempted items outrank retries.
 */
object UploadPriorityCalculator {

    fun calculatePriority(
        fileSize: Long,
        mediaCreatedAt: Long?,
        attemptCount: Int,
        isUserInitiated: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val fileSizeMB = fileSize / (1024.0 * 1024.0)

        val daysSinceCreation = mediaCreatedAt?.let {
            ((now - it) / (1000L * 60 * 60 * 24)).toInt()
        } ?: 0
        @Suppress("unused")
        val recencyScore = (1.0 - (daysSinceCreation / 365.0)).coerceIn(0.0, 1.0)

        var score = fileSizeMB - recencyScore * 10 + attemptCount * 50.0

        if (isUserInitiated) score -= 1000

        return score.toInt()
    }

    /** Sort by priority ascending (highest priority first). */
    fun sortByPriority(tasks: List<UploadTask>): List<UploadTask> =
        tasks.sortedBy { it.priority }
}
