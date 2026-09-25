package com.lumovault.app.ui.navigation

import com.lumovault.app.domain.model.SystemAlbum

/**
 * Which list the viewer swipes through, and where it came back from.
 *
 * The viewer is one screen reached from three, and what it must preserve is the *list*, not the entry point:
 * opening a photo from the Camera album and swiping left should go to the next camera photo, not to whatever
 * the timeline happens to show. That makes the source part of the route rather than a parameter the screen
 * remembers, which is also what survives process death — the same argument the album routes make.
 *
 * System albums are named, not numbered, because they have no ids: their membership is a query. An unknown
 * kind or a name no longer in the enum decodes to [Photos] rather than failing, so a link saved before an
 * album was renamed opens something rather than crashing on the way.
 */
sealed interface ViewerTarget {
    /** The route's second segment: which list this is. */
    val kind: String

    /** The route's third segment: which one, or [NO_ARGUMENT] when the kind needs nothing. */
    val argument: String

    data object Photos : ViewerTarget {
        override val kind: String get() = KIND_PHOTOS
        override val argument: String get() = NO_ARGUMENT
    }

    data class Album(val albumId: Long) : ViewerTarget {
        override val kind: String get() = KIND_ALBUM
        override val argument: String get() = albumId.toString()
    }

    data class SystemAlbumView(val album: SystemAlbum) : ViewerTarget {
        override val kind: String get() = KIND_SYSTEM
        override val argument: String get() = album.name
    }

    companion object {
        const val KIND_PHOTOS = "photos"
        const val KIND_ALBUM = "album"
        const val KIND_SYSTEM = "system"

        /** A path segment cannot be empty, so "this kind needs no argument" needs a word of its own. */
        const val NO_ARGUMENT = "-"

        fun decode(kind: String?, argument: String?): ViewerTarget = when (kind) {
            KIND_ALBUM -> argument?.toLongOrNull()?.let(::Album) ?: Photos
            KIND_SYSTEM -> SystemAlbum.entries.firstOrNull { it.name == argument }?.let(::SystemAlbumView) ?: Photos
            else -> Photos
        }
    }
}

/**
 * The viewer's route. Top-level rather than underneath a tab, because it draws over the whole window — see
 * [com.lumovault.app.ui.LumoVaultApp], which hides the bar for exactly this prefix.
 */
object ViewerRoutes {
    const val PREFIX = "viewer/"

    const val ARG_MEDIA_ID = "mediaId"
    const val ARG_KIND = "kind"
    const val ARG_ARGUMENT = "argument"

    const val PATTERN = "viewer/{$ARG_MEDIA_ID}/{$ARG_KIND}/{$ARG_ARGUMENT}"

    fun of(mediaStoreId: Long, target: ViewerTarget): String =
        "$PREFIX$mediaStoreId/${target.kind}/${target.argument}"
}
