package com.lumovault.app.domain.organization

import com.lumovault.app.domain.model.Media
import kotlinx.coroutines.flow.Flow

/**
 * User albums: the only collections in the app whose membership is a decision rather than a fact about
 * the file.
 *
 * Two rules hold throughout, and both are about what deleting an album must never do. Removing an album
 * removes *the list*: the media stays indexed, the organisation stays marked, the backup record and the
 * Telegram message behind it stay exactly where they are. And adding an item to an album does not queue
 * an upload — a photo filed into "Summer Trip" is the same photo with the same backup state as it had a
 * second earlier.
 *
 * Album names are the one piece of user text this writes, so they are trimmed, required to have some
 * content, and capped: a name of no length means an album nobody can find again, and an unbounded one
 * breaks the row it is drawn in.
 */
interface AlbumRepository {
    fun observeAlbums(): Flow<List<Album>>

    fun observeAlbum(albumId: Long): Flow<Album?>

    /** The album's members, newest first, excluding any that are in Trash. */
    fun observeContents(albumId: Long, limit: Int): Flow<List<Media>>

    /** The new album's id, or null when [name] is not usable. */
    suspend fun create(name: String): Long?

    /** False when the album does not exist or [name] is not usable; the name is stored as typed. */
    suspend fun rename(albumId: Long, name: String): Boolean

    /** False when there is no such album. Nothing else is deleted, whatever the membership holds. */
    suspend fun delete(albumId: Long): Boolean

    /** Adds the members that are in the index and not already there; returns how many that was. */
    suspend fun addMedia(albumId: Long, mediaStoreIds: Collection<Long>): Int

    /** Takes members out of the album. The media is untouched by this, as ever. */
    suspend fun removeMedia(albumId: Long, mediaStoreIds: Collection<Long>): Int

    /** Which of [mediaStoreIds] are already members, so a picker can show them ticked. */
    suspend fun membersWithin(albumId: Long, mediaStoreIds: Collection<Long>): List<Long>

    /** Which albums contain this item, for the sheet that adds it to more. */
    suspend fun albumsContaining(mediaStoreId: Long): List<Long>

    companion object {
        /** Long enough for a real title, short enough that the list row never has to ellipsis-wrap. */
        const val MAX_NAME_LENGTH = 80
    }
}
