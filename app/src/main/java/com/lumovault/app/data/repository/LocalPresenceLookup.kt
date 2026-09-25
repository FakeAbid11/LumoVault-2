package com.lumovault.app.data.repository

import com.lumovault.app.data.local.backup.BackupQueueDao
import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.MediaType

/**
 * Answers "is this remote item also on the device?", which is what lets the Cloud screen say
 * cloud-only rather than implying the photo is missing.
 *
 * Deliberately not part of [com.lumovault.app.domain.repository.MediaRepository]: the local library
 * has no use for the question, and adding it there would put cloud vocabulary in the local
 * repository's contract.
 *
 * Two signals, of different strength. A message that carries a manifest is matched by content hash
 * against a backup record whose media row still exists, which is an exact answer: the same bytes are
 * here and they are stored there. Everything without a manifest — every backup made before Phase 6, and
 * every photo the user dropped into the channel themselves — falls back to file name *and* size, because
 * hashing here would mean reading every original and PRD section 25 forbids that during a browse. A plain
 * photo reports no name to Telegram either, so before its manifest existed it could only ever read as
 * cloud-only, which was the honest answer at the time and is a limitation now rather than a bug.
 */
class LocalPresenceLookup(
    private val media: MediaDao,
    private val queue: BackupQueueDao,
) {
    /** Message ids whose remote file the device still holds. */
    suspend fun backedUp(items: List<CloudMedia>): Set<Long> {
        val hashes = items.mapNotNull { it.contentHash.takeIf(String::isNotBlank) }.distinct()
        val stored = if (hashes.isEmpty()) emptySet() else hashMatched(items, hashes)
        return stored + nameAndSizeMatched(items.filterNot { it.messageId in stored })
    }

    private suspend fun hashMatched(items: List<CloudMedia>, hashes: List<String>): Set<Long> {
        val onDevice = mutableSetOf<String>()
        hashes.chunked(QUERY_CHUNK).forEach { chunk ->
            queue.hashesStillOnDevice(chunk).forEach { onDevice += it }
        }
        return items.filter { it.contentHash in onDevice }.map { it.messageId }.toSet()
    }

    private suspend fun nameAndSizeMatched(items: List<CloudMedia>): Set<Long> {
        // A photo carries no name of its own, so a name match is only possible for the kinds that do.
        val candidates = items.filter { it.type != MediaType.Photo || it.fileName.isNotBlank() }
        val names = candidates.map { it.fileName }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return emptySet()

        val onDevice = mutableSetOf<Pair<String, Long>>()
        names.chunked(QUERY_CHUNK).forEach { chunk ->
            media.findByName(chunk).forEach { match -> onDevice += match.displayName to match.sizeBytes }
        }
        return candidates.filter { it.fileName to it.sizeBytes in onDevice }.map { it.messageId }.toSet()
    }

    private companion object {
        /** SQLite's own parameter limit is ~999; 400 leaves room for the rest of the statement. */
        const val QUERY_CHUNK = 400
    }
}
