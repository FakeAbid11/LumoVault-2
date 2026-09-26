package com.lumovault.app.domain.model

/**
 * A folder on the device, offered as an album and as a backup source.
 *
 * One record for both questions, because both ask the same one: what is filed at this path. The album grid
 * hides folders the Library already lists; the backup picker must not — `DCIM/Camera/` is the folder people
 * most often want backed up, and letting an album-screen rule decide that would be a backup decision made
 * by the wrong screen.
 *
 * Deliberately not an [com.lumovault.app.domain.organization.Album]: a folder is not a list LumoVault
 * owns. It has no row in `albums`, no entry in `album_media`, and nothing can be added to it or taken from
 * it — the device decides its membership the moment a photo is saved into it. Keeping the two kinds apart
 * in the type is what makes "delete this album" unable to ever reach somebody's WhatsApp pictures, and
 * keeps a folder vanishing off the disk from touching a user's album of the same name.
 *
 * [relativePath] is the identity and [displayName] is only a label, because two different folders can be
 * called the same thing: `Pictures/Telegram/` and `DCIM/Telegram/` are not one album, and an id built from
 * the last segment would quietly merge them into a list that is neither.
 */
data class LocalFolder(
    /** Normalized, always with the trailing separator: see [FolderPaths.normalize]. */
    val relativePath: String,
    val displayName: String,
    /** The parent folder, for the cases where two folders share a display name. */
    val parentLabel: String,
    val mediaCount: Int,
    /**
     * The newest item's own thumbnail, or null when the folder has nothing drawable.
     *
     * Never a remote identifier: a cover is drawn from the file that is already on the device, and
     * fetching one from Telegram to decorate an album card would spend the user's data on a view.
     */
    val coverUri: String? = null,
)

/**
 * MediaStore's `RELATIVE_PATH` has more than one legal spelling, and a folder album keyed on the raw value
 * would show the same folder twice under two of them.
 *
 * Everything below is about producing exactly one string per physical folder, because that string is what
 * a folder album's identity is, what the route carries, and what the backup folder picker compares with
 * `relative_path IN (…)` — three places that have to agree.
 */
object FolderPaths {
    /** MediaStore files at the root of a volume with no folder of their own; not an album. */
    const val ROOT = "/"

    /** `Pictures//WhatsApp\` and `pictures/whatsapp` both become `Pictures/WhatsApp/`. */
    fun normalize(raw: String): String {
        val segments = raw.trim().replace('\\', '/').split('/').filter { it.isNotEmpty() }
        return if (segments.isEmpty()) ROOT else segments.joinToString("/") + "/"
    }

    /**
     * The last segment, which is the word a person would use for the folder.
     *
     * Deliberately not [Media.folderName], which keeps the whole trimmed path: that form is right for the
     * backup folder picker, where choosing `Pictures/WhatsApp` over `DCIM/WhatsApp` is the decision being
     * made, and wrong on an album card, where the same fact appears twice as soon as two folders share a
     * name. Never the raw path either — `Pictures/WhatsApp/` in a title is Android's filing, not the
     * album's name.
     */
    fun displayNameOf(normalized: String): String =
        normalized.trimEnd('/').substringAfterLast('/').ifBlank { ROOT.trimEnd('/') }

    /** Everything above the last segment, or empty for a folder sitting at the root. */
    fun parentOf(normalized: String): String = normalized.trimEnd('/').substringBeforeLast('/', "")
}
