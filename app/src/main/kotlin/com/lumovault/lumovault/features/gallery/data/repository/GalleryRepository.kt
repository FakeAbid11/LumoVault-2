package com.lumovault.lumovault.features.gallery.data.repository

import com.lumovault.lumovault.core.database.dao.DuplicateHash
import com.lumovault.lumovault.core.database.dao.MediaDao
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.data.service.MediaScannerService
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class GalleryRepository @Inject constructor(
    private val mediaDao: MediaDao,
    private val scanner: MediaScannerService,
) {

    val timeline: Flow<List<MediaItemEntity>> = mediaDao.timelineFlow()
    val favorites: Flow<List<MediaItemEntity>> = mediaDao.favoritesFlow()
    val trashed: Flow<List<MediaItemEntity>> = mediaDao.trashedFlow()
    val hidden: Flow<List<MediaItemEntity>> = mediaDao.hiddenFlow()
    val archived: Flow<List<MediaItemEntity>> = mediaDao.archivedFlow()

    suspend fun mediaItem(localId: String): MediaItemEntity? = mediaDao.byLocalId(localId)

    suspend fun search(query: String): List<MediaItemEntity> = mediaDao.search(query)

    suspend fun semanticSearch(
        queryEmbedding: FloatArray,
        limit: Int = 60,
        minScore: Double = 0.2,
    ): List<MediaItemEntity> {
        val items = mediaDao.allWithEmbeddings()
        val scored = items.mapNotNull { item ->
            val emb = item.clipEmbedding ?: return@mapNotNull null
            if (emb.size != EMBEDDING_DIM) return@mapNotNull null
            val score = cosineSimilarity(queryEmbedding, emb.toFloatArray())
            if (score >= minScore) item to score else null
        }
        return scored.sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    suspend fun itemsNeedingEmbedding(): List<MediaItemEntity> =
        mediaDao.itemsNeedingEmbedding()

    suspend fun updateClipEmbedding(localId: String, embedding: FloatArray) =
        mediaDao.setClipEmbedding(localId, embedding.toList())

    suspend fun labelMediaItem(localId: String, labels: List<String>) =
        mediaDao.setAiLabels(localId, labels)

    suspend fun labeledLocalIds(): Set<String> =
        mediaDao.allLabeledIds().toSet()

    suspend fun refreshFromDevice(onProgress: ((Int) -> Unit)? = null): Int {
        val now = System.currentTimeMillis()
        val scanned = scanner.listAll { loaded -> onProgress?.invoke(loaded) }
        val entities = scanned.map { scanner.toEntity(it, now) }
        val existing = mediaDao.byLocalIds(scanned.map { it.id.toString() })
            .associateBy { it.localId }
        val withIds = entities.map { e -> existing[e.localId]?.let { e.copy(id = it.id) } ?: e }
        mediaDao.upsertAll(withIds)
        val seen = withIds.map { it.localId }.toHashSet()
        val stale = existing.keys - seen
        stale.chunked(BATCH).forEach { mediaDao.deleteByLocalIds(it) }
        return withIds.size
    }

    suspend fun setFavorite(localId: String, favorite: Boolean) =
        mediaDao.setFavorite(localId, favorite)

    suspend fun moveToTrash(localId: String) =
        mediaDao.moveToTrash(localId, System.currentTimeMillis())

    suspend fun restoreFromTrash(localId: String) = mediaDao.restoreFromTrash(localId)

    suspend fun deletePermanently(localId: String) = deletePermanently(listOf(localId))

    suspend fun deletePermanently(localIds: List<String>) {
        if (localIds.isEmpty()) return
        mediaDao.deleteByLocalIds(localIds)
    }

    suspend fun setHidden(localId: String, hidden: Boolean) =
        mediaDao.setHidden(localId, hidden)

    suspend fun setArchived(localId: String, archived: Boolean) =
        mediaDao.setArchived(localId, archived)

    suspend fun duplicateGroups(): List<List<MediaItemEntity>> {
        val hashes = mediaDao.duplicateHashes().sortedWith(
            compareByDescending<DuplicateHash> { it.count }.thenBy { it.fileHash },
        )
        return hashes.mapNotNull { hash ->
            val group = mediaDao.byFileHash(hash.fileHash)
            if (group.size >= 2) group else null
        }
    }

    companion object {
        private const val BATCH = 500
        private const val EMBEDDING_DIM = 512

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
            if (a.size != b.size) return 0.0
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i].toDouble()
                normA += a[i] * a[i].toDouble()
                normB += b[i] * b[i].toDouble()
            }
            val denom = sqrt(normA) * sqrt(normB)
            return if (denom == 0.0) 0.0 else dot / denom
        }
    }
}
