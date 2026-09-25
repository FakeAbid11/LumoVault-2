package com.lumovault.app.data.media

import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Asks Android to delete media files, which is the only way an app may remove them.
 *
 * `MediaStore.createDeleteRequest` is consent, not a workaround: the system shows the user what is about
 * to go, and LumoVault learns the answer from the activity result. That is also why nothing here claims a
 * deletion — the caller acts on the result, and a Trash row that was not confirmed stays in Trash.
 *
 * API 30 introduced that request. On the API 29 this app still supports, deleting someone else's media
 * means holding `WRITE_EXTERNAL_STORAGE`, which LumoVault deliberately does not ask for: the honest
 * answer is null, and the screen says the file has to be removed in the system's own gallery rather than
 * pretending a button worked.
 */
class MediaStoreLocalDeleter(private val resolver: ContentResolver) {

    /**
     * A delete request for [contentUris], or null when this build cannot ask at all.
     *
     * Null means "do not try, and tell the user", not "nothing to do": the caller keeps the items in
     * Trash and the UI explains the limit, which is the only version of this that is not a false success.
     */
    fun requestFor(contentUris: Collection<String>): DeletionRequest? {
        val uris = contentUris.mapNotNull { uri -> runCatching { Uri.parse(uri) }.getOrNull() }
        if (uris.isEmpty()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return runCatching {
            resolver.createDeleteRequest(uris, REQUEST_REASON)?.let { sender ->
                DeletionRequest(sender, uris.map(Uri::toString))
            }
        }.getOrNull()
    }

    /** The consent prompt plus the ids it covers, so the result can be applied to exactly those. */
    data class DeletionRequest(
        val intentSender: IntentSender,
        val contentUris: List<String>,
    )
}
