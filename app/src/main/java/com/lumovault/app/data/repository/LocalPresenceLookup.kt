package com.lumovault.app.data.repository

import com.lumovault.app.data.local.MAX_IDS_PER_QUERY
import com.lumovault.app.data.local.backup.BackupQueueDao
import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.data.local.media.MediaEntity
import com.lumovault.app.data.local.media.toMedia
import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaType

/**
 * Answers "is this remote item also on the device?" — and, with one core, answers the two deeper
 * forms of the same question: *which row* is it (so a cloud tap can open the shared viewer), and
 * *what can be handed to another app* (so the share sheet sends the device's own bytes).
 *
 * Deliberately not part of [com.lumovault.app.domain.repository.MediaRepository]: the local library
 * has no use for the question, and adding it there would put cloud vocabulary in the local
 * repository's contract.
 *
 * Two signals, of different strength. A message that carries a manifest is matched by content hash
 * against a backup record whose media row still exists, which is an exact answer: the same bytes are
 * here and they are stored there. Everything without a manifest — every backup made before Phase 6,
 * and every photo the user dropped into the channel themselves — falls back to file name *and* size,
 * because hashing here would mean reading every original and PRD section 25 forbids that during a
 * browse. A plain photo reports no name to Telegram either, so before its manifest existed it could
 * only ever read as cloud-only, which was the honest answer at the time and is a limitation now
 * rather than a bug.
 *
 * All three public answers derive from [localMediaIds], so the badge, the viewer and the share sheet
 * can never disagree about which items are on the device: they are three views of one lookup.
 */
class LocalPresenceLookup(
    private val media: MediaDao,
    private val queue: BackupQueueDao,
) {
    /**
     * Message ids whose remote file the device still holds.
     *
     * The badge's answer, and nothing but keys of [localMediaIds] — computed that way so the mark a
     * cell draws and the rows a tap reaches are the same fact.
     */
    suspend fun backedUp(items: List<CloudMedia>): Set<Long> = localMediaIds(items).keys

    /**
     * The device row each remote item's copy lives in, keyed by message id.
     *
     * Keys are a subset of the items' own message ids, in the items' order; an item with no local
     * copy simply has no key — a fact, not a failure.
     */
    suspend fun localMediaIds(items: List<CloudMedia>): Map<Long, Long> {
        val matched = LinkedHashMap<Long, Long>()
        if (items.isEmpty()) return matched

        // The exact signal first: a manifest hash whose media row still exists.
        val hashes = items.mapNotNull { it.contentHash.takeIf(String::isNotBlank) }.distinct()
        if (hashes.isNotEmpty()) {
            val rowByHash = mutableMapOf<String, Long>()
            hashes.chunked(MAX_IDS_PER_QUERY).forEach { chunk ->
                queue.mediaIdsStillOnDevice(chunk).forEach { row -> rowByHash[row.hash] = row.mediaId }
            }
            items.forEach { item ->
                if (item.contentHash.isNotBlank()) {
                    rowByHash[item.contentHash]?.let { mediaId -> matched[item.messageId] = mediaId }
                }
            }
        }

        // Then name *and* size, for everything the hash could not place: the same rule the badge has
        // always used, now yielding the row as well as the yes.
        val pending = items.filterNot { it.messageId in matched }
        val candidates = pending.filter { it.type != MediaType.Photo || it.fileName.isNotBlank() }
        val names = candidates.map { it.fileName }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return matched

        val rowByNameAndSize = mutableMapOf<Pair<String, Long>, Long>()
        names.chunked(MAX_IDS_PER_QUERY).forEach { chunk ->
            media.findByName(chunk).forEach { row ->
                rowByNameAndSize[row.displayName to row.sizeBytes] = row.mediaId
            }
        }
        candidates.forEach { item ->
            rowByNameAndSize[item.fileName to item.sizeBytes]?.let { mediaId ->
                matched[item.messageId] = mediaId
            }
        }
        return matched
    }

    /**
     * The full local rows for these ids, in the order the ids were given.
     *
     * Chunked against the query ceiling, and deliberately *without* the timeline's visibility
     * filter: an archived or trashed local copy still exists, and a viewer or a share reached from
     * the Cloud screen must find it rather than report a file the device plainly holds.
     *
     * The order is *this* function's contract, not the SQL's: `WHERE media_store_id IN (…)` walks an
     * index and returns rows in key order, so a shared answer assembled from that query would hand
     * the viewer a window whose page 0 is the oldest row rather than the one the Cloud grid tapped.
     * The rows are therefore re-sequenced against the asked list.
     */
    suspend fun rowsForMedia(ids: Collection<Long>): List<Media> {
        val wanted = ids.distinct()
        if (wanted.isEmpty()) return emptyList()

        val byId = mutableMapOf<Long, MediaEntity>()
        wanted.chunked(MAX_IDS_PER_QUERY).forEach { chunk ->
            media.rowsFor(chunk).forEach { row -> byId[row.mediaStoreId] = row }
        }
        return wanted.mapNotNull { id -> byId[id]?.toMedia() }
    }

    /**
     * The local copies of these remote items, in the items' order: what the shared viewer pages
     * through when an on-device cloud item is opened, and what the share utility is handed.
     * Items without a local copy are absent rather than approximated — a thumbnail is not the file.
     */
    suspend fun localMediaFor(items: List<CloudMedia>): List<Media> =
        rowsForMedia(localMediaIds(items).values)
}
