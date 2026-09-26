package com.lumovault.app.ui.navigation

import com.lumovault.app.domain.model.FolderPaths
import com.lumovault.app.domain.model.SystemAlbum

/**
 * Which album a detail screen is showing.
 *
 * One type because the two kinds genuinely differ: a user album is a stored list you can add to and
 * take from, and a system album is a question asked of the library — "what is a screenshot" — that no
 * hand-edit can answer. Passing this instead of a bare id keeps the difference in the type rather than
 * in a flag the detail screen would have to remember to check.
 */
sealed interface AlbumTarget {
    data class User(val albumId: Long) : AlbumTarget
    data class System(val album: SystemAlbum) : AlbumTarget

    /**
     * A folder on the device.
     *
     * A path rather than a number, and deliberately so: `albumId` is a row id into `albums`, and inventing a
     * folder id in the same space — a negative, a large constant, a hash — would eventually hand a folder
     * screen an id that also names somebody's "Vacation" album, where the two screens read different tables
     * and neither one is wrong.
     */
    data class LocalFolder(val relativePath: String) : AlbumTarget

    companion object {
        /**
         * Parses the argument off the route.
         *
         * `null` for anything unrecognised, including a system-album name from a newer build: a stale
         * link should not invent an album, and the caller shows the empty state instead.
         */
        fun from(raw: String?, id: String?): AlbumTarget? {
            raw ?: return null
            id?.toLongOrNull()?.let { return User(it) }
            return SystemAlbum.entries.firstOrNull { it.name == raw }?.let { System(it) }
        }
    }
}

/**
 * Route arguments and patterns, kept next to the type that reads them so the two cannot drift apart.
 *
 * These are deliberately *not* members of [LumoVaultDestination]: that enum is what the bottom bar
 * iterates, and a detail screen is not a fifth tab. The bar resolves a detail route back to Albums by
 * prefix instead, which is also what keeps a deep link from highlighting nothing.
 */
object AlbumRoutes {
    const val ARG_TARGET = "target"
    const val ARG_ALBUM_ID = "albumId"

    const val USER_PATTERN = "albums/user/{albumId}"
    const val SYSTEM_PATTERN = "albums/system/{target}"
    const val LOCAL_PATTERN = "albums/folder/{path}"

    const val ARG_PATH = "path"

    /** Every album screen hangs under this prefix, including the tabs' own `albums` route. */
    const val DETAIL_PREFIX = "albums/"

    fun user(albumId: Long): String = "albums/user/$albumId"

    fun system(album: SystemAlbum): String = "albums/system/${album.name}"

    /** Percent-encoded, because `Pictures/WhatsApp/` is three route segments if it is not. */
    fun local(relativePath: String): String =
        "albums/folder/" + android.net.Uri.encode(FolderPaths.normalize(relativePath))
}
