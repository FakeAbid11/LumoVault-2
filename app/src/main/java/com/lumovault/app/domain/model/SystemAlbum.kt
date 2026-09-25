package com.lumovault.app.domain.model

/**
 * The collections PRD section 2 lists as "system albums": every one of them is an answer about the
 * media itself rather than a list someone maintains, which is why none of them is a row in the database.
 * A Camera album that *stored* its members would be a second, staler copy of what `relative_path`
 * already says about each file, and the moment the two disagreed the screen would be lying.
 *
 * Three of them are about what the user decided instead — [Favorites], [Archive], [Trash] — and those
 * read the organisation record. Three are about where the file came from, and the patterns below are
 * the whole of that definition: MediaStore's `RELATIVE_PATH` is the folder it filed the media under, so
 * `DCIM/Camera/` is the camera, `%Screenshots/%` is a screenshot wherever it landed, and `Download/` is
 * a download.
 *
 * Those patterns are a heuristic, and they are stated as one rather than dressed up as certainty.
 * Android exposes `IS_SCREENSHOT` only from API 31 and nothing at all that says "this came from the
 * camera", so on the API 29 device this app still supports the folder is the only honest signal. A file
 * the user moved out of `Screenshots/` therefore leaves the album, which is what a folder-derived
 * category means; nothing pretends otherwise.
 */
enum class SystemAlbum {
    Camera,
    Screenshots,
    Downloads,
    Videos,
    Favorites,
    Archive,
    Trash,

    /**
     * Newly *arrived* media rather than newly captured.
     *
     * MediaStore's `DATE_ADDED` is when the file entered the device, which is the closest thing to
     * "when LumoVault found it" that exists without a capture date of its own — nothing here reads EXIF,
     * so PRD section 23's "a photo taken in 2022 and imported today" cannot be told apart from a photo
     * taken today. What this view *does* give is a bounded window, which is the useful half of the
     * feature: the things that turned up recently, wherever their dates say they belong.
     */
    RecentlyAdded,
    ;

    /**
     * The `LIKE` pattern that defines a folder album, or null when this album is not one.
     *
     * `RELATIVE_PATH` always ends with a separator, so the trailing `%` covers the file name and nothing
     * else; `Screenshots` is allowed a leading `%` because that folder is filed under different parents
     * on different devices — `Pictures/`, `DCIM/`, or a manufacturer's own root.
     */
    val pathLikePattern: String?
        get() = when (this) {
            Camera -> "DCIM/Camera/%"
            Screenshots -> "%Screenshots/%"
            Downloads -> "Download/%"
            else -> null
        }

    /** The media type this album is defined by, if any. A GIF is its own type and so stays out of Videos. */
    val mediaType: MediaType?
        get() = when (this) {
            Videos -> MediaType.Video
            else -> null
        }

    /** Whether the album is driven by the organisation record rather than by the file's own facts. */
    val isOrganized: Boolean
        get() = this == Favorites || this == Archive || this == Trash

    /**
     * Only a user album holds items because someone put them there. A system album's membership is a
     * consequence of what a file is, so "remove from album" has no meaning on it — you unfavourite,
     * unarchive, restore, or move the file, and the album follows.
     */
    val supportsMembership: Boolean
        get() = false
}
