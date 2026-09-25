package com.lumovault.app.domain.restore

import com.lumovault.app.domain.model.MediaType

/**
 * Files a downloaded original into MediaStore, where the rest of Android can see it.
 *
 * A restore that left its bytes in LumoVault's own directory would be a private file: invisible to the
 * gallery, invisible to the scanner, and invisible to the user's understanding of where their photos are.
 * PRD section 52's flow ends at "MediaStore scan" for that reason, and this interface is the step before
 * it — the one place allowed to write into the media provider.
 */
interface RestoredMediaWriter {
    /**
     * Whether the device could hold [sizeBytes] right now.
     *
     * Asked before the download starts, not after, because the failure worth avoiding is the one that
     * arrives three quarters of the way through a video: it costs the user their data *and* their storage,
     * and leaves a partial file to clean up.
     */
    fun hasRoomFor(sizeBytes: Long): Boolean

    suspend fun store(source: RestorableSource): StoredMedia
}

/**
 * A complete file on a path this app can read, ready to be filed.
 *
 * [preferredName], [declaredMimeType] and [mediaType] come from the cloud index, which recorded what this
 * app sent. The writer may override all three: Telegram stores media in containers of its own choosing, and
 * the bytes decide what a file is. [mediaType] is the last resort when the header says nothing — without it
 * a file with an unrecognised signature has no collection to be filed under, and MediaStore needs one.
 */
data class RestorableSource(
    val path: String,
    val preferredName: String,
    val declaredMimeType: String,
    val mediaType: MediaType,
)

/** What filing a file into MediaStore ended as. */
sealed interface StoredMedia {
    /**
     * The row exists and is no longer pending.
     *
     * [mediaStoreId] is the MediaStore id, which is the same number LumoVault's own index keys on — that
     * equality is what lets a restore associate its local row without a filename match, a path match, or
     * any other guess.
     */
    data class Ready(val contentUri: String, val mediaStoreId: Long, val sizeBytes: Long) : StoredMedia

    /** Nothing was left behind: a half-written row is deleted before this is returned. */
    data class Refused(val failure: RestoreFailure) : StoredMedia
}
