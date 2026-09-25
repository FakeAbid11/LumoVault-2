package com.lumovault.app.data.repository

import androidx.room.withTransaction
import com.lumovault.app.data.local.LumoVaultDatabase
import com.lumovault.app.data.local.cloud.CloudChannelDao
import com.lumovault.app.data.local.cloud.CloudChannelEntity
import com.lumovault.app.data.local.cloud.CloudMediaDao
import com.lumovault.app.data.local.cloud.CloudMediaEntity
import com.lumovault.app.domain.model.CloudDateSource
import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.CloudTypeCount
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.repository.CloudIndexRepository
import com.lumovault.app.domain.repository.RemoteBackup
import com.lumovault.app.domain.telegram.CloudAssociation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed cloud index.
 *
 * Writes are transactional per page rather than per row: a channel with 50,000 messages arrives as
 * 500 pages, and each page is one `UPSERT … ` batch. Pruning waits for the end of the scan so an
 * interrupted walk never deletes rows it simply had not reached yet.
 */
class CloudIndexRepositoryImpl(
    private val database: LumoVaultDatabase,
    private val media: CloudMediaDao,
    private val channel: CloudChannelDao,
) : CloudIndexRepository {
    override fun observeWindow(limit: Int): Flow<List<CloudMedia>> =
        media.observeWindow(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeCount(): Flow<Int> = media.observeCount()

    override fun observeTypeCounts(): Flow<List<CloudTypeCount>> =
        media.observeTypeCounts().map { rows -> rows.map { CloudTypeCount(it.mediaType, it.itemCount) } }

    override suspend fun currentCount(): Int = media.currentCount()

    override suspend fun savePage(chatId: Long, scanId: Long, items: List<CloudMedia>) {
        if (items.isEmpty()) return
        database.withTransaction {
            media.upsertAll(items.map { it.toEntity(chatId = chatId, scanId = scanId) })
        }
    }

    override suspend fun finishScan(scanId: Long, association: CloudAssociation, prune: Boolean): Int =
        database.withTransaction {
            val removed = if (prune) media.pruneBefore(scanId) else 0
            channel.upsert(association.toEntity())
            removed
        }

    override suspend fun remoteBackupFor(contentHash: String): RemoteBackup? {
        if (contentHash.isBlank()) return null
        return media.backupFor(contentHash.lowercase())?.let { RemoteBackup(it.chatId, it.messageId) }
    }

    override suspend fun unrecognizedRemoteCount(): Int = media.unrecognizedManifestCount()

    override suspend fun association(): CloudAssociation? =
        channel.row(CloudChannelEntity.SINGLETON_ROW_ID)?.toDomain()

    override suspend fun saveAssociation(association: CloudAssociation) {
        database.withTransaction { channel.upsert(association.toEntity()) }
    }

    override suspend fun replaceAssociation(association: CloudAssociation) {
        database.withTransaction {
            media.clear()
            channel.upsert(association.toEntity())
        }
    }

    override suspend fun dropAssociation() {
        database.withTransaction {
            media.clear()
            channel.clear()
        }
    }
}

private fun CloudMediaEntity.toDomain(): CloudMedia = CloudMedia(
    messageId = messageId,
    chatId = chatId,
    type = MediaType.fromStorageKey(mediaType),
    mimeType = mimeType,
    fileName = fileName,
    sizeBytes = sizeBytes,
    dateSeconds = dateSeconds,
    dateSource = CloudDateSource.fromStorageKey(dateSource),
    width = width,
    height = height,
    durationSeconds = durationSeconds,
    remoteFileId = remoteFileId,
    previewRemoteFileId = previewRemoteFileId,
    caption = caption,
    contentHash = contentHash,
)

private fun CloudMedia.toEntity(chatId: Long, scanId: Long): CloudMediaEntity = CloudMediaEntity(
    messageId = messageId,
    chatId = chatId,
    mediaType = type.storageKey,
    mimeType = mimeType,
    fileName = fileName,
    sizeBytes = sizeBytes,
    dateSeconds = dateSeconds,
    dateSource = dateSource.storageKey,
    width = width,
    height = height,
    durationSeconds = durationSeconds,
    remoteFileId = remoteFileId,
    previewRemoteFileId = previewRemoteFileId,
    caption = caption,
    lastSeenScanId = scanId,
    contentHash = contentHash,
)

private fun CloudAssociation.toEntity(): CloudChannelEntity = CloudChannelEntity(
    chatId = chatId,
    ownerUserId = ownerUserId,
    protocolVersion = protocolVersion,
    lastScannedMessageId = lastScannedMessageId,
    lastSyncSeconds = lastSyncSeconds,
)

private fun CloudChannelEntity.toDomain(): CloudAssociation = CloudAssociation(
    chatId = chatId,
    ownerUserId = ownerUserId,
    protocolVersion = protocolVersion,
    lastScannedMessageId = lastScannedMessageId,
    lastSyncSeconds = lastSyncSeconds,
)
