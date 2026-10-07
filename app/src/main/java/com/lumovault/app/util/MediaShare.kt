package com.lumovault.app.util

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lumovault.app.R
import com.lumovault.app.domain.model.ShareableMedia

/**
 * The app's one way of handing media to another application.
 *
 * Every screen that shares — the Photos selection strip, the album strip, the Cloud strip and the
 * viewer — calls [share] with the same [ShareableMedia] rows its own data layer answered, so there
 * is exactly one answer anywhere in the app to the questions that make sharing fragile:
 *
 *  - **Which intent.** One item is [Intent.ACTION_SEND], two or more are
 *    [Intent.ACTION_SEND_MULTIPLE]. A multiple-item share sent as a single `ACTION_SEND` is the
 *    classic way an app silently drops everything after the first photo.
 *  - **Which type.** One item takes its own MIME type verbatim; several collapse to a family
 *    wildcard (`image` for a tray of photographs, `video` for a tray of clips) when they are all
 *    one family (an exact type when they are all the same file type, so a tray of GIFs announces
 *    `image/gif`), and a mixed selection falls back to [ANY_TYPE] rather than lying that every
 *    item is an image.
 *  - **Which address.** Content URIs only. A raw filesystem path would be unreadable to the
 *    receiver under scoped storage and would disclose where files live, so nothing here ever builds
 *    one — the rows come from MediaStore's own `content://` column.
 *  - **Permission.** [Intent.FLAG_GRANT_READ_URI_PERMISSION] is set on the intent *and* carried by
 *    [Intent.setClipData], because the framework grants the URIs a clip holds; the extra alone is
 *    not a grant. The chooser gets the flag too, so the target the user picks inherits it.
 *
 * The sheet the user sees is Android's own share sheet ([Intent.createChooser]); there is no
 * in-app imitation of one, and this object never draws anything.
 *
 * The decisions that can be wrong without a device — action choice, type resolution, which URIs
 * travel — are the public, pure surface this file exposes, and they are what `MediaShareTest`
 * asserts.
 */
object MediaShare {
    /** What a mixed or unknown selection declares; Android's own "any type" answer. */
    const val ANY_TYPE = "*/*"

    /** The intent action a share of [itemCount] items uses. */
    fun shareAction(itemCount: Int): String =
        if (itemCount > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND

    /**
     * The MIME type a share of these types announces.
     *
     * Blank types are dropped rather than propagated — a missing type on one item must not turn a
     * tray of JPEGs into the catch-all — and only when nothing is known does the answer become
     * [ANY_TYPE].
     */
    fun resolvedType(mimeTypes: List<String>): String {
        val known = mimeTypes.filter { it.isNotBlank() }.distinct()
        if (known.isEmpty()) return ANY_TYPE
        if (known.size == 1) return known.first()
        return when {
            known.all { it.startsWith("image/") } -> "image/*"
            known.all { it.startsWith("video/") } -> "video/*"
            else -> ANY_TYPE
        }
    }

    /**
     * The share intent for these items, or null when there is nothing to share.
     *
     * Null is a real answer rather than an exception: a selection can outlive the rows it named
     * (a file deleted from another app a moment ago), and a share button that does nothing must be
     * told so — the caller reports it — instead of opening an empty share sheet.
     */
    fun buildIntent(items: List<ShareableMedia>): Intent? {
        // Distinct by address: the same file can be selected through two routes (a cloud copy and
        // its local one), and one URI twice in a share sheet reads as two photos to the receiver.
        val shareable = items.filter { it.uri.isNotBlank() }.distinctBy { it.uri }
        if (shareable.isEmpty()) return null

        val uris = shareable.map { Uri.parse(it.uri) }
        val shareType = resolvedType(shareable.map { it.mimeType })

        return Intent(shareAction(shareable.size)).apply {
            // Named `shareType`, never `type`: inside `apply` a local called `type` and this intent's
            // own `type` property would read from the same bare name, and which of them wins is a
            // resolution detail rather than a decision anyone here meant to make.
            this.type = shareType
            if (uris.size == 1) {
                // The single-item shape receivers expect: one Parcelable under EXTRA_STREAM, not a
                // one-element list, because `getParcelableExtra` is how most targets read it.
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            // The clip is the grant's carrier, and it carries every URI, not only the first.
            val clip = ClipData.newRawUri(shareType, uris.first())
            uris.drop(1).forEach { uri -> clip.addItem(ClipData.Item(uri)) }
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Opens Android's share sheet for these items, and answers whether a sheet was opened.
     *
     * [onEmpty] fires instead when there is nothing to share, so the screen can say so; a button
     * that opens an empty sheet, or one that opens nothing in silence, both read as broken.
     */
    fun share(context: Context, items: List<ShareableMedia>, onEmpty: (() -> Unit)? = null): Boolean {
        val intent = buildIntent(items)
        if (intent == null) {
            onEmpty?.invoke()
            return false
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.share_action))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // A share button must not care which Context the composition held. An Activity may start
        // an activity as itself; anything else is refused outright unless the launch declares a
        // task of its own. The flag is added only for that case, so the Activity path every screen
        // takes today behaves exactly as it did.
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        return true
    }
}
