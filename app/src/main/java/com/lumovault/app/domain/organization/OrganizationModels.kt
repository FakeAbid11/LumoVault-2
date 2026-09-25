package com.lumovault.app.domain.organization

import com.lumovault.app.domain.model.Media

/** A user album, as the Albums list and the detail header need it. */
data class Album(
    val id: Long,
    val name: String,
    val createdAtSeconds: Long,
    /** How many members are still on the device and not in Trash, counted by the database. */
    val itemCount: Int,
    /** A member's thumbnail, newest first; null for an album with nothing left in it. */
    val coverUri: String?,
)

/**
 * How many items each system album currently holds.
 *
 * A map keyed by the album rather than eight fields, because every consumer is a list that iterates
 * [com.lumovault.app.domain.model.SystemAlbum] anyway — a record with one field per album would just
 * move the same iteration somewhere less obvious, and a missing field would compile.
 */
data class SystemAlbumCounts(
    val byAlbum: Map<SystemAlbumKey, Int>,
) {
    fun of(album: SystemAlbumKey): Int = byAlbum[album] ?: 0

    val total: Int get() = byAlbum.values.sum()
}

/**
 * The type alias exists so the map's key is spelled the same here and at the call sites without the
 * domain model depending on the enum's own file layout.
 */
typealias SystemAlbumKey = com.lumovault.app.domain.model.SystemAlbum

/**
 * One item's organisation, as the record stores it.
 *
 * Three separate flags rather than one state, because they are not mutually exclusive and PRD section
 * 21 says so outright: an item can be favourited and archived and in Trash at once, and each of the
 * three still means what it means. Flattening them into an enum is what would make
 * "favourite + backed up + archived" unrepresentable.
 */
data class MediaOrganization(
    val mediaStoreId: Long,
    val favorite: Boolean,
    val archived: Boolean,
    val trashedAtSeconds: Long,
) {
    val isTrashed: Boolean get() = trashedAtSeconds > 0L
}
