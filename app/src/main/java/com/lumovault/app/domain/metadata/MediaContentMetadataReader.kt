package com.lumovault.app.domain.metadata

import com.lumovault.app.domain.model.MediaMetadata

/**
 * Reads what a media file says about itself, without turning it into a bitmap.
 *
 * The interface exists so the map and the details panel can be built and tested against an answer, not
 * against a file format. The implementation is the only place that touches `ContentResolver`, an
 * `ExifInterface`, or the media-location permission, which is what keeps a corrupt JPEG from being able to
 * fail a screen.
 */
interface MediaContentMetadataReader {
    /** Reads [contentUri]. Never throws: every failure arrives as a [MetadataRead]. */
    suspend fun read(contentUri: String): MetadataRead
}

/**
 * The outcome of one read.
 *
 * Three cases because they mean different things downstream. A file that carries no EXIF is a fact about
 * the file; a file that could not be opened is a fact about this moment; and both are recorded as
 * *attempted* so the next pass does not reopen them. Only `ACCESS_MEDIA_LOCATION` redaction makes an
 * absent position uncertain — see [com.lumovault.app.data.local.metadata.MediaMetadataDao.discardUnlocatedReads].
 */
sealed interface MetadataRead {
    /** The file was parsed and had something worth keeping. */
    data class Found(val metadata: MediaMetadata) : MetadataRead

    /** The file was parsed and carries no camera or position data at all. */
    data object NothingRecorded : MetadataRead

    /** The file could not be read, or is not a format that carries this kind of data. */
    data class Unreadable(val failure: MetadataFailure) : MetadataRead
}

enum class MetadataFailure {
    /** The row points at nothing; the file was deleted between the scan and this pass. */
    SourceMissing,

    /** The bytes could not be opened, or the grant disappeared under the read. Transient, so nothing is
     * recorded and the item stays a candidate. */
    Unreadable,
}

/**
 * A photo the extraction pass has not opened yet.
 *
 * Carries the content uri as well as the id because reading EXIF is the one job here that needs the file,
 * and making the pass look the uri up per item would be a query per photo — the N+1 shape this app's rules
 * call out by name.
 */
data class MetadataCandidate(val mediaStoreId: Long, val contentUri: String)
