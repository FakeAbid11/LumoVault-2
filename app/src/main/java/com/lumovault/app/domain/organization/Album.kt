package com.lumovault.app.domain.organization

import com.lumovault.app.domain.model.SystemAlbum

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
 * Keyed by the album rather than carrying eight fields, because every consumer is a list that iterates
 * [SystemAlbum] anyway; a record with one field per album would move that same iteration somewhere less
 * obvious and let a missing field compile.
 */
data class SystemAlbumCounts(
    val byAlbum: Map<SystemAlbum, Int>,
) {
    fun of(album: SystemAlbum): Int = byAlbum[album] ?: 0
}
