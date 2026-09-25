package com.lumovault.app.domain.model

/**
 * What the file itself says about where and with what it was made.
 *
 * Every field is nullable because the source is nullable, and the UI draws only what is present: PRD
 * section 28's "never invent metadata" is the whole design of this type. There is no `UNKNOWN` sentinel
 * and no empty string standing in for a lens the camera did not record, because both would have to be
 * decoded back into "absent" at every call site, and one of them would eventually be drawn as if it were
 * a fact.
 */
data class MediaMetadata(
    val location: MediaLocation?,
    val altitudeMeters: Double?,
    val cameraMake: String?,
    val cameraModel: String?,
    val lensModel: String?,
    val focalLengthMm: Double?,
    val apertureF: Double?,
    val isoSpeed: Int?,
    val shutterSeconds: Double?,
) {
    val hasLocation: Boolean get() = location != null

    /**
     * Make and model as one label, without repeating the model when the make is already its prefix —
     * `"Apple"` + `"iPhone 15 Pro"` is an iPhone 15 Pro, not an Apple iPhone 15 Pro.
     */
    val cameraLabel: String?
        get() = listOfNotNull(cameraMake, cameraModel)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .let { parts ->
                when {
                    parts.isEmpty() -> null
                    parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true) -> parts[1]
                    else -> parts.joinToString(" ")
                }
            }

    /** Whether anything at all came out of the file, which is what the details panel reads to decide whether to exist. */
    val hasCameraInformation: Boolean
        get() = cameraLabel != null || lensModel != null || focalLengthMm != null ||
            apertureF != null || isoSpeed != null || shutterSeconds != null
}

/**
 * A capture position, always both halves or neither.
 *
 * Latitude and longitude are stored as separate EXIF fields with separate references, so a file can carry
 * one and not the other — and half a coordinate is not a location. The extractor therefore emits this
 * type or null, and no later layer has to remember to check the pair.
 */
data class MediaLocation(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            "a position outside the possible range is not a location"
        }
    }
}

/**
 * The rectangle the map is currently showing, in degrees, with the east/west edge wrapped into a single
 * contiguous span when the view crosses the antimeridian.
 */
data class MapBounds(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
) {
    val isValid: Boolean
        get() = minLatitude <= maxLatitude && minLongitude <= maxLongitude
}

/** One positioned photo, as the map and its strip draw it. */
data class MapPhoto(
    val mediaStoreId: Long,
    val contentUri: String,
    val type: MediaType,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val dateTakenSeconds: Long?,
    val dateAddedSeconds: Long,
) {
    val location: MediaLocation get() = MediaLocation(latitude, longitude)

    /**
     * What a preview may put in a date slot, or null when it may not put a date at all.
     *
     * The capture time only, never the day the file turned up: PRD section 28's "never invent metadata"
     * means a marker label saying 2019 has to be a 2019 the device actually recorded, and an unlabeled
     * thumbnail asks nothing of the user's trust.
     */
    val captionDateSeconds: Long? get() = dateTakenSeconds
}
