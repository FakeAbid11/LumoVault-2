package com.lumovault.app.data.local.metadata

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What only the file itself can say: where it was taken, and which camera took it.
 *
 * This is a separate table rather than four more columns on `media`, for the reason every related table
 * here has existed for: `media` is rewritten with `INSERT OR REPLACE` by every scan, so a value that cost
 * something to obtain would be deleted by the next pull-to-refresh. A GPS fix read out of EXIF is exactly
 * such a value — and unlike a backup hash it cannot be re-derived cheaply, because re-reading the file is
 * the only way to get it back.
 *
 * There is no foreign key to `media` either. The same cascade hazard that rules one out for `album_media`
 * applies here with more force: a parent row the scanner replaces would empty this table on every sync.
 * Rows are instead removed explicitly, when a file is confirmed gone, and swept once per scan for the
 * ones that left the device outside the app.
 *
 * Every field is nullable because EXIF fields genuinely are, and a blank is the honest answer: PRD section
 * 28's "never invent metadata" is why there is no `DEFAULT ''` standing in for a camera model the file does
 * not name. A row exists only for an item whose bytes were actually read, so a library nobody has opened
 * keeps no rows here at all.
 */
@Entity(
    tableName = "media_metadata",
    indices = [
        // The map asks one question of this table — what is inside the rectangle on screen — and it asks
        // it on every pan. Leading with the latitude is what keeps that a range scan rather than a walk
        // over every photo the device holds.
        Index("latitude", "longitude"),
    ],
)
data class MediaMetadataEntity(
    /** MediaStore id, matching `media`, `media_organization` and `backup_queue`. */
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,

    /** Degrees north, WGS 84; null when the file carries no GPS latitude. */
    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    /** Degrees east; null when the file carries no GPS longitude. */
    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    /** Elevation in metres above the WGS 84 ellipsoid, if the file states one. */
    @ColumnInfo(name = "altitude_meters")
    val altitudeMeters: Double? = null,

    @ColumnInfo(name = "camera_make")
    val cameraMake: String? = null,

    @ColumnInfo(name = "camera_model")
    val cameraModel: String? = null,

    @ColumnInfo(name = "lens_model")
    val lensModel: String? = null,

    @ColumnInfo(name = "focal_length_mm")
    val focalLengthMm: Double? = null,

    /** The f-number — the aperture ratio, not a physical diameter. */
    @ColumnInfo(name = "aperture_f")
    val apertureF: Double? = null,

    @ColumnInfo(name = "iso_speed")
    val isoSpeed: Int? = null,

    /** Exposure time in seconds; 0.004 means 1/250 s. */
    @ColumnInfo(name = "shutter_seconds")
    val shutterSeconds: Double? = null,

    /**
     * When this row was written, in seconds; 0 means it never has been.
     *
     * Recording the attempt rather than only the success is what keeps an unreadable file from being
     * reopened on every future pass: a photo whose EXIF cannot be parsed is not a candidate any more, and
     * that is a fact about the file, not a pending task.
     */
    @ColumnInfo(name = "extracted_at", defaultValue = "0")
    val extractedAt: Long = 0,
)
