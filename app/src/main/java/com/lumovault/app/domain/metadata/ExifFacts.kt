package com.lumovault.app.domain.metadata

import com.lumovault.app.domain.model.MediaLocation
import com.lumovault.app.domain.model.MediaMetadata
import kotlin.math.pow

/**
 * The raw answers one file's EXIF block gave, converted into LumoVault's metadata model.
 *
 * Split from the reader because the conversions are where the wrong answers live, and they are all
 * decidable without an Android device:
 *
 *  - **Shutter speed.** EXIF stores `ShutterSpeedValue` as an *apex* — a signed base-2 logarithm of the
 *    exposure time, so 8.0 means 1/256 s, not 8 seconds. `ExposureTime` is the actual duration. A viewer
 *    that read the apex as seconds would print "8s" under a daylight handheld shot, so the duration is
 *    preferred and the apex is only the fallback.
 *  - **Blank versus absent.** Android returns an empty string for a tag a camera wrote as an empty field.
 *    Those become null; the UI must not render a label for a fact the file does not hold (PRD section 28).
 *  - **Zero.** A focal length, F-number or exposure of 0 is "not recorded" — a real lens is never 0 mm and
 *    a real shot is never 0 s. A latitude/longitude of exactly 0/0 is treated the same way: it is the
 *    placeholder broken GPS chips write, and a marker off the coast of Africa that claims a photo was taken
 *    there is a worse error than one missing marker.
 *  - **Range.** Coordinates outside ±90/±180 are not positions at all, and [MediaLocation] refuses them —
 *    checked here first so a bad tag degrades to "no location" instead of failing the pass.
 */
data class ExifFacts(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val focalLengthMm: Double? = null,
    val fNumber: Double? = null,
    val isoSpeed: Int? = null,
    val exposureTimeSeconds: Double? = null,
    val shutterValueApex: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
) {

    /** The position, or null when the pair is missing, placeholder, or impossible. */
    val location: MediaLocation?
        get() {
            val lat = latitude ?: return null
            val lon = longitude ?: return null
            if (lat == NO_FIX && lon == NO_FIX) return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return MediaLocation(lat, lon)
        }

    /**
     * Exposure duration in seconds.
     *
     * `ExposureTime` when it is there; otherwise the apex converted, because some cameras write only the
     * apex. A negative apex is a longer-than-one-second exposure and a positive one is shorter, which is
     * the inversion that makes reading the raw value as seconds such an easy mistake.
     */
    val shutterSeconds: Double?
        get() {
            exposureTimeSeconds?.takeIf { it > 0.0 && it.isFinite() }?.let { return it }
            val apex = shutterValueApex ?: return null
            if (!apex.isFinite() || apex > MAX_APEX_SECONDS || apex < MIN_APEX_SECONDS) return null
            return 1.0 / 2.0.pow(apex)
        }

    val focalLength: Double? get() = focalLengthMm?.takeIf { it > 0.0 && it.isFinite() }
    val aperture: Double? get() = fNumber?.takeIf { it > 0.0 && it.isFinite() }

    /**
     * Altitude keeps its zero.
     *
     * Unlike the fields above, "0" here is a real answer — sea level — and the reader has already done the
     * only honest absence check available, which is whether the tag was in the file at all.
     */
    val altitude: Double? get() = altitudeMeters?.takeIf { it.isFinite() }

    /**
     * The metadata to store, or null when the file said nothing usable.
     *
     * The caller records a null as "read, nothing there" rather than leaving the item as a permanent
     * candidate, so this has to be the single place that decides what counts as something.
     */
    fun toMediaMetadata(): MediaMetadata? {
        val shot = MediaMetadata(
            location = location,
            altitudeMeters = altitude,
            cameraMake = text(make),
            cameraModel = text(model),
            lensModel = text(lensModel),
            focalLengthMm = focalLength,
            apertureF = aperture,
            isoSpeed = isoSpeed?.takeIf { it > 0 },
            shutterSeconds = shutterSeconds,
        )
        return shot.takeIf { it.hasLocation || it.hasCameraInformation || it.altitudeMeters != null }
    }

    private fun text(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /** The 0/0 the placeholder writers emit — "null island", and not where the photo is. */
        private const val NO_FIX = 0.0

        /**
         * Apex bounds worth converting. 2^32 s is over a century and 2^-64 s is below any sensor's clock,
         * so a value outside this range is a corrupt tag rather than a long exposure.
         */
        private const val MAX_APEX_SECONDS = 32.0
        private const val MIN_APEX_SECONDS = -64.0
    }
}
