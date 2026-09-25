package com.lumovault.app.data.local.organization

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-made album.
 *
 * Only user albums are stored. The system albums PRD section 2 lists — Camera, Screenshots, Downloads,
 * Videos, Favorites, Archive, Trash, Recently Added — are all answers *about the media*: which folder
 * MediaStore says it came from, what type it is, what the user marked it. Writing rows for them would
 * put a second, drift-prone copy of those facts in the database, and a row that nothing recomputes is
 * how an album ends up claiming photos it no longer holds. They are derived at query time instead, in
 * [com.lumovault.app.domain.model.SystemAlbum].
 *
 * There is no cover column either. An album's cover is its newest item's thumbnail, which the list
 * query reads from the membership join; a stored "cover id" nothing sets would be a column that only
 * ever means "not chosen yet".
 */
@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    /** When the user made it, which is what the Albums list orders by. */
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0,
)

/**
 * One item in one album.
 *
 * The composite primary key *is* the duplicate-membership rule: two adds of the same photo are one
 * row, and the `INSERT OR IGNORE` in [AlbumDao.addMembers] relies on that rather than checking first,
 * which is the difference between one statement and a read-then-write race.
 *
 * The foreign key points at `albums` with a cascade, because deleting an album must remove its
 * membership rows and nothing else. It pointedly does *not* point at `media`, and that is not an
 * oversight: the media index is rewritten with `@Upsert`, which is `INSERT OR REPLACE`, and a cascade
 * from a parent the scanner replaces would silently empty every album on the next sync. The same
 * reasoning is why `backup_queue` relates to media by id with a left join instead — a media row is a
 * fact about the device that a scan overwrites, and organization is a fact the user created.
 */
@Entity(
    tableName = "album_media",
    primaryKeys = ["album_id", "media_store_id"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["album_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("media_store_id")],
)
data class AlbumMembershipEntity(
    @ColumnInfo(name = "album_id")
    val albumId: Long,

    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,

    @ColumnInfo(name = "added_at", defaultValue = "0")
    val addedAt: Long = 0,
)

/**
 * What the user decided about one media item, kept apart from both the media index and the backup
 * record so that each of the three can change alone.
 *
 * Three independent dimensions, one row each by id: [favorite] is a label, [archived] hides an item
 * from the timeline without hiding it from the library, and [trashedAt] hides it from both until it is
 * restored or permanently deleted. None of them is derived from backup state, and none of them implies
 * a backup — a photo can be favourited and un-backed-up, or archived and safely stored, and the four
 * combinations PRD section 12's Phase 7 brief asks for are all representable here precisely because
 * nothing collapses them.
 *
 * Sparse by design: a row exists only for an item the user actually organised, so a 100,000-item
 * library whose owner never pressed anything keeps 0 rows here.
 */
@Entity(
    tableName = "media_organization",
    indices = [
        // Each system album is one of these three predicates, and each is answered by filtering on it
        // and then ordering by the media table's own date — so an index here is what keeps "show me
        // my favorites" from becoming a scan of the library.
        Index("favorite"),
        Index("archived"),
        Index("trashed_at"),
    ],
)
data class MediaOrganizationEntity(
    /** MediaStore id, matching `media` and `backup_queue` so one item is one id everywhere. */
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,

    @ColumnInfo(name = "favorite", defaultValue = "0")
    val favorite: Boolean = false,

    @ColumnInfo(name = "archived", defaultValue = "0")
    val archived: Boolean = false,

    /**
     * When it went to Trash, in seconds; 0 means not trashed.
     *
     * A timestamp rather than a boolean because Trash promises recovery: PRD section 22's "restore"
     * and Android's own 30-day retention both need to know how long something has been sitting, and a
     * flag could not answer that after a restart.
     */
    @ColumnInfo(name = "trashed_at", defaultValue = "0")
    val trashedAt: Long = 0,
)
