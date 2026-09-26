package com.lumovault.app.data.local.organization

import com.lumovault.app.data.local.media.LocalNameMatch
import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.data.local.media.MediaEntity
import com.lumovault.app.data.local.media.MediaTypeCount
import com.lumovault.app.data.local.metadata.MediaMetadataEntity
import com.lumovault.app.domain.model.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * One media row, as far as albums and organisation care: what it is, where MediaStore filed it, and the
 * two dates every predicate and ordering reads.
 */
data class FakeMediaRow(
    val id: Long,
    val type: MediaType = MediaType.Photo,
    val relativePath: String = DEFAULT_PATH,
    val dateAddedSeconds: Long = 1_790_000_000L,
    val dateModifiedSeconds: Long = 1_790_000_000L,
    val dateTakenSeconds: Long? = null,
    val sizeBytes: Long = 1024L,
) {
    fun toEntity() = MediaEntity(
        mediaStoreId = id,
        contentUri = "content://media/external/images/media/$id",
        mediaType = type.storageKey,
        mimeType = if (type == MediaType.Video) "video/mp4" else "image/jpeg",
        displayName = "IMG_$id.${if (type == MediaType.Video) "mp4" else "jpg"}",
        relativePath = relativePath,
        sizeBytes = sizeBytes,
        dateAddedSeconds = dateAddedSeconds,
        dateModifiedSeconds = dateModifiedSeconds,
        width = 4,
        height = 3,
        durationMillis = if (type == MediaType.Photo) null else 2_000L,
        lastSeenScanId = SCAN_ID,
        dateTakenSeconds = dateTakenSeconds,
    )

    companion object {
        const val DEFAULT_PATH = "DCIM/Camera/"
        private const val SCAN_ID = 1L
    }
}

/**
 * The four tables Phase 7 adds, in one store shared by the four fakes.
 *
 * One store rather than four separate maps because the whole point of the queries is how they join: an
 * album's count, a system album's contents and the timeline filter all read media *and* organisation, and
 * fakes that each kept their own idea of what was indexed could agree with themselves while the SQL
 * disagreed.
 */
class OrganizationStore {
    val media = LinkedHashMap<Long, MediaEntity>()
    val organization = LinkedHashMap<Long, MediaOrganizationEntity>()
    val albums = LinkedHashMap<Long, AlbumEntity>()
    val members = linkedSetOf<AlbumKey>()

    /**
     * EXIF, keyed by the same id. A separate map rather than more fields on [media] because that is the
     * production shape: the scanner replaces media rows wholesale, and a position paid for by opening a file
     * must survive it.
     */
    val metadata = LinkedHashMap<Long, MediaMetadataEntity>()
    val tick = MutableStateFlow(0)

    private var nextAlbumId = 1L

    fun index(vararg rows: FakeMediaRow) {
        rows.forEach { media[it.id] = it.toEntity() }
        bump()
    }

    /** A scan replacing a row, which is what the media index's `INSERT OR REPLACE` does. */
    fun reindex(row: FakeMediaRow) {
        media[row.id] = row.toEntity()
        bump()
    }

    fun nextAlbumId(): Long = nextAlbumId++

    fun bump() {
        tick.value++
    }

    fun organizationOf(id: Long): MediaOrganizationEntity? = organization[id]

    /**
     * Whether the timeline should show this item at all: archived and trashed items are hidden, and an
     * item whose row vanished with a prune is not visible either because it is not in [media].
     */
    fun visible(id: Long): Boolean {
        if (!media.containsKey(id)) return false
        val state = organization[id] ?: return true
        return !state.archived && state.trashedAt == 0L
    }

    /** Trashed items are hidden from every album view, not only from the timeline. */
    fun inAlbumView(id: Long): Boolean {
        if (!media.containsKey(id)) return false
        return (organization[id]?.trashedAt ?: 0L) == 0L
    }

    fun newestFirst(ids: Collection<Long>, limit: Int): List<MediaEntity> = ids
        .mapNotNull { media[it] }
        .sortedWith(
            compareByDescending<MediaEntity> { it.dateAddedSeconds }.thenByDescending { it.mediaStoreId },
        )
        .take(limit)
}

/** An album and one of its members; the real table's composite primary key, as a value. */
data class AlbumKey(val albumId: Long, val mediaStoreId: Long)

/**
 * SQL `LIKE` for `%` and `_`, which is what lets the fakes derive their answers from the same
 * [com.lumovault.app.domain.model.SystemAlbum.pathLikePattern] strings the queries bind instead of
 * paraphrasing them. A fake with its own copy of a pattern would agree with itself while the database
 * disagreed, and that is a green test worth nothing.
 */
fun String.like(pattern: String): Boolean {
    val regex = buildString {
        append("^")
        for (char in pattern) {
            when (char) {
                '%' -> append(".*")
                '_' -> append(".")
                in ".+*?()[]{}|^\$" -> append('\\').append(char)
                else -> append(char)
            }
        }
        append("$")
    }.toRegex(RegexOption.IGNORE_CASE)
    return matches(regex)
}
