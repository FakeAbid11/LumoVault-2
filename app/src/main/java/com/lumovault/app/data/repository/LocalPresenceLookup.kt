package com.lumovault.app.data.repository

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
 * Matching is on file name *and* size because hashing is the backup engine's job — computing a hash
 * here would mean reading every original, which PRD section 25 forbids. A remote item with no name
 * (a plain photo carries none) therefore always reads as cloud-only, which is honest: without the
 * manifest the backup engine will write, nothing proves the two files are the same photo.
 */
class LocalPresenceLookup(
    private val media: MediaDao,
) {
    /** Message ids whose remote file is matched by a local row of the same name and size. */
    suspend fun backedUp(items: List<CloudMedia>): Set<Long> {
        val candidates = items.filter { it.type != MediaType.Photo || it.fileName.isNotBlank() }
        val names = candidates.map { it.fileName }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return emptySet()

        val onDevice = names.chunked(NAME_CHUNK) { chunk -> media.findByName(chunk) }
            .flatten()
            .map { it.displayName to it.sizeBytes }
            .toSet()

        return candidates.filter { it.fileName to it.sizeBytes in onDevice }.map { it.messageId }.toSet()
    }

    private companion object {
        /** SQLite's own parameter limit is ~999; 400 leaves room for the rest of the statement. */
        const val NAME_CHUNK = 400
    }
}
