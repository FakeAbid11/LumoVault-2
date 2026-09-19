package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.core.database.dao.MediaDao
import com.lumovault.lumovault.core.tdlib.TdLibConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataSyncCoordinator @Inject constructor(
    private val metadataRepository: MetadataRepository,
    private val syncService: SyncService,
    private val uploader: TelegramMetadataUploader,
    private val downloader: TelegramMetadataDownloader,
    private val channelUpdateListener: ChannelUpdateListener,
    private val connectionManager: TdLibConnectionManager,
    private val mediaDao: MediaDao,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun initialize() {
        metadataRepository.initialize()
        channelUpdateListener.startListening()
    }

    fun startSync() {
        scope.launch {
            try {
                if (!connectionManager.isConnected) {
                    connectionManager.connect()
                }

                syncService.syncToTelegram(
                    uploadPartition = { partitionId, json ->
                        uploader.uploadPartition(partitionId, json)
                    },
                    uploadManifest = { manifestJson ->
                        uploader.uploadManifest(manifestJson)
                    },
                    allItems = {
                        metadataRepository.getAllMetadata()
                    },
                    totalSizeBytes = calculateTotalSizeBytes(),
                )
            } catch (_: Throwable) {}
        }
    }

    fun reconcile() {
        scope.launch {
            try {
                if (!connectionManager.isConnected) {
                    connectionManager.connect()
                }

                metadataRepository.reconcileFromTelegram(
                    downloadManifest = { downloader.downloadManifest() },
                    downloadPartition = { partitionId -> downloader.downloadPartition(partitionId) },
                )
            } catch (_: Throwable) {}
        }
    }

    fun stop() {
        channelUpdateListener.stopListening()
    }

    private suspend fun calculateTotalSizeBytes(): Long {
        return try {
            val items = mediaDao.allItems()
            items.sumOf { it.fileSize }
        } catch (_: Throwable) { 0L }
    }
}
