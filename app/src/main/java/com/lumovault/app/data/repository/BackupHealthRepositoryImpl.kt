package com.lumovault.app.data.repository

import com.lumovault.app.data.local.backup.BackupHealthDao
import com.lumovault.app.data.local.backup.BackupQueueDao
import com.lumovault.app.data.local.backup.FreeUpSpaceDao
import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.BackupHealth
import com.lumovault.app.domain.repository.BackupHealthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Reads the numbers from the queries that already exist for them.
 *
 * The queue's counts come from [BackupQueueDao.observeCounts] — the same statement the notification's
 * progress line is built from — and Free Up Space's from [FreeUpSpaceDao.observeTotals]. That is the point of
 * this class: a health screen with its own SQL would be a second opinion about the same rows, and the two
 * would eventually disagree in front of a user.
 *
 * Unknown state keys count as failed, which is [UploadState.fromStorageKey]'s rule and is kept here rather
 * than quietly mapping to something benign. A row this build cannot name is a row it should not hide.
 */
class BackupHealthRepositoryImpl(
    private val media: MediaDao,
    private val queue: BackupQueueDao,
    private val freeUpSpace: FreeUpSpaceDao,
    private val health: BackupHealthDao,
    /**
     * The scan stamp as a flow rather than the settings store, because that store can only be built on top
     * of a real database — and the one number it contributes here is worth being testable.
     */
    private val lastScanSeconds: Flow<Long?>,
) : BackupHealthRepository {

    override fun observe(): Flow<BackupHealth> {
        // Nested three-way combines rather than one six-way: Kotlin's typed `combine` stops at five, and the
        // vararg overload that covers six hands back an `Array<*>` and loses every type in it — a mistake this
        // file would compile through and then mis-read at runtime.
        val local = combine(
            media.observeCount(),
            queue.observeCounts(),
            health.observeCloudOnlyCount(),
        ) { total, counts, cloudOnly ->
            LocalState(total = total, byState = counts.associate { it.stateKey to it.itemCount }, cloudOnly = cloudOnly)
        }

        val remote = combine(
            freeUpSpace.observeTotals(UploadState.BackedUp.storageKey),
            health.observeLastBackupSeconds(UploadState.BackedUp.storageKey),
            lastScanSeconds,
        ) { reclaimable, lastBackup, scan ->
            RemoteState(
                reclaimableCount = reclaimable.itemCount,
                reclaimableBytes = reclaimable.totalBytes,
                lastBackupSeconds = lastBackup,
                lastScanSeconds = scan,
            )
        }

        return combine(local, remote) { localState, remoteState ->
            localState.toHealth(remoteState)
        }
    }

    private data class LocalState(val total: Int, val byState: Map<String, Int>, val cloudOnly: Int) {
        fun toHealth(remote: RemoteState) = BackupHealth(
            localTotal = total,
            backedUp = byState[UploadState.BackedUp.storageKey] ?: 0,
            waiting = (byState[UploadState.Queued.storageKey] ?: 0) +
                (byState[UploadState.NotBackedUp.storageKey] ?: 0),
            uploading = (byState[UploadState.Preparing.storageKey] ?: 0) +
                (byState[UploadState.Uploading.storageKey] ?: 0),
            failed = byState.entries
                .filter { UploadState.fromStorageKey(it.key) == UploadState.Failed }
                .sumOf { it.value },
            cloudOnly = cloudOnly,
            reclaimableCount = remote.reclaimableCount,
            reclaimableBytes = remote.reclaimableBytes,
            lastBackupSeconds = remote.lastBackupSeconds,
            lastScanSeconds = remote.lastScanSeconds,
        )
    }

    private data class RemoteState(
        val reclaimableCount: Int,
        val reclaimableBytes: Long,
        val lastBackupSeconds: Long?,
        val lastScanSeconds: Long?,
    )
}
