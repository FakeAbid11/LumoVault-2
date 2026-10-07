package com.lumovault.app.domain.model

/**
 * One local file as it is handed to another application: where the receiver can read it from, and
 * how the file declares its type.
 *
 * [uri] is always a `content://` address — a MediaStore row this app does not have to (and must not)
 * open a filesystem path for — because a raw path handed to a receiving app is a path it cannot read
 * under scoped storage, and a path that leaks where a file lives. Nothing here ever carries an
 * absolute filesystem path; the URI permission the share intent grants is what makes the bytes
 * readable for the length of the hand-off.
 *
 * Shared by every screen that can share (Photos, the viewer, albums and Cloud all answer this type
 * from their repositories), so the one intent builder in
 * [com.lumovault.app.util.MediaShare] is the only place that decides what "share" means.
 */
data class ShareableMedia(
    /** A `content://` URI. Blank is not a shareable answer and is dropped by the builder. */
    val uri: String,
    /** The receiver-facing MIME type, exactly as MediaStore records it. */
    val mimeType: String,
)
