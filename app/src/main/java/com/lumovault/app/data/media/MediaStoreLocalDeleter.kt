package com.lumovault.app.data.media

import android.app.PendingIntent
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
 * deletion happened — the caller acts on the result, and a Trash row that was not confirmed stays in
 * Trash, which is the only honest reading of a dialog the user may have dismissed.
 *
 * API 30 introduced that request. On the API 29 this app still supports, deleting media the app did not
 * create means holding `WRITE_EXTERNAL_STORAGE`, which LumoVault deliberately does not ask for, so the
 * answer is null and the screen says the file has to be removed in the device's own gallery rather than
 * pretending a button worked.
 */
class MediaStoreLocalDeleter(private val resolver: ContentResolver) {

    /**
     * The consent request for [contentUris], or null when this build cannot ask at all.
     *
     * Null is "do not try, and say so", not "there was nothing to do" — the caller keeps the items in
     * Trash and explains why, which is the only version of this that is not a false success.
     */
    fun requestFor(contentUris: Collection<String>): DeletionRequest? {
        val uris = contentUris.mapNotNull { uri -> runCatching { Uri.parse(uri) }.getOrNull() }
        if (uris.isEmpty()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        // The platform takes the resolver and the list and writes the prompt itself, so there is no
        // app-supplied reason string to get wrong and nothing here to localise.
        val prompt: PendingIntent? = runCatching {
            MediaStore.createDeleteRequest(resolver, uris)
        }.getOrNull()

        return prompt?.let { DeletionRequest(it.intentSender, uris.map(Uri::toString)) }
    }

    /** The consent request plus the uris it covers, so the result applies to exactly those. */
    data class DeletionRequest(
        val intentSender: IntentSender,
        val contentUris: List<String>,
    )
}
