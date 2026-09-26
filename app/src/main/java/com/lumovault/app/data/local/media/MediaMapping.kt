package com.lumovault.app.data.local.media

import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaType

/**
 * The index row to the library model.
 *
 * Top-level and `internal` because more than one repository reads this table: the media index itself,
 * and Phase 7's albums and system-album queries, which join to `media` and must hand back the same
 * [Media] the timeline does. Two private copies of one mapping is how an album's cell and a timeline
 * cell end up disagreeing about what the same row means.
 */
internal fun MediaEntity.toMedia(): Media = Media(
    id = mediaStoreId,
    contentUri = contentUri,
    type = MediaType.fromStorageKey(mediaType),
    mimeType = mimeType,
    displayName = displayName,
    relativePath = relativePath,
    sizeBytes = sizeBytes,
    dateAddedSeconds = dateAddedSeconds,
    dateModifiedSeconds = dateModifiedSeconds,
    width = width,
    height = height,
    durationMillis = durationMillis,
    dateTakenSeconds = dateTakenSeconds,
)
